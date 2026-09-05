package com.agent.accessibility.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.agent.accessibility.mcp.McpServerService
import com.agent.accessibility.model.SnapshotSource
import com.agent.accessibility.model.TreeSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(controller: AccessibilityController) {
    var isServiceEnabled by remember { mutableStateOf(controller.isServiceEnabled()) }
    var latestSnapshot by remember { mutableStateOf<TreeSnapshot?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var isMcpRunning by remember { mutableStateOf(McpServerService.isRunning) }
    var mcpPort by remember { mutableIntStateOf(McpServerService.port) }
    var authToken by remember { mutableStateOf(McpServerService.authToken) }
    var approvedClients by remember { mutableStateOf(McpServerService.instance?.getApprovedClientsList() ?: emptyList()) }
    var auditLogs by remember { mutableStateOf(McpServerService.instance?.getAuditLogs(10) ?: emptyList()) }
    var tunnelRunning by remember { mutableStateOf(McpServerService.instance?.cloudflareTunnel?.isRunning == true) }
    var tunnelUrl by remember { mutableStateOf(McpServerService.instance?.cloudflareTunnel?.publicUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Android Agent") },
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

            if (isServiceEnabled) {
                item {
                    CaptureControlsCard(
                        controller = controller,
                        countdown = countdown,
                        onCaptureNow = {
                            scope.launch {
                                latestSnapshot = controller.captureSnapshot()
                            }
                        },
                        onCountdownCapture = { seconds ->
                            countdown = seconds
                            scope.launch {
                                for (i in seconds downTo 1) {
                                    countdown = i
                                    delay(1000)
                                }
                                countdown = 0
                                latestSnapshot = controller.captureSnapshot(SnapshotSource.COUNTDOWN)
                            }
                        },
                        onClearSnapshots = {
                            controller.clearSnapshots()
                            latestSnapshot = null
                        },
                        snapshotCount = controller.getSnapshotCount()
                    )
                }
            }

            if (latestSnapshot != null) {
                item {
                    TreeDisplayCard(snapshot = latestSnapshot!!)
                }
            }

            if (isServiceEnabled) {
                item {
                    DebugControlsCard(controller = controller)
                }
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
fun CaptureControlsCard(
    controller: AccessibilityController,
    countdown: Int,
    onCaptureNow: () -> Unit,
    onCountdownCapture: (Int) -> Unit,
    onClearSnapshots: () -> Unit,
    snapshotCount: Int
) {
    var countdownSeconds by remember { mutableStateOf("3") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Capture Controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Snapshots stored: $snapshotCount",
                style = MaterialTheme.typography.bodyMedium
            )

            if (countdown > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "Capturing in $countdown seconds... Switch to another app NOW!",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                }
            }

            Button(
                onClick = onCaptureNow,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("CAPTURE NOW")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = countdownSeconds,
                    onValueChange = { countdownSeconds = it },
                    label = { Text("Seconds") },
                    modifier = Modifier.width(80.dp)
                )
                Button(
                    onClick = {
                        val seconds = countdownSeconds.toIntOrNull() ?: 3
                        onCountdownCapture(seconds)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("CAPTURE IN ${countdownSeconds}s")
                }
            }

            OutlinedButton(
                onClick = onClearSnapshots,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CLEAR SNAPSHOTS")
            }
        }
    }
}

@Composable
fun TreeDisplayCard(snapshot: TreeSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Captured Tree",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Package: ${snapshot.tree.foregroundPackage ?: "Unknown"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Nodes: ${snapshot.tree.totalNodeCount}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Source: ${snapshot.source}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Age: ${snapshot.ageSeconds()}s ago",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp)
                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = snapshot.tree.toPrettyString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
fun DebugControlsCard(controller: AccessibilityController) {
    var nodeIdText by remember { mutableStateOf("") }
    var tapX by remember { mutableStateOf("") }
    var tapY by remember { mutableStateOf("") }
    var swipeStartX by remember { mutableStateOf("") }
    var swipeStartY by remember { mutableStateOf("") }
    var swipeEndX by remember { mutableStateOf("") }
    var swipeEndY by remember { mutableStateOf("") }
    var swipeDuration by remember { mutableStateOf("500") }
    var inputNodeId by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Interaction Controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = nodeIdText,
                onValueChange = { nodeIdText = it },
                label = { Text("Node ID") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val nodeId = nodeIdText.toIntOrNull()
                    if (nodeId != null) {
                        lastResult = "Click result: ${controller.clickNode(nodeId)}"
                    } else {
                        lastResult = "Invalid node ID"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CLICK NODE")
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tapX,
                    onValueChange = { tapX = it },
                    label = { Text("X") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tapY,
                    onValueChange = { tapY = it },
                    label = { Text("Y") },
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    val x = tapX.toIntOrNull()
                    val y = tapY.toIntOrNull()
                    if (x != null && y != null) {
                        lastResult = "Tap result: ${controller.tap(x, y)}"
                    } else {
                        lastResult = "Invalid coordinates"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("TAP")
            }

            HorizontalDivider()

            Text("Swipe", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = swipeStartX,
                    onValueChange = { swipeStartX = it },
                    label = { Text("Start X") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = swipeStartY,
                    onValueChange = { swipeStartY = it },
                    label = { Text("Start Y") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = swipeEndX,
                    onValueChange = { swipeEndX = it },
                    label = { Text("End X") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = swipeEndY,
                    onValueChange = { swipeEndY = it },
                    label = { Text("End Y") },
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = swipeDuration,
                onValueChange = { swipeDuration = it },
                label = { Text("Duration (ms)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val startX = swipeStartX.toIntOrNull()
                    val startY = swipeStartY.toIntOrNull()
                    val endX = swipeEndX.toIntOrNull()
                    val endY = swipeEndY.toIntOrNull()
                    val duration = swipeDuration.toLongOrNull()
                    if (startX != null && startY != null && endX != null && endY != null && duration != null) {
                        lastResult = "Swipe result: ${controller.swipe(startX, startY, endX, endY, duration)}"
                    } else {
                        lastResult = "Invalid swipe parameters"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SWIPE")
            }

            HorizontalDivider()

            OutlinedTextField(
                value = inputNodeId,
                onValueChange = { inputNodeId = it },
                label = { Text("Node ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Text to input") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val nodeId = inputNodeId.toIntOrNull()
                    if (nodeId != null && inputText.isNotBlank()) {
                        lastResult = "Input result: ${controller.inputText(nodeId, inputText)}"
                    } else {
                        lastResult = "Invalid node ID or text"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SET TEXT")
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { lastResult = "Back result: ${controller.back()}" },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("BACK")
                }
                Button(
                    onClick = { lastResult = "Home result: ${controller.home()}" },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("HOME")
                }
            }

            if (lastResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        text = lastResult!!,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
