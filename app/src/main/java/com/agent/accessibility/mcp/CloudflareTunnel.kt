package com.agent.accessibility.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File

/**
 * Manages a Cloudflare Quick Tunnel (cloudflared) process.
 * Creates a public HTTPS URL that proxies to localhost:8765.
 * No Cloudflare account required — URL changes on each restart.
 */
class CloudflareTunnel(private val context: Context) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        private const val BINARY_NAME = "libcloudflared.so"
        private const val LOCAL_URL = "http://localhost:8765"
        private val URL_REGEX = Regex("""https://[a-zA-Z0-9-]+\.trycloudflare\.com[^\s]*""")
    }

    interface Listener {
        fun onUrlAvailable(url: String) {}
        fun onStopped() {}
        fun onError(error: String) {}
    }

    private var process: Process? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isRunning: Boolean = false
        private set

    var publicUrl: String? = null
        private set

    var listener: Listener? = null

    /**
     * Start the cloudflared tunnel process.
     * Copies the binary from nativeLibraryDir to a writable location and executes it.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Tunnel already running")
            return
        }

        try {
            val binaryFile = prepareBinary() ?: run {
                listener?.onError("Failed to prepare cloudflared binary")
                return
            }

            // Make binary executable
            binaryFile.setExecutable(true, false)

            Log.d(TAG, "Starting cloudflared from: ${binaryFile.absolutePath}")

            val pb = ProcessBuilder(
                binaryFile.absolutePath,
                "tunnel",
                "--url", LOCAL_URL
            )
            pb.redirectErrorStream(false)
            pb.environment()["HOME"] = context.cacheDir.absolutePath

            process = pb.start()
            isRunning = true

            // Read stdout for URL in background
            readerJob = scope.launch {
                try {
                    val reader = process!!.inputStream.bufferedReader()
                    readOutput(reader)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error reading tunnel output", e)
                    }
                }
            }

            // Also read stderr for errors
            scope.launch {
                try {
                    val reader = process!!.errorStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "stderr: $line")
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error reading tunnel stderr", e)
                    }
                }
            }

            // Wait for process exit in background
            scope.launch {
                try {
                    val exitCode = process!!.waitFor()
                    Log.d(TAG, "cloudflared exited with code: $exitCode")
                } catch (e: Exception) {
                    Log.d(TAG, "Process wait interrupted")
                } finally {
                    withContext(Dispatchers.Main) {
                        stop()
                        listener?.onStopped()
                    }
                }
            }

            Log.d(TAG, "Tunnel process started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel", e)
            isRunning = false
            listener?.onError("Failed to start tunnel: ${e.message}")
        }
    }

    /**
     * Stop the cloudflared tunnel process.
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        publicUrl = null
        readerJob?.cancel()
        readerJob = null

        try {
            process?.destroyForcibly()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying process", e)
        }
        process = null
        Log.d(TAG, "Tunnel stopped")
    }

    /**
     * Destroy the tunnel and clean up resources.
     */
    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun readOutput(reader: BufferedReader) {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            Log.d(TAG, "stdout: $l")

            // Check for the public URL
            if (publicUrl == null) {
                val match = URL_REGEX.find(l)
                if (match != null) {
                    publicUrl = match.value
                    Log.d(TAG, "URL found: $publicUrl")
                    // Notify on main thread
                    scope.launch(Dispatchers.Main) {
                        listener?.onUrlAvailable(publicUrl!!)
                    }
                }
            }
        }
    }

    /**
     * Copy the bundled binary to a writable location.
     * Returns the File object or null on failure.
     */
    private fun prepareBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val sourceFile = File(nativeDir, BINARY_NAME)

        // If the binary exists in nativeLibraryDir (extracted by Android), use it directly
        if (sourceFile.exists()) {
            Log.d(TAG, "Binary found in nativeLibraryDir: ${sourceFile.absolutePath}")
            // Copy to cache dir since native dir may be read-only on some devices
            val cacheFile = File(context.cacheDir, "cloudflared")
            if (!cacheFile.exists() || cacheFile.length() != sourceFile.length()) {
                sourceFile.copyTo(cacheFile, overwrite = true)
            }
            return cacheFile
        }

        // Fallback: check if it was already copied to cache
        val cacheFile = File(context.cacheDir, "cloudflared")
        if (cacheFile.exists() && cacheFile.canExecute()) {
            Log.d(TAG, "Using cached binary: ${cacheFile.absolutePath}")
            return cacheFile
        }

        Log.e(TAG, "Binary not found at: ${sourceFile.absolutePath}")
        Log.e(TAG, "nativeLibraryDir contents: ${File(nativeDir).list()?.toList()}")
        return null
    }
}
