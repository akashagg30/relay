// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.io.File

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
 * @param launcher seam for process creation; defaults to a real ProcessBuilder.
 *   Tests inject a fake returning a stub [Process].
 * @param onUrl called once with the first public URL seen on either stream.
 * @param onExit called once when a started process exits on its own.
 * @param onError called once when launching fails, with the real message.
 * @param log sink for diagnostic lines; never android.util.Log.
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

    private val lock = Any()
    private var process: Process? = null
    private var pumpThreads: List<Thread> = emptyList()
    private var watcherThread: Thread? = null

    /** Generation counter: stale watcher/pump output from a previous run is ignored. */
    private var generation: Int = 0

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
            val command = listOf(executablePath, "tunnel", "--url", "http://127.0.0.1:$port")
            val env = mapOf("HOME" to homeDir.absolutePath)
            try {
                proc = launcher(command, env)
            } catch (e: Exception) {
                val msg = "${e.javaClass.name}: ${e.message} (executable=$executablePath)"
                lastError = msg
                isRunning = false
                log("TunnelProcess: launch failed: $msg")
                onError(msg)
                return
            }
            generation += 1
            gen = generation
            process = proc
            isRunning = true
        }

        log("TunnelProcess: started (port=$port)")
        val stdoutPump = pumpThread("tunnel-stdout", proc.inputStream.bufferedReader()::readLine, gen)
        val stderrPump = pumpThread("tunnel-stderr", proc.errorStream.bufferedReader()::readLine, gen)
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
            var fire = false
            synchronized(lock) {
                if (gen == generation) {
                    isRunning = false
                    publicUrl = null
                    fire = true
                }
            }
            if (fire) {
                log("TunnelProcess: exited with code $exitCode")
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
     * and watcher, and resets state. No-op when not running.
     */
    fun stop() {
        val proc: Process?
        val threads: List<Thread>
        synchronized(lock) {
            if (!isRunning && process == null) return
            generation += 1
            isRunning = false
            publicUrl = null
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

    private fun pumpThread(name: String, readLine: () -> String?, gen: Int): Thread {
        return Thread({
            try {
                while (true) {
                    val line = readLine() ?: break
                    handleLine(line)
                }
            } catch (e: Exception) {
                val active = synchronized(lock) { gen == generation }
                if (active) {
                    log("TunnelProcess: $name pump ended: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }, name).apply { isDaemon = true }
    }

    private fun handleLine(line: String) {
        log("TunnelProcess: $line")
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
}
