// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM tests for [TunnelProcess] using a fake [Process] backed by piped
 * streams. No Android dependencies, no real cloudflared binary.
 */
class TunnelProcessTest {

    private open class FakeProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
        private val exitLatch: CountDownLatch = CountDownLatch(1)
    ) : Process() {
        @Volatile var exitCodeValue: Int = 0
        @Volatile var destroyed: Boolean = false

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun waitFor(): Int {
            exitLatch.await()
            return exitCodeValue
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return exitLatch.await(timeout, unit)
        }

        override fun exitValue(): Int {
            if (exitLatch.count > 0) throw IllegalThreadStateException("not exited")
            return exitCodeValue
        }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        fun exit(code: Int) {
            exitCodeValue = code
            exitLatch.countDown()
        }
    }

    private class Harness(
        onUrl: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val stdoutRead = PipedInputStream(65536)
        val stderrRead = PipedInputStream(65536)
        val stdoutWrite = PipedOutputStream(stdoutRead)
        val stderrWrite = PipedOutputStream(stderrRead)
        val fake = FakeProcess(stdoutRead, stderrRead)
        val commands = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()
        val tunnel = TunnelProcess(
            launcher = { command, env ->
                commands.add(command)
                envs.add(env)
                fake
            },
            onUrl = onUrl,
            onExit = onExit,
            onError = onError,
            log = {}
        )

        fun start(port: Int = 8765) {
            tunnel.start("/fake/nativeLibraryDir/libcloudflared.so", port, File("/fake/home"))
        }
    }

    private fun awaitCondition(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (cond()) return true
            if (System.currentTimeMillis() >= deadline) return cond()
            Thread.sleep(50)
        }
    }

    @Test
    fun `url arrives when only stderr carries it (the real-world case)`() {
        val urlLatch = CountDownLatch(1)
        var seenUrl: String? = null
        val h = Harness(onUrl = { seenUrl = it; urlLatch.countDown() })
        try {
            h.start()
            h.stderrWrite.write(
                "2026-10-28T17:26:40Z INF |  https://patch-cassette-golden-carb.trycloudflare.com                                      |\n".toByteArray()
            )
            h.stderrWrite.flush()
            assertTrue("URL from stderr observed", urlLatch.await(5, TimeUnit.SECONDS))
            assertEquals("https://patch-cassette-golden-carb.trycloudflare.com", h.tunnel.publicUrl)
            assertEquals("https://patch-cassette-golden-carb.trycloudflare.com", seenUrl)
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `url arrives when only stdout carries it`() {
        val urlLatch = CountDownLatch(1)
        val h = Harness(onUrl = { urlLatch.countDown() })
        try {
            h.start()
            h.stdoutWrite.write("INF https://stdout-sample.trycloudflare.com\n".toByteArray())
            h.stdoutWrite.flush()
            assertTrue("URL from stdout observed", urlLatch.await(5, TimeUnit.SECONDS))
            assertEquals("https://stdout-sample.trycloudflare.com", h.tunnel.publicUrl)
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `deadlock regression - 512KB on stderr drains and exit is observed`() {
        val exitLatch = CountDownLatch(1)
        val h = Harness(onExit = { exitLatch.countDown() })
        try {
            h.start()
            val totalBytes = 512 * 1024
            val writer = Thread {
                try {
                    val chunk = ("x".repeat(63) + "\n").toByteArray()
                    var written = 0
                    while (written < totalBytes) {
                        h.stderrWrite.write(chunk)
                        written += chunk.size
                    }
                    h.stderrWrite.flush()
                } catch (_: Exception) {
                }
            }
            writer.isDaemon = true
            writer.start()
            writer.join(10_000)
            if (writer.isAlive) fail("stderr writer blocked: stderr is not being drained")
            h.fake.exit(0)
            assertTrue("exit observed within 10s", exitLatch.await(10, TimeUnit.SECONDS))
            assertTrue(
                "not running after exit",
                awaitCondition(5_000) { !h.tunnel.isRunning }
            )
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `start twice invokes launcher exactly once`() {
        val h = Harness()
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            h.start()
            h.start()
            assertEquals(1, h.commands.size)
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `process exit clears state and fires onExit once`() {
        val exitLatch = CountDownLatch(1)
        var exitCount = 0
        val h = Harness(onExit = { exitCount++; exitLatch.countDown() })
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            h.stderrWrite.write("INF https://patch-cassette-golden-carb.trycloudflare.com\n".toByteArray())
            h.stderrWrite.flush()
            assertTrue(awaitCondition(5_000) { h.tunnel.publicUrl != null })
            h.fake.exit(1)
            assertTrue("onExit fired", exitLatch.await(10, TimeUnit.SECONDS))
            assertTrue(awaitCondition(5_000) { !h.tunnel.isRunning })
            assertNull(h.tunnel.publicUrl)
            Thread.sleep(300)
            assertEquals(1, exitCount)
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `stop destroys forcibly and clears running`() {
        val h = Harness()
        h.start()
        assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
        h.tunnel.stop()
        assertTrue(h.fake.destroyed)
        assertTrue(awaitCondition(5_000) { !h.tunnel.isRunning })
    }

    @Test
    fun `launcher failure sets lastError and fires onError with same text`() {
        val errLatch = CountDownLatch(1)
        var errText: String? = null
        val tunnel = TunnelProcess(
            launcher = { _, _ -> throw RuntimeException("boom-launch-failure") },
            onError = { errText = it; errLatch.countDown() },
            log = {}
        )
        tunnel.start("/fake/nativeLibraryDir/libcloudflared.so", 8765, File("/fake/home"))
        assertTrue("onError fired", errLatch.await(5, TimeUnit.SECONDS))
        val last = tunnel.lastError
        assertTrue(last != null && "boom-launch-failure" in last)
        assertEquals(last, errText)
        assertTrue(!tunnel.isRunning)
    }

    @Test
    fun `command builds tunnel-url with caller port and HOME`() {
        val h = Harness()
        try {
            h.start(port = 8901)
            assertEquals(1, h.commands.size)
            assertEquals(
                listOf(
                    "/fake/nativeLibraryDir/libcloudflared.so",
                    "tunnel",
                    "--url",
                    "http://127.0.0.1:8901"
                ),
                h.commands.single()
            )
            assertEquals("/fake/home", h.envs.single()["HOME"])
        } finally {
            h.tunnel.stop()
        }
    }

    // ------------------------------------------------------------------
    // Tunnel diagnostics: rolling buffer, exitInfo/diagnostic, generation
    // guard (CARD 1 of 2 — surface WHY cloudflared dies).
    // ------------------------------------------------------------------

    /**
     * A [FakeProcess] whose [waitFor] swallows interrupts, simulating a
     * watcher thread that outlives stop() and fires late with a stale
     * generation.
     */
    private class UninterruptibleFake(
        stdout: InputStream,
        stderr: InputStream
    ) : FakeProcess(stdout, stderr) {
        override fun waitFor(): Int {
            while (true) {
                try {
                    return super.waitFor()
                } catch (_: InterruptedException) {
                    // Keep waiting: the stale watcher outlives stop().
                }
            }
        }
    }

    @Test
    fun `self-exit folds diagnostic with code duration and last stderr`() {
        val exitLatch = CountDownLatch(1)
        val h = Harness(onExit = { exitLatch.countDown() })
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            val marker = "tunnel-boom-stderr-marker"
            h.stderrWrite.write("INF $marker\n".toByteArray())
            h.stderrWrite.flush()
            assertTrue(
                "stderr line buffered",
                awaitCondition(5_000) { h.tunnel.bufferedLines.any { it.contains(marker) } }
            )
            h.fake.exit(1)
            assertTrue("onExit fired", exitLatch.await(10, TimeUnit.SECONDS))
            assertTrue(
                "diagnostic set",
                awaitCondition(5_000) { h.tunnel.diagnostic != null }
            )
            val d = h.tunnel.diagnostic!!
            assertTrue(
                "exact prefix",
                d.startsWith("cloudflared (libcloudflared.so) exited with code 1 after ")
            )
            assertTrue(
                "duration one-decimal",
                Regex("after \\d+\\.\\ds").containsMatchIn(d)
            )
            assertTrue("last stderr line", d.contains("last stderr: INF $marker"))
            val info = h.tunnel.exitInfo
            assertNotNull(info)
            assertEquals(1, info!!.exitCode)
            assertEquals("INF $marker", info.lastStderr)
            assertTrue(info.durationMs >= 0)
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `self-exit with no stderr falls back to last stdout`() {
        val exitLatch = CountDownLatch(1)
        val h = Harness(onExit = { exitLatch.countDown() })
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            val marker = "only-stdout-marker"
            h.stdoutWrite.write("INF $marker\n".toByteArray())
            h.stdoutWrite.flush()
            assertTrue(
                "stdout line buffered",
                awaitCondition(5_000) { h.tunnel.bufferedLines.any { it.contains(marker) } }
            )
            h.fake.exit(2)
            assertTrue("onExit fired", exitLatch.await(10, TimeUnit.SECONDS))
            assertTrue(
                "diagnostic set",
                awaitCondition(5_000) { h.tunnel.diagnostic != null }
            )
            val d = h.tunnel.diagnostic!!
            assertTrue(
                "exact prefix",
                d.startsWith("cloudflared (libcloudflared.so) exited with code 2 after ")
            )
            assertTrue(
                "stdout fallback",
                d.contains("no stderr output (last stdout: INF $marker)")
            )
            val info = h.tunnel.exitInfo
            assertNotNull(info)
            assertNull(info!!.lastStderr)
            assertEquals("INF $marker", info.lastStdout)
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `self-exit with no output reports no output captured`() {
        val exitLatch = CountDownLatch(1)
        val h = Harness(onExit = { exitLatch.countDown() })
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            h.fake.exit(3)
            assertTrue("onExit fired", exitLatch.await(10, TimeUnit.SECONDS))
            assertTrue(
                "diagnostic set",
                awaitCondition(5_000) { h.tunnel.diagnostic != null }
            )
            val d = h.tunnel.diagnostic!!
            assertTrue(
                "exact prefix",
                d.startsWith("cloudflared (libcloudflared.so) exited with code 3 after ")
            )
            assertTrue("no-output branch", d.contains("no output captured"))
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `launch failure sets diagnostic with launch prefix and null exitInfo`() {
        val tunnel = TunnelProcess(
            launcher = { _, _ -> throw RuntimeException("boom-launch-diag") },
            log = {}
        )
        tunnel.start("/fake/nativeLibraryDir/libcloudflared.so", 8765, File("/fake/home"))
        val d = tunnel.diagnostic
        assertNotNull(d)
        assertTrue("launch prefix", d!!.startsWith("cloudflared launch failed:"))
        assertTrue("mentions launch", "launch" in d)
        assertTrue("keeps lastError text", "boom-launch-diag" in d)
        assertNull(tunnel.exitInfo)
        assertTrue(!tunnel.isRunning)
    }

    @Test
    fun `buffer caps at 25 lines per stream oldest dropped first`() {
        val h = Harness()
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            for (i in 1..40) {
                h.stdoutWrite.write("stdout line $i\n".toByteArray())
                h.stderrWrite.write("stderr line $i\n".toByteArray())
            }
            h.stdoutWrite.flush()
            h.stderrWrite.flush()
            assertTrue(
                "buffer full",
                awaitCondition(10_000) { h.tunnel.bufferedLines.size == 50 }
            )
            val lines = h.tunnel.bufferedLines
            assertEquals(50, lines.size)
            assertEquals(25, lines.count { "[stdout]" in it })
            assertEquals(25, lines.count { "[stderr]" in it })
            assertTrue(lines.none { it.endsWith("stdout line 1") })
            assertTrue(lines.none { it.endsWith("stderr line 1") })
            assertTrue(lines.any { it.endsWith("stdout line 16") })
            assertTrue(lines.any { it.endsWith("stderr line 16") })
            assertTrue(lines.any { it.endsWith("stdout line 40") })
            assertTrue(lines.any { it.endsWith("stderr line 40") })
            assertTrue(
                "arrival order kept",
                lines.indexOfFirst { it.endsWith("stdout line 16") } <
                    lines.indexOfFirst { it.endsWith("stdout line 40") }
            )
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `buffer lines carry stream tag and HH-mm-ss-SSS timestamp`() {
        val h = Harness()
        try {
            h.start()
            assertTrue(awaitCondition(5_000) { h.tunnel.isRunning })
            h.stdoutWrite.write("ts-tag-stdout\n".toByteArray())
            h.stderrWrite.write("ts-tag-stderr\n".toByteArray())
            h.stdoutWrite.flush()
            h.stderrWrite.flush()
            assertTrue(
                "both lines buffered",
                awaitCondition(5_000) { h.tunnel.bufferedLines.size == 2 }
            )
            val lines = h.tunnel.bufferedLines
            val stamped = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[(stdout|stderr)] ")
            assertTrue("every line stamped", lines.all { stamped.containsMatchIn(it) })
            assertTrue(lines.any { "[stdout] ts-tag-stdout" in it })
            assertTrue(lines.any { "[stderr] ts-tag-stderr" in it })
        } finally {
            h.tunnel.stop()
        }
    }

    @Test
    fun `stale watcher after stop-start cannot overwrite new run`() {
        val stdout1r = PipedInputStream(65536)
        val stdout1w = PipedOutputStream(stdout1r)
        val stderr1r = PipedInputStream(65536)
        val stderr1w = PipedOutputStream(stderr1r)
        val stdout2r = PipedInputStream(65536)
        val stdout2w = PipedOutputStream(stdout2r)
        val stderr2r = PipedInputStream(65536)
        val stderr2w = PipedOutputStream(stderr2r)
        val fake1 = UninterruptibleFake(stdout1r, stderr1r)
        val fake2 = FakeProcess(stdout2r, stderr2r)
        val fakes = listOf(fake1, fake2)
        var calls = 0
        val tunnel = TunnelProcess(
            launcher = { _, _ -> fakes[calls++] },
            log = {}
        )
        try {
            tunnel.start("/fake/nativeLibraryDir/libcloudflared.so", 8765, File("/fake/home"))
            assertTrue(awaitCondition(5_000) { tunnel.isRunning })
            // User stops; the gen-1 watcher survives (swallowed interrupt).
            tunnel.stop()
            assertTrue(!tunnel.isRunning)
            // New run on a fresh process.
            tunnel.start("/fake/nativeLibraryDir/libcloudflared.so", 8765, File("/fake/home"))
            assertTrue(awaitCondition(5_000) { tunnel.isRunning })
            assertEquals(2, calls)
            // The OLD process exits late with a stale generation.
            fake1.exit(1)
            Thread.sleep(1000)
            assertTrue("new run still running", tunnel.isRunning)
            assertNull("stale watcher wrote no exitInfo", tunnel.exitInfo)
            assertNull("stale watcher wrote no diagnostic", tunnel.diagnostic)
        } finally {
            try {
                stdout1w.close()
            } catch (_: Exception) {
            }
            try {
                stderr1w.close()
            } catch (_: Exception) {
            }
            try {
                stdout2w.close()
            } catch (_: Exception) {
            }
            try {
                stderr2w.close()
            } catch (_: Exception) {
            }
            tunnel.stop()
        }
    }
}
