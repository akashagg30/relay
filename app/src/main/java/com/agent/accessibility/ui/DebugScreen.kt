package com.agent.accessibility.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.accessibility.controller.AccessibilityController
import com.agent.accessibility.model.AccessibilityTreeData
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(controller: AccessibilityController) {
    var isServiceEnabled by remember { mutableStateOf(controller.isServiceEnabled()) }
    var treeData by remember { mutableStateOf<AccessibilityTreeData?>(null) }
    var showDebugControls by remember { mutableStateOf(false) }

    // Refresh state periodically
    LaunchedEffect(Unit) {
        while (true) {
            isServiceEnabled = controller.isServiceEnabled()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Android Agent Debug") },
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
            // Service Status
            item {
                ServiceStatusCard(
                    isEnabled = isServiceEnabled,
                    onOpenSettings = { controller.openAccessibilitySettings() }
                )
            }

            // Refresh Tree Button
            if (isServiceEnabled) {
                item {
                    Button(
                        onClick = {
                            treeData = controller.getService()?.readCurrentTree()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("REFRESH TREE")
                    }
                }
            }

            // Tree Display
            if (treeData != null) {
                item {
                    TreeDisplayCard(treeData = treeData!!)
                }
            }

            // Debug Controls
            if (isServiceEnabled) {
                item {
                    DebugControlsCard(controller = controller)
                }
            }
        }
    }
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
fun TreeDisplayCard(treeData: AccessibilityTreeData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Accessibility Tree",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Foreground: ${treeData.foregroundPackage ?: "Unknown"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Total Nodes: ${treeData.totalNodeCount}",
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
                    text = treeData.toPrettyString(),
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
                text = "Debug Controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Click Node
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

            // Tap
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

            // Swipe
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

            // Input Text
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

            // Back / Home
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

            // Result Display
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
