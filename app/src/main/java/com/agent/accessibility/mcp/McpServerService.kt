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
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class McpServerService : Service() {

    companion object {
        private const val TAG = "McpServer"
        private const val CHANNEL_ID = "mcp_server_channel"
        private const val CONSENT_CHANNEL_ID = "mcp_consent_channel"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_PORT = 8765
        private const val CONSENT_REQUEST_ID = 2
        private const val MAX_BODY_SIZE = 1_048_576 // 1MB

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
    private val executor = Executors.newFixedThreadPool(10)

    // Cloudflare Tunnel
    var cloudflareTunnel: CloudflareTunnel? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        authManager = AuthManager(this)
        mcpHandler = McpHandler(this)
        rateLimiter = RateLimiter()
        auditLogger = AuditLogger(this)
        consentPrefs = getSharedPreferences("mcp_consent", Context.MODE_PRIVATE)
        authToken = authManager.token
        cloudflareTunnel = CloudflareTunnel(this)
        Log.d(TAG, "MCP Server service created")
    }

    /**
     * Creates all notification channels early in the service lifecycle.
     * The consent channel uses IMPORTANCE_HIGH so the notification appears
     * as a heads-up prompt (sound + vibration + heads-up banner).
     */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Foreground service channel — LOW is fine (persistent, silent)
        val foregroundChannel = NotificationChannel(
            CHANNEL_ID,
            "MCP Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Relay MCP Server foreground service"
        }

        // Consent prompt channel — HIGH so it shows as heads-up with sound
        val consentChannel = NotificationChannel(
            CONSENT_CHANNEL_ID,
            "MCP Client Consent",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Prompts when a new MCP client requests access"
            enableVibration(true)
            enableLights(true)
        }

        manager.createNotificationChannel(foregroundChannel)
        manager.createNotificationChannel(consentChannel)
        Log.d(TAG, "Notification channels created (foreground=$CHANNEL_ID, consent=$CONSENT_CHANNEL_ID)")
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
            "START_TUNNEL" -> startTunnel()
            "STOP_TUNNEL" -> stopTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cloudflareTunnel?.destroy()
        cloudflareTunnel = null
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
        // Stop tunnel if running (it depends on the MCP server)
        stopTunnel()
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
            val rawInput = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readStreamLine(rawInput) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = readStreamLine(rawInput) ?: break
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
                if (contentLength > MAX_BODY_SIZE) {
                    Log.w(TAG, "Body too large: $contentLength bytes from $clientIp")
                    val response = tooLargeResponse()
                    sendHttpResponse(output, response.first, response.second)
                    return
                }
                val bytes = ByteArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = rawInput.read(bytes, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                String(bytes, 0, off, Charsets.UTF_8)
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
                    // Auto-approve local network clients (safe: on same machine or LAN)
                    if (isLocalNetworkClient(clientIp)) {
                        approveClient(clientIp)
                        showInfoNotification(clientIp, true)
                        Log.d(TAG, "Auto-approved local network client: $clientIp")
                    } else {
                        showConsentPrompt(clientIp)
                        val response = consentRequiredResponse()
                        auditLogger.logRequest(clientIp, "consent_required", "POST /mcp", 403)
                        sendHttpResponse(output, response.first, response.second)
                        return
                    }
                }
            }

            val accept = headers["accept"] ?: ""
            val response = when {
                method == "GET" && path == "/health" -> handleHealth()
                method == "GET" && path == "/mcp" -> handleSseGet()
                method == "POST" && path == "/mcp" -> handleMcp(headers["authorization"], body, accept, clientIp)
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
        val accessibilityRunning = isAccessibilityServiceRunning()
        val foregroundPackage = getForegroundPackage()

        val health = JSONObject().apply {
            put("status", "ok")
            put("accessibility", accessibilityEnabled)
            put("accessibilityRunning", accessibilityRunning)
            put("foregroundPackage", foregroundPackage ?: "unknown")
            put("mcpServer", isRunning)
            put("observeFallbackCount", mcpHandler.observeFallbackCount)
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

    private fun rateLimitResponse(): Pair<Int, String> {
        return Pair(429, """{"error":"Too Many Requests"}""")
    }

    private fun consentRequiredResponse(): Pair<Int, String> {
        return Pair(403, """{"error":"Client not approved. Check phone for consent prompt."}""")
    }

    private fun tooLargeResponse(): Pair<Int, String> {
        return Pair(413, """{"error":"Request body too large"}""")
    }

    private fun isClientApproved(clientIp: String): Boolean {
        val approvedClients = getApprovedClients()
        return approvedClients.contains(clientIp)
    }

    private fun showConsentPrompt(clientIp: String) {
        // Check if notifications are allowed (Android 13+ needs runtime permission)
        if (!canNotify()) {
            Log.w(TAG, "Notifications not permitted — auto-approving client $clientIp")
            approveClient(clientIp)
            showInfoNotification(clientIp, true)
            return
        }

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

        val notification = Notification.Builder(this, CONSENT_CHANNEL_ID)
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
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(CONSENT_REQUEST_ID, notification)
        Log.d(TAG, "Consent notification posted for $clientIp (channel=$CONSENT_CHANNEL_ID)")
    }

    /**
     * Checks whether this app has POST_NOTIFICATIONS permission.
     * On Android < 13 this always returns true.
     */
    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return androidx.core.app.ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns true for addresses on the loopback, 10.x, 172.16-31.x, 192.168.x, or link-local ranges.
     */
    private fun isLocalNetworkClient(ip: String): Boolean {
        val clean = ip.removePrefix("::ffff:")
        return clean == "127.0.0.1" || clean == "::1" ||
                clean.startsWith("10.") ||
                clean.startsWith("192.168.") ||
                clean.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")) ||
                clean.startsWith("169.254.")
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

    private var cloudflaredProcess: Process? = null
    private var cloudflaredUrl: String? = null

    fun startTunnel() {
        if (!isRunning) {
            Log.w(TAG, "Cannot start tunnel: MCP server not running")
            return
        }
        if (cloudflaredProcess != null) {
            Log.w(TAG, "Tunnel already running")
            return
        }
        
        try {
            val binaryFile = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
            if (!binaryFile.exists()) {
                Log.e(TAG, "cloudflared binary not found")
                return
            }
            
            // Copy to cache and make executable
            val cacheFile = File(cacheDir, "cloudflared")
            binaryFile.copyTo(cacheFile, overwrite = true)
            cacheFile.setExecutable(true, false)
            
            val pb = ProcessBuilder(
                cacheFile.absolutePath,
                "tunnel",
                "--url", "http://127.0.0.1:$port"
            )
            pb.redirectErrorStream(false)
            pb.environment()["HOME"] = cacheDir.absolutePath
            
            cloudflaredProcess = pb.start()
            Log.d(TAG, "cloudflared process started")
            
            // Read stdout for URL
            Thread {
                try {
                    val reader = cloudflaredProcess!!.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "cloudflared: $line")
                        val match = Regex("https://[a-zA-Z0-9-]+\.trycloudflare\.com").find(line ?: "")
                        if (match != null) {
                            cloudflaredUrl = match.value
                            Log.d(TAG, "Tunnel URL: $cloudflaredUrl")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading cloudflared output", e)
                }
            }.start()
            
            // Update notification
            startForegroundWithNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel", e)
        }
    }

    fun stopTunnel() {
        cloudflaredProcess?.destroyForcibly()
        cloudflaredProcess = null
        cloudflaredUrl = null
        Log.d(TAG, "Tunnel stopped")
        startForegroundWithNotification()
    }

    fun stopTunnel() {
        cloudflareTunnel?.stop()
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

    private fun readStreamLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return sb.toString()
                sb.append(b.toChar())
                if (next >= 0) sb.append(next.toChar())
                continue
            }
            sb.append(b.toChar())
        }
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
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            if (statusCode != 204) {
                append("Content-Type: application/json\r\n")
            }
            append("Content-Length: ${bytes.size}\r\n")
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

    /**
     * Check if the accessibility service instance is actually running.
     * This is more reliable than isAccessibilityEnabled() which only checks settings.
     */
    private fun isAccessibilityServiceRunning(): Boolean {
        return com.agent.accessibility.service.AgentAccessibilityService.instance != null
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
        // Channels are created in onCreate() via createNotificationChannels().
        // Ensure they exist here too (idempotent) in case of race conditions.
        createNotificationChannels()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Relay MCP Server")
            .setContentText(buildString { append("Port $port"); cloudflaredUrl?.let { append(" | Tunnel: $it") } })
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
