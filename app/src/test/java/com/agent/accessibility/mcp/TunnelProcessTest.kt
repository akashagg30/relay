// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
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

    private class FakeProcess(
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
}
