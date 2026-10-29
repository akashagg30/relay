// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Owns the whole cloudflared process lifecycle in pure JVM code (zero Android
 * dependencies) so it is unit-testable on the JVM.
 *
 * Real-world behaviour this class encodes:
 * - cloudflared prints the public quick-Tunnel URL on **stderr**, not stdout
 *   (stdout is empty), inside a box-drawing border. Both streams are pumped
 *   concurrently and both feed [TunnelUrlParser.extractUrl]; the first URL
 *   found on either stream wins.
 * - Neither stream may be left undrained: if stderr is not read, its pipe
 *   buffer fills and cloudflared blocks forever.
 * - The binary is executed from its installed location (nativeLibraryDir);
 *   copying it to cacheDir and executing from there is denied by W^X policy
 *   on Android 10+ with targetSdk 35.
 *
 * Diagnostics this class surfaces (the founder has no adb, so logcat is
 * unreadable on device):
 * - [bufferedLines]: rolling buffer of the last 25 lines per stream, each
 *   stamped `HH:mm:ss.SSS [stdout|stderr] <text>`, in arrival order.
 * - [exitInfo]/[diagnostic]: structured + one-line human-readable account of
 *   WHY the process died (launch failure vs self-exit with code, duration
 *   and last output). Both are written inside the same generation guard as
 *   [isRunning]/[publicUrl], and both are cleared by [start] and [stop].
 *
 * @param launcher seam for process creation; defaults to a real ProcessBuilder.
 *   Tests inject a fake returning a stub [Process].
 * @param onUrl called once with the first public URL seen on either stream.
 * @param onExit called once when a started process exits on its own.
 * @param onError called once when launching fails, with the real message.
 * @param log sink for diagnostic lines; never Android log.
 */
class TunnelProcess(
    private val launcher: (command: List<String>, env: Map<String, String>) -> Process = { command, env ->
        ProcessBuilder(command).apply {
            environment().putAll(env)
            redirectErrorStream(false)
        }.start()
    },
    private val onUrl: (String) -> Unit = {},
    private val onExit: (Int) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {

    /** Structured account of a self-exit: code, run duration, trailing output. */
    data class ExitInfo(
        val exitCode: Int,
        val durationMs: Long,
        val lastStderr: String?,
        val lastStdout: String?
    )

    companion object {
        /** Per-stream cap of the rolling output buffer. */
        const val BUFFER_LINES_PER_STREAM = 25

        internal fun buildExitDiagnostic(
            exitCode: Int,
            durationMs: Long,
            lastStderr: String?,
            lastStdout: String?
        ): String {
            val secs = String.format(Locale.US, "%.1f", durationMs / 1000.0)
            val base = "cloudflared (libcloudflared.so) exited with code $exitCode after ${secs}s"
            return when {
                !lastStderr.isNullOrBlank() -> "$base — last stderr: $lastStderr"
                !lastStdout.isNullOrBlank() -> "$base — no stderr output (last stdout: $lastStdout)"
                else -> "$base — no output captured"
            }
        }
    }

    private val lock = Any()
    private var process: Process? = null
    private var pumpThreads: List<Thread> = emptyList()
    private var watcherThread: Thread? = null

    /** Generation counter: stale watcher/pump output from a previous run is ignored. */
    private var generation: Int = 0

    /** Monotonic start time of the current run (System.currentTimeMillis). */
    private var runStartMs: Long = 0L

    /** Rolling output buffer (arrival order); guarded by [lock]. */
    private val buffer: ArrayDeque<String> = ArrayDeque()

    /** Last non-blank raw line per stream for the current run; guarded by [lock]. */
    private var lastStdoutRaw: String? = null
    private var lastStderrRaw: String? = null

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var publicUrl: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Structured account of the current run's self-exit, or null when the
     * process has not exited on its own. Cleared by [start] and [stop].
     */
    @Volatile
    var exitInfo: ExitInfo? = null
        private set

    /**
     * One-line human-readable account of WHY the tunnel died, or null when
     * healthy / never run. Superset of [lastError] (which stays launch-only):
     * launch failure yields `cloudflared launch failed: <lastError text>`;
     * self-exit yields
     * `cloudflared (libcloudflared.so) exited with code <c> after <d.d>s — …`.
     */
    @Volatile
    var diagnostic: String? = null
        private set

    /**
     * Immutable snapshot of the rolling output buffer: last 25 lines per
     * stream (max 50 total), oldest dropped first, in arrival order. Each
     * entry is `HH:mm:ss.SSS [stdout|stderr] <text>`.
     */
    val bufferedLines: List<String>
        get() = synchronized(lock) { buffer.toList() }

    /**
     * Starts `executablePath tunnel --url http://127.0.0.1:[port]` with
     * `HOME` pointing at [homeDir]. No-op when already running (one process
     * max). Launch failures set [lastError] to the real message
     * (exception class + message + executable path) and fire [onError].
     */
    fun start(executablePath: String, port: Int, homeDir: File) {
        val proc: Process
        val gen: Int
        synchronized(lock) {
            if (isRunning) {
                log("TunnelProcess: start() ignored, already running")
                return
            }
            lastError = null
            publicUrl = null
            exitInfo = null
            diagnostic = null
            buffer.clear()
            lastStdoutRaw = null
            lastStderrRaw = null
            val command = listOf(executablePath, "tunnel", "--url", "http://127.0.0.1:$port")
            val env = mapOf("HOME" to homeDir.absolutePath)
            try {
                proc = launcher(command, env)
            } catch (e: Exception) {
                val msg = "${e.javaClass.name}: ${e.message} (executable=$executablePath)"
                lastError = msg
                diagnostic = "cloudflared launch failed: $msg"
                isRunning = false
                log("TunnelProcess: launch failed: $msg")
                onError(msg)
                return
            }
            generation += 1
            gen = generation
            process = proc
            runStartMs = System.currentTimeMillis()
            isRunning = true
        }

        log("TunnelProcess: started (port=$port)")
        val stdoutPump = pumpThread("tunnel-stdout", "stdout", proc.inputStream.bufferedReader()::readLine, gen)
        val stderrPump = pumpThread("tunnel-stderr", "stderr", proc.errorStream.bufferedReader()::readLine, gen)
        synchronized(lock) {
            if (gen == generation) {
                pumpThreads = listOf(stdoutPump, stderrPump)
            }
        }
        val watcher = Thread({
            val exitCode = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            val durationMs = System.currentTimeMillis() - runStartMs
            var fire = false
            var diag: String? = null
            synchronized(lock) {
                if (gen == generation) {
                    isRunning = false
                    publicUrl = null
                    val err = lastStderrRaw
                    val out = lastStdoutRaw
                    exitInfo = ExitInfo(exitCode, durationMs, err, out)
                    diagnostic = buildExitDiagnostic(exitCode, durationMs, err, out)
                    diag = diagnostic
                    fire = true
                }
            }
            if (fire) {
                log("TunnelProcess: exited with code $exitCode")
                diag?.let { log("TunnelProcess: $it") }
                onExit(exitCode)
            }
        }, "tunnel-watcher").apply { isDaemon = true }
        synchronized(lock) {
            if (gen == generation) {
                watcherThread = watcher
            }
        }
        watcher.start()
        stdoutPump.start()
        stderrPump.start()
    }

    /**
     * Stops the process: destroys it forcibly, unblocks and joins the pumps
     * and watcher, and resets state. No-op when not running. User-initiated,
     * so a stale exit arriving afterwards must not resurrect [exitInfo] or
     * [diagnostic]: both are cleared here.
     */
    fun stop() {
        val proc: Process?
        val threads: List<Thread>
        synchronized(lock) {
            if (!isRunning && process == null) return
            generation += 1
            isRunning = false
            publicUrl = null
            exitInfo = null
            diagnostic = null
            proc = process
            process = null
            threads = pumpThreads + listOfNotNull(watcherThread)
            pumpThreads = emptyList()
            watcherThread = null
        }
        try {
            proc?.destroyForcibly()
        } catch (_: Exception) {
        }
        try {
            proc?.inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            proc?.errorStream?.close()
        } catch (_: Exception) {
        }
        threads.forEach { it.interrupt() }
        val self = Thread.currentThread()
        threads.forEach {
            if (it != self) {
                try {
                    it.join(2000)
                } catch (_: Exception) {
                }
            }
        }
        log("TunnelProcess: stopped")
    }

    private fun pumpThread(name: String, stream: String, readLine: () -> String?, gen: Int): Thread {
        return Thread({
            try {
                while (true) {
                    val line = readLine() ?: break
                    handleLine(line, stream)
                }
            } catch (e: Exception) {
                val active = synchronized(lock) { gen == generation }
                if (active) {
                    log("TunnelProcess: $name pump ended: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }, name).apply { isDaemon = true }
    }

    private fun handleLine(line: String, stream: String) {
        log("TunnelProcess: $line")
        appendBuffered(stream, line)
        if (publicUrl != null) return
        val url = TunnelUrlParser.extractUrl(line) ?: return
        var fire = false
        synchronized(lock) {
            if (publicUrl == null) {
                publicUrl = url
                fire = true
            }
        }
        if (fire) {
            log("TunnelProcess: public URL: $url")
            onUrl(url)
        }
    }

    /**
     * Appends one stamped line to the rolling buffer, evicting the oldest
     * lines of the same stream beyond [BUFFER_LINES_PER_STREAM]. Also tracks
     * the last non-blank raw line per stream for the exit diagnostic.
     */
    private fun appendBuffered(stream: String, raw: String) {
        synchronized(lock) {
            val stamped = "${timestampFormat.format(Date())} [$stream] $raw"
            buffer.addLast(stamped)
            val tag = "[$stream]"
            var count = 0
            for (entry in buffer) {
                if (tag in entry) count++
            }
            while (count > BUFFER_LINES_PER_STREAM) {
                val it = buffer.iterator()
                while (it.hasNext()) {
                    if (tag in it.next()) {
                        it.remove()
                        count--
                        break
                    }
                }
            }
            if (raw.isNotBlank()) {
                if (stream == "stderr") lastStderrRaw = raw else lastStdoutRaw = raw
            }
        }
    }
}
