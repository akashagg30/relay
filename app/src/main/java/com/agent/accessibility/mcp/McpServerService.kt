package com.agent.accessibility.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.agent.accessibility.MainActivity
import org.json.JSONArray
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
        private const val CONSENT_REQUEST_ID = 2

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
    private lateinit var rateLimiter: RateLimiter
    private lateinit var auditLogger: AuditLogger
    private lateinit var consentPrefs: SharedPreferences
    private val executor = Executors.newCachedThreadPool()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        authManager = AuthManager(this)
        mcpHandler = McpHandler(this)
        rateLimiter = RateLimiter()
        auditLogger = AuditLogger(this)
        consentPrefs = getSharedPreferences("mcp_consent", Context.MODE_PRIVATE)
        authToken = authManager.token
        Log.d(TAG, "MCP Server service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startServer()
            "STOP" -> stopServer()
            "APPROVE_CLIENT" -> {
                val clientIp = intent.getStringExtra("client_ip")
                if (clientIp != null) {
                    approveClient(clientIp)
                    showInfoNotification(clientIp, false)
                }
            }
            "DENY_CLIENT" -> {
                val clientIp = intent.getStringExtra("client_ip")
                if (clientIp != null) {
                    denyClient(clientIp)
                }
            }
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
            mcpHandler.init()
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
        mcpHandler.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "MCP Server stopped")
    }

    private fun handleClient(socket: Socket) {
        var keepAlive = false
        val clientIp = socket.inetAddress.hostAddress ?: "unknown"
        
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

            Log.d(TAG, "Request: $method $path from $clientIp Headers: ${headers.filter { it.key == "accept" || it.key == "content-type" }}")

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

            // Security checks for MCP endpoints
            if (path == "/mcp" && method == "POST") {
                // Rate limiting check
                if (!rateLimiter.isAllowed(clientIp)) {
                    val response = rateLimitResponse()
                    auditLogger.logRequest(clientIp, "rate_limited", "POST /mcp", 429)
                    sendHttpResponse(output, response.first, response.second)
                    return
                }

                // Consent check for new clients
                if (!isClientApproved(clientIp)) {
                    showConsentPrompt(clientIp)
                    val response = consentRequiredResponse()
                    auditLogger.logRequest(clientIp, "consent_required", "POST /mcp", 403)
                    sendHttpResponse(output, response.first, response.second)
                    return
                }
            }

            val accept = headers["accept"] ?: ""
            val response = when {
                method == "GET" && path == "/health" -> handleHealth()
                method == "GET" && path == "/mcp" -> handleSseGet()
                method == "POST" && path == "/mcp" -> handleMcp(headers["authorization"], body, accept, clientIp)
                method == "OPTIONS" -> corsResponse()
                else -> notFound()
            }

            // Log the request
            val authStatus = if (path == "/mcp" && method == "POST") {
                if (authManager.validate(headers["authorization"])) "accepted" else "rejected"
            } else "n/a"
            auditLogger.logRequest(clientIp, authStatus, "$method $path", response.first)

            if (response.third) {
                // SSE response
                val eventType = if (method == "GET") "endpoint" else "message"
                sendSseResponse(output, response.second, eventType)
                // For POST SSE: close stream after response (client expects it)
                // For GET SSE: keep alive (legacy transport)
                if (method != "GET") {
                    keepAlive = false
                }
            } else {
                sendHttpResponse(output, response.first, response.second)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
            auditLogger.logRequest(clientIp, "error", "unknown", 500)
        } finally {
            try {
                if (!keepAlive) socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleHealth(): Triple<Int, String, Boolean> {
        val accessibilityEnabled = isAccessibilityEnabled()
        val foregroundPackage = getForegroundPackage()

        val health = JSONObject().apply {
            put("status", "ok")
            put("accessibility", accessibilityEnabled)
            put("foregroundPackage", foregroundPackage ?: "unknown")
            put("mcpServer", isRunning)
            put("port", port)
        }
        return Triple(200, health.toString(), false)
    }

    private fun handleSseGet(): Triple<Int, String, Boolean> {
        // Legacy HTTP+SSE transport: return SSE stream with endpoint event
        // sendSseResponse will wrap in SSE format, so return raw JSON
        return Triple(200, """{"uri":"/mcp"}""", true)
    }

    private fun handleMcp(authHeader: String?, body: String, accept: String = "", clientIp: String = "unknown"): Triple<Int, String, Boolean> {
        if (!authManager.validate(authHeader)) {
            return Triple(401, """{"error":"Unauthorized"}""", false)
        }

        Log.d(TAG, "MCP request from $clientIp: ${body.take(200)}")
        val response = mcpHandler.handleRequest(body)

        // Empty response means it was a notification
        if (response.isEmpty()) {
            Log.d(TAG, "Notification handled (no response)")
            return Triple(204, "", false)
        }

        Log.d(TAG, "MCP response: ${response.take(200)}")
        
        // Always use SSE format for POST - OpenCode's fetch() expects it
        // when Accept includes text/event-stream
        val useSse = accept.contains("text/event-stream")
        return Triple(200, response, useSse)
    }

    private fun corsResponse(): Triple<Int, String, Boolean> {
        return Triple(200, "", false)
    }

    private fun rateLimitResponse(): Pair<Int, String> {
        return Pair(429, """{"error":"Too Many Requests"}""")
    }

    private fun consentRequiredResponse(): Pair<Int, String> {
        return Pair(403, """{"error":"Client not approved. Check phone for consent prompt."}""")
    }

    private fun isClientApproved(clientIp: String): Boolean {
        val approvedClients = getApprovedClients()
        
        // For the first connection (no approved clients yet), auto-approve but still show notification as info
        if (approvedClients.isEmpty()) {
            approveClient(clientIp)
            showInfoNotification(clientIp, true) // Auto-approved
            return true
        }
        
        return approvedClients.contains(clientIp)
    }

    private fun showConsentPrompt(clientIp: String) {
        val approveIntent = Intent(this, McpServerService::class.java).apply {
            action = "APPROVE_CLIENT"
            putExtra("client_ip", clientIp)
        }
        val approvePendingIntent = PendingIntent.getService(
            this, 0, approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val denyIntent = Intent(this, McpServerService::class.java).apply {
            action = "DENY_CLIENT"
            putExtra("client_ip", clientIp)
        }
        val denyPendingIntent = PendingIntent.getService(
            this, 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("New MCP Client Request")
            .setContentText("Client $clientIp wants to connect")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .addAction(Notification.Action.Builder(
                null, "Allow", approvePendingIntent
            ).build())
            .addAction(Notification.Action.Builder(
                null, "Deny", denyPendingIntent
            ).build())
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(CONSENT_REQUEST_ID, notification)
    }

    private fun showInfoNotification(clientIp: String, autoApproved: Boolean) {
        val message = if (autoApproved) {
            "First client $clientIp auto-approved"
        } else {
            "Client $clientIp approved"
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP Client Status")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(CONSENT_REQUEST_ID + 1, notification)
    }

    fun approveClient(clientIp: String) {
        val approvedClients = getApprovedClients().toMutableSet()
        approvedClients.add(clientIp)
        saveApprovedClients(approvedClients)
        
        // Cancel consent prompt notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(CONSENT_REQUEST_ID)
        
        Log.d(TAG, "Client approved: $clientIp")
    }

    fun denyClient(clientIp: String) {
        // Cancel consent prompt notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(CONSENT_REQUEST_ID)
        
        Log.d(TAG, "Client denied: $clientIp")
    }

    private fun getApprovedClients(): Set<String> {
        val json = consentPrefs.getString("approved_clients", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse approved clients", e)
            emptySet()
        }
    }

    private fun saveApprovedClients(clients: Set<String>) {
        val array = JSONArray()
        clients.forEach { array.put(it) }
        consentPrefs.edit().putString("approved_clients", array.toString()).apply()
    }

    fun getApprovedClientsList(): List<String> {
        return getApprovedClients().toList()
    }

    fun removeApprovedClient(clientIp: String) {
        val approvedClients = getApprovedClients().toMutableSet()
        approvedClients.remove(clientIp)
        saveApprovedClients(approvedClients)
    }

    fun regenerateAuthToken(): String {
        val newToken = authManager.regenerateToken()
        authToken = newToken
        // Update the foreground notification to show new token
        if (isRunning) {
            startForegroundWithNotification()
        }
        return newToken
    }

    fun getAuditLogs(limit: Int = 10): List<AuditLogger.AuditEntry> {
        return auditLogger.getRecentLogs(limit)
    }

    fun clearAuditLogs() {
        auditLogger.clearLogs()
    }

    private fun notFound(): Triple<Int, String, Boolean> {
        return Triple(404, """{"error":"Not found"}""", false)
    }

    private fun sendHttpResponse(output: OutputStream, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
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

    private fun sendSseResponse(output: OutputStream, body: String, eventType: String = "message") {
        val sseData = "event: $eventType\r\ndata: $body\r\n\r\n"
        val bytes = sseData.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, Accept\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        output.write(bytes)
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
            description = "Relay MCP Server"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Relay MCP Server")
            .setContentText("Port $port | Token: ${authToken.take(8)}...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
