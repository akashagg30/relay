// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Thin Android shell over [TunnelProcess], which owns the whole cloudflared
 * lifecycle and is unit-tested on the JVM.
 *
 * The bundled cloudflared binary (libcloudflared.so) is executed **in place**
 * from `applicationInfo.nativeLibraryDir` (an executable apk_data_file
 * mapping). It must NOT be copied to cacheDir first: app-data files lose
 * their executable mapping under W^X policy on Android 10+ with targetSdk 35,
 * so exec-after-copy fails with EACCES. `HOME` still points at
 * `context.cacheDir` so cloudflared has a writable home.
 *
 * The tunnel forwards to the caller's port: [start] takes the MCP server's
 * actual listening port (no hardcoded port here).
 */
class CloudflareTunnel(
    private val context: Context,
    tunnelFactory: (
        onUrl: (String) -> Unit,
        onExit: (Int) -> Unit,
        onError: (String) -> Unit,
        log: (String) -> Unit
    ) -> TunnelProcess = { onUrl, onExit, onError, log ->
        TunnelProcess(onUrl = onUrl, onExit = onExit, onError = onError, log = log)
    }
) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        private const val BINARY_NAME = "libcloudflared.so"
    }

    interface Listener {
        fun onUrlAvailable(url: String) {}
        fun onStopped() {}
        fun onError(error: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val tunnel: TunnelProcess = tunnelFactory(
        { url -> mainHandler.post { listener?.onUrlAvailable(url) } },
        { _ -> mainHandler.post { listener?.onStopped() } },
        { err ->
            lastError = err
            mainHandler.post { listener?.onError(err) }
        },
        { msg -> Log.d(TAG, msg) }
    )

    var listener: Listener? = null

    @Volatile
    var lastError: String? = null
        private set

    /** Real process state from [TunnelProcess]; never a flag set optimistically. */
    val isRunning: Boolean
        get() = tunnel.isRunning

    val publicUrl: String?
        get() = tunnel.publicUrl

    /**
     * Starts the tunnel forwarding to the given local [port].
     */
    fun start(port: Int) {
        if (tunnel.isRunning) {
            Log.w(TAG, "Tunnel already running")
            return
        }
        lastError = null

        val exe = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!exe.exists()) {
            val msg = "cloudflared binary not found at ${exe.absolutePath}"
            Log.e(TAG, msg)
            lastError = msg
            listener?.onError(msg)
            return
        }

        Log.d(TAG, "Starting cloudflared in place: ${exe.absolutePath} (port=$port)")
        tunnel.start(exe.absolutePath, port, context.cacheDir)
    }

    /**
     * Stops the tunnel.
     */
    fun stop() {
        if (!tunnel.isRunning) return
        tunnel.stop()
        Log.d(TAG, "Tunnel stopped")
        listener?.onStopped()
    }

    /**
     * Stops the tunnel and releases resources.
     */
    fun destroy() {
        tunnel.stop()
        Log.d(TAG, "Tunnel destroyed")
    }
}
