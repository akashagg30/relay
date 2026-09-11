// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.accessibility.controller.AccessibilityController
import com.agent.accessibility.mcp.AppInfo
import com.agent.accessibility.mcp.AppPolicy
import com.agent.accessibility.mcp.AppRegistry
import com.agent.accessibility.mcp.McpServerService
import com.agent.accessibility.mcp.MiuiPermissionHelper
import com.agent.accessibility.mcp.PolicyMode
import com.agent.accessibility.service.OverlayButtonService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(controller: AccessibilityController) {
    var isServiceEnabled by remember { mutableStateOf(controller.isServiceEnabled()) }
    var isMcpRunning by remember { mutableStateOf(McpServerService.isRunning) }
    var mcpPort by remember { mutableIntStateOf(McpServerService.port) }
    var authToken by remember { mutableStateOf(McpServerService.authToken) }
    var approvedClients by remember { mutableStateOf(McpServerService.instance?.getApprovedClientsList() ?: emptyList()) }
    var auditLogs by remember { mutableStateOf(McpServerService.instance?.getAuditLogs(10) ?: emptyList()) }
    var tunnelRunning by remember { mutableStateOf(McpServerService.instance?.cloudflareTunnel?.isRunning == true) }
    var tunnelUrl by remember { mutableStateOf(McpServerService.instance?.cloudflareTunnel?.publicUrl) }
    var overlayRunning by remember { mutableStateOf(OverlayButtonService.isRunning) }
    var isMiui by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        isMiui = MiuiPermissionHelper.isMiui(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            isServiceEnabled = controller.isServiceEnabled()
            isMcpRunning = McpServerService.isRunning
            mcpPort = McpServerService.port
            authToken = McpServerService.authToken
            approvedClients = McpServerService.instance?.getApprovedClientsList() ?: emptyList()
            auditLogs = McpServerService.instance?.getAuditLogs(10) ?: emptyList()
            tunnelRunning = McpServerService.instance?.cloudflareTunnel?.isRunning == true
            tunnelUrl = McpServerService.instance?.cloudflareTunnel?.publicUrl
            overlayRunning = OverlayButtonService.isRunning
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { isServiceEnabled = controller.isServiceEnabled() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Status")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ServiceStatusCard(
                    isEnabled = isServiceEnabled,
                    onOpenSettings = { controller.openAccessibilitySettings() }
                )
            }

            if (isMiui) {
                item {
                    MiuiPermissionCard(
                        onOpenSettings = {
                            MiuiPermissionHelper.openPermissionSettings(context)
                        }
                    )
                }
            }

            item {
                McpServerCard(
                    isRunning = isMcpRunning,
                    port = mcpPort,
                    authToken = authToken,
                    isAccessibilityEnabled = isServiceEnabled,
                    onStart = {
                        val intent = Intent(context, McpServerService::class.java).apply {
                            action = "START"
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    },
                    onStop = {
                        val intent = Intent(context, McpServerService::class.java).apply {
                            action = "STOP"
                        }
                        context.startService(intent)
                    },
                    onCopyToken = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("auth_token", authToken))
                    },
                    onCopyEndpoint = {
                        val ip = getDeviceIp(context)
                        val endpoint = "http://$ip:$mcpPort/mcp"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("mcp_endpoint", endpoint))
                    }
                )
            }

            item {
                CloudflareTunnelCard(
                    isTunnelRunning = tunnelRunning,
                    publicUrl = tunnelUrl,
                    isMcpRunning = isMcpRunning,
                    onStartTunnel = {
                        val intent = Intent(context, McpServerService::class.java).apply {
                            action = "START_TUNNEL"
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    },
                    onStopTunnel = {
                        val intent = Intent(context, McpServerService::class.java).apply {
                            action = "STOP_TUNNEL"
                        }
                        context.startService(intent)
                    },
                    onCopyUrl = { url ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tunnel_url", url))
                    }
                )
            }

            item {
                OverlayButtonCard(
                    isOverlayRunning = overlayRunning,
                    isMcpRunning = isMcpRunning,
                    onToggleOverlay = {
                        val intent = Intent(context, OverlayButtonService::class.java)
                        if (OverlayButtonService.isRunning) {
                            context.stopService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                )
            }

            if (isMcpRunning) {
                item {
                    SecurityCard(
                        authToken = authToken,
                        onRegenerateToken = {
                            McpServerService.instance?.regenerateAuthToken()
                        },
                        onCopyToken = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("auth_token", authToken))
                        },
                        approvedClients = approvedClients,
                        onRemoveClient = { clientIp ->
                            McpServerService.instance?.removeApprovedClient(clientIp)
                        },
                        auditLogs = auditLogs,
                        onClearLogs = {
                            McpServerService.instance?.clearAuditLogs()
                        }
                    )
                }
            }



            item {
                AppPolicyCard(context = context)
            }

            item {
                ShareLogsCard(context = context)
            }
        }
    }
}

@Composable
fun ShareLogsCard(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Logs & Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Share logs for debugging or send to developer for support.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Button(
                onClick = {
                    val scope = CoroutineScope(Dispatchers.IO)
                    scope.launch {
                        try {
                            val logFile = java.io.File(context.cacheDir, "relay_logs.txt")
                            val logDir = java.io.File(context.filesDir, "mcp_logs")
                            val sb = StringBuilder()
                            sb.appendLine("=== Relay MCP Logs ===")
                            sb.appendLine("Version: ${com.agent.accessibility.BuildConfig.APP_VERSION}")
                            sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                            sb.appendLine()
                            if (logDir.exists()) {
                                logDir.listFiles()?.sortedByDescending { it.name }?.take(3)?.forEach { file ->
                                    sb.appendLine("--- ${file.name} ---")
                                    sb.appendLine(file.readText().take(5000))
                                    sb.appendLine()
                                }
                            }
                            logFile.writeText(sb.toString())
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            withContext(Dispatchers.Main) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Relay MCP Logs")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Share Logs"))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ShareLogs", "Failed to share logs", e)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("SHARE LOGS")
            }
        }
    }
}

private fun getDeviceIp(context: Context): String {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ip = wifiManager.connectionInfo.ipAddress
    return String.format(
        "%d.%d.%d.%d",
        ip and 0xff,
        ip shr 8 and 0xff,
        ip shr 16 and 0xff,
        ip shr 24 and 0xff
    )
}

@Composable
fun ServiceStatusCard(isEnabled: Boolean, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color(0xFFF44336).copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Accessibility,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isEnabled) "ACCESSIBILITY SERVICE: ENABLED" else "ACCESSIBILITY SERVICE: DISABLED",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN ACCESSIBILITY SETTINGS")
            }
        }
    }
}

@Composable
fun McpServerCard(
    isRunning: Boolean,
    port: Int,
    authToken: String,
    isAccessibilityEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyToken: () -> Unit,
    onCopyEndpoint: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) Color(0xFF2196F3).copy(alpha = 0.1f) else Color(0xFF9E9E9E).copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "MCP Server",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Status: ${if (isRunning) "RUNNING" else "STOPPED"}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (isRunning) Color(0xFF4CAF50) else Color(0xFFF44336)
            )

            if (isRunning) {
                Text(
                    text = "Port: $port",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Endpoint: http://<phone-ip>:$port/mcp",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopyEndpoint,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("COPY ENDPOINT", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Auth Token:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = authToken,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2
                )
                Button(
                    onClick = onCopyToken,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("COPY TOKEN")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("STOP SERVER")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isAccessibilityEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("START SERVER")
                }
                if (!isAccessibilityEnabled) {
                    Text(
                        text = "Enable Accessibility Service first",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

@Composable
fun CloudflareTunnelCard(
    isTunnelRunning: Boolean,
    publicUrl: String?,
    isMcpRunning: Boolean,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onCopyUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isTunnelRunning) Color(0xFFFF9800).copy(alpha = 0.15f) else Color(0xFF9E9E9E).copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Cloudflare Tunnel",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Status: ${if (isTunnelRunning) "RUNNING" else "STOPPED"}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (isTunnelRunning) Color(0xFFFF9800) else Color(0xFFF44336)
            )

            Text(
                text = "Creates a public HTTPS URL for your MCP server. No Cloudflare account needed.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            if (isTunnelRunning && publicUrl != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Public URL:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = publicUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3
                )
                Button(
                    onClick = { onCopyUrl(publicUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("COPY URL")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isTunnelRunning) {
                Button(
                    onClick = onStopTunnel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("STOP TUNNEL")
                }
            } else {
                Button(
                    onClick = onStartTunnel,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isMcpRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("START TUNNEL")
                }
                if (!isMcpRunning) {
                    Text(
                        text = "Start MCP Server first",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

@Composable
fun OverlayButtonCard(
    isOverlayRunning: Boolean,
    isMcpRunning: Boolean,
    onToggleOverlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOverlayRunning) Color(0xFF9C27B0).copy(alpha = 0.15f) else Color(0xFF9E9E9E).copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Floating Overlay Button",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Status: ${if (isOverlayRunning) "ACTIVE" else "INACTIVE"}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (isOverlayRunning) Color(0xFF9C27B0) else Color(0xFFF44336)
            )

            Text(
                text = "Quick toggle for MCP server from any app. Draggable floating button.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onToggleOverlay,
                modifier = Modifier.fillMaxWidth(),
                enabled = isMcpRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOverlayRunning) Color(0xFFF44336) else Color(0xFF9C27B0)
                )
            ) {
                Text(if (isOverlayRunning) "REMOVE OVERLAY" else "SHOW OVERLAY")
            }

            if (!isMcpRunning) {
                Text(
                    text = "Start MCP Server first",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336)
                )
            }
        }
    }
}

@Composable
fun MiuiPermissionCard(
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF9800).copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "⚠️ MIUI Detected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )

            Text(
                text = "MIUI blocks apps from starting activities in background. Enable 'Start in background' permission for Relay to launch other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Text("OPEN MIUI PERMISSIONS")
            }
        }
    }
}


@Composable
fun SecurityCard(
    authToken: String,
    onRegenerateToken: () -> Unit,
    onCopyToken: () -> Unit,
    approvedClients: List<String>,
    onRemoveClient: (String) -> Unit,
    auditLogs: List<com.agent.accessibility.mcp.AuditLogger.AuditEntry>,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF9800).copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Security",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Auth Token Section
            Text(
                text = "Auth Token",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = authToken,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCopyToken,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("COPY TOKEN")
                }
                OutlinedButton(
                    onClick = onRegenerateToken,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("REGENERATE")
                }
            }

            HorizontalDivider()

            // Approved Clients Section
            Text(
                text = "Approved Clients",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (approvedClients.isEmpty()) {
                Text(
                    text = "No approved clients",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                approvedClients.forEach { client ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = client,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveClient(client) }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Remove",
                                tint = Color(0xFFF44336)
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Audit Logs Section
            Text(
                text = "Audit Logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (auditLogs.isEmpty()) {
                Text(
                    text = "No audit logs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                auditLogs.forEach { entry ->
                    val codeColor = when {
                        entry.responseCode == 200 -> Color(0xFF4CAF50)
                        entry.responseCode == 401 || entry.responseCode == 403 -> Color(0xFFF44336)
                        entry.responseCode == 429 -> Color(0xFFFF9800)
                        else -> Color.Unspecified
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = entry.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = entry.sourceIp,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${entry.responseCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = codeColor
                                )
                            }
                            Text(
                                text = "${entry.authStatus} | ${entry.method}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onClearLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CLEAR LOGS")
            }

            HorizontalDivider()

            // Security Status
            Text(
                text = "Security Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Rate Limiting", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Active (30 req/s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Consent Prompt", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Active",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
fun AppPolicyCard(context: Context) {
    val selfPkg = context.packageName
    var mode by remember { mutableStateOf(AppPolicy.mode(context)) }
    var protected by remember { mutableStateOf(AppPolicy.protectedPackages(context)) }
    var allowed by remember { mutableStateOf(AppPolicy.allowedPackages(context)) }
    var pickerFor by remember { mutableStateOf<String?>(null) }

    fun saveProtected(next: Set<String>) {
        protected = next
        AppPolicy.setProtectedPackages(context, next)
    }
    fun saveAllowed(next: Set<String>) {
        allowed = next
        AppPolicy.setAllowedPackages(context, next)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("App Access", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "Blocked apps are both unreadable and untouchable by the AI agent. " +
                    "Only you can change this list — the agent can read it but never edit it.",
                fontSize = 12.sp, color = Color(0xFFAAAAAA)
            )
            Spacer(Modifier.height(12.dp))

            // Relay itself: invariant, not removable
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Relay", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFF8A80))
                    Text(selfPkg, fontSize = 10.sp, color = Color(0xFF777777), fontFamily = FontFamily.Monospace)
                }
                Text("ALWAYS BLOCKED", fontSize = 10.sp, color = Color(0xFF777777))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Relay is locked so the agent cannot disable the service or rewrite this policy.",
                fontSize = 11.sp, color = Color(0xFF777777)
            )

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
            Spacer(Modifier.height(12.dp))

            // Mode
            Text("Mode", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = mode == PolicyMode.ALL,
                    onClick = { mode = PolicyMode.ALL; AppPolicy.setMode(context, PolicyMode.ALL) }
                )
                Text("All apps, except protected", fontSize = 13.sp, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = mode == PolicyMode.ALLOWLIST,
                    onClick = { mode = PolicyMode.ALLOWLIST; AppPolicy.setMode(context, PolicyMode.ALLOWLIST) }
                )
                Text("Only selected apps", fontSize = 13.sp, color = Color.White)
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
            Spacer(Modifier.height(12.dp))

            Text("Protected apps (${protected.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(Modifier.height(4.dp))
            if (protected.isEmpty()) {
                Text("None", fontSize = 12.sp, color = Color(0xFF777777))
            }
            protected.sorted().forEach { pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pkg, fontSize = 11.sp, color = Color(0xFFCCCCCC),
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { saveProtected(protected - pkg) }) {
                        Text("Remove", fontSize = 11.sp, color = Color(0xFFEF9A9A))
                    }
                }
            }
            Row {
                TextButton(onClick = { pickerFor = "protected" }) { Text("Add apps", fontSize = 12.sp) }
                TextButton(onClick = { saveProtected(AppPolicy.sensitiveDefaults()) }) {
                    Text("Restore defaults", fontSize = 12.sp)
                }
            }

            if (mode == PolicyMode.ALLOWLIST) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
                Spacer(Modifier.height(12.dp))
                Text("Allowed apps (${allowed.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                if (allowed.isEmpty()) {
                    Text(
                        "None — the agent cannot act on any app in this mode.",
                        fontSize = 12.sp, color = Color(0xFFFFAB91)
                    )
                }
                allowed.sorted().forEach { pkg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pkg, fontSize = 11.sp, color = Color(0xFFCCCCCC),
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { saveAllowed(allowed - pkg) }) {
                            Text("Remove", fontSize = 11.sp, color = Color(0xFFEF9A9A))
                        }
                    }
                }
                TextButton(onClick = { pickerFor = "allowed" }) { Text("Add apps", fontSize = 12.sp) }
            }
        }
    }

    pickerFor?.let { target ->
        AppPickerDialog(
            context = context,
            title = if (target == "protected") "Protect apps" else "Allow apps",
            selected = if (target == "protected") protected else allowed,
            onToggle = { pkg, on ->
                val base = if (target == "protected") protected else allowed
                val next = if (on) base + pkg else base - pkg
                if (target == "protected") saveProtected(next) else saveAllowed(next)
            },
            onDismiss = { pickerFor = null }
        )
    }
}

@Composable
private fun AppPickerDialog(
    context: Context,
    title: String,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val apps = remember { AppRegistry(context).getLaunchableApps().sortedBy { it.name.lowercase() } }
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter {
        query.isBlank() ||
            it.name.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title (${selected.size} selected)") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered) { app: AppInfo ->
                        val checked = app.packageName in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName, !checked) }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(app.packageName, it) })
                            Column(Modifier.weight(1f)) {
                                Text(app.name, fontSize = 14.sp, color = Color.White)
                                Text(
                                    app.packageName, fontSize = 10.sp, color = Color(0xFF888888),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
