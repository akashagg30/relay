package com.agent.accessibility.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.agent.accessibility.MainActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class McpServerService : Service() {

    companion object {
        private const val TAG = "McpServer"
        private const val CHANNEL_ID = "mcp_server_channel"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_PORT = 8765

        var instance: McpServerService? = null
            private set

        var isRunning: Boolean = false
            private set

        var port: Int = DEFAULT_PORT
            private set

        var authToken: String = ""
            private set
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private lateinit var authManager: AuthManager
    private lateinit var mcpHandler: McpHandler
    private val executor = Executors.newCachedThreadPool()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        authManager = AuthManager(this)
        mcpHandler = McpHandler(this)
        authToken = authManager.token
        Log.d(TAG, "MCP Server service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startServer()
            "STOP" -> stopServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        instance = null
        super.onDestroy()
    }

    fun startServer() {
        if (isRunning) return

        try {
            val requestedPort = getPortFromPrefs()
            serverSocket = ServerSocket(requestedPort)
            port = requestedPort

            serverThread = Thread {
                while (!Thread.currentThread().isInterrupted && serverSocket?.isClosed != true) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.submit { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            isRunning = true
            startForegroundWithNotification()
            Log.d(TAG, "MCP Server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopSelf()
        }
    }

    fun stopServer() {
        isRunning = false
        serverThread?.interrupt()
        serverThread = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "MCP Server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(chars, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(chars, 0, read)
            } else ""

            val response = when {
                method == "GET" && path == "/health" -> handleHealth()
                method == "POST" && path == "/mcp" -> handleMcp(headers["authorization"], body)
                method == "OPTIONS" -> corsResponse()
                else -> notFound()
            }

            sendHttpResponse(output, response.first, response.second)
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleHealth(): Pair<Int, String> {
        val accessibilityEnabled = isAccessibilityEnabled()
        val foregroundPackage = getForegroundPackage()

        val health = JSONObject().apply {
            put("status", "ok")
            put("accessibility", accessibilityEnabled)
            put("foregroundPackage", foregroundPackage ?: "unknown")
            put("mcpServer", isRunning)
            put("port", port)
        }
        return 200 to health.toString()
    }

    private fun handleMcp(authHeader: String?, body: String): Pair<Int, String> {
        if (!authManager.validate(authHeader)) {
            return 401 to """{"error":"Unauthorized"}"""
        }

        Log.d(TAG, "MCP request: ${body.take(200)}")
        val response = mcpHandler.handleRequest(body)

        // Empty response means it was a notification
        if (response.isEmpty()) {
            Log.d(TAG, "Notification handled (no response)")
            return 204 to ""
        }

        Log.d(TAG, "MCP response: ${response.take(200)}")
        return 200 to response
    }

    private fun corsResponse(): Pair<Int, String> {
        return 200 to ""
    }

    private fun notFound(): Pair<Int, String> {
        return 404 to """{"error":"Not found"}"""
    }

    private fun sendHttpResponse(output: OutputStream, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            if (statusCode != 204) {
                append("Content-Type: application/json\r\n")
            }
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (statusCode != 204) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "${packageName}/${packageName}.service.AgentAccessibilityService"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    private fun getForegroundPackage(): String? {
        return com.agent.accessibility.service.AgentAccessibilityService.instance
            ?.rootInActiveWindow
            ?.packageName
            ?.toString()
    }

    private fun getPortFromPrefs(): Int {
        val prefs = getSharedPreferences("mcp_server", Context.MODE_PRIVATE)
        return prefs.getInt("port", DEFAULT_PORT)
    }

    fun setPort(newPort: Int) {
        port = newPort
        getSharedPreferences("mcp_server", Context.MODE_PRIVATE)
            .edit().putInt("port", newPort).apply()
    }

    private fun startForegroundWithNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Android Agent MCP Server"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Agent MCP Server")
            .setContentText("Running on port $port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
