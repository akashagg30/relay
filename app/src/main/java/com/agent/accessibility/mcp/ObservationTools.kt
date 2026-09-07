package com.agent.accessibility.mcp

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Observation tools: reading and projecting the current UI state.
 * Implements get_screen_state, observe, find, scroll_until and diag_sealed.
 */

internal fun McpHandler.getScreenState(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val rootNode = findForegroundRoot(service)
        ?: return toolErrorResponse(id, "No active window")

    try {
        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)

        // Store descriptors in snapshot manager
        val descriptors = NodeResolver.buildDescriptors(tree)
        val snapshotId = NodeResolver.snapshotManager.store(descriptors, tree.foregroundPackage)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val nodesArray = JSONArray()
        tree.root?.let { flattenForJson(it, null, nodesArray, snapshotId) }

        val result = JSONObject().apply {
            put("snapshotId", snapshotId)
            put("packageName", tree.foregroundPackage ?: "unknown")
            put("timestamp", tree.timestamp)
            put("screenWidth", metrics.widthPixels)
            put("screenHeight", metrics.heightPixels)
            put("nodes", nodesArray)
        }

        return toolSuccessResponse(id, result.toString())
    } finally {
        @Suppress("DEPRECATION")
        rootNode.recycle()
    }
}

internal fun McpHandler.findForegroundRoot(service: AgentAccessibilityService): AccessibilityNodeInfo? {
    val activeRoot = service.rootInActiveWindow
    val activePackageName = activeRoot?.packageName?.toString()

    Log.d(TAG, "findForegroundRoot: activeRoot pkg=$activePackageName, windows=${service.windows.size}")

    // Check all windows for popups/dialogs from the SAME app
    var popupRoot: AccessibilityNodeInfo? = null
    var popupNodeCount = 0

    for (window in service.windows) {
        val windowRoot = window.root
        if (windowRoot != null &&
            windowRoot.packageName?.toString() != context.packageName
        ) {
            val windowPkg = windowRoot.packageName?.toString()
            // Consider popups from same app OR system dialogs (android package)
            if (activePackageName != null && windowPkg != activePackageName && windowPkg != "android") {
                Log.d(TAG, "  Skipping window pkg=$windowPkg (not same as active $activePackageName)")
                continue
            }
            // Count nodes to identify popups (smaller window = likely popup)
            val nodeCount = countNodes(windowRoot)
            Log.d(TAG, "  Window pkg=$windowPkg nodes=$nodeCount same=${windowRoot === activeRoot}")
            if (popupRoot == null || nodeCount < popupNodeCount) {
                // Skip if this window is the same as active window
                if (windowRoot !== activeRoot) {
                    popupRoot = windowRoot
                    popupNodeCount = nodeCount
                }
            }
        }
    }

    // Return popup if found (smaller window = likely dropdown/dialog)
    if (popupRoot != null) {
        Log.d(TAG, "findForegroundRoot: returning popup pkg=${popupRoot.packageName}")
        return popupRoot
    }

    if (activeRoot != null && activeRoot.packageName?.toString() != context.packageName) {
        Log.d(TAG, "findForegroundRoot: returning activeRoot pkg=${activeRoot.packageName}")
        return activeRoot
    }

    for (window in service.windows) {
        val windowRoot = window.root
        if (windowRoot != null && windowRoot.packageName?.toString() != context.packageName) {
            Log.d(TAG, "findForegroundRoot: returning window pkg=${windowRoot.packageName}")
            return windowRoot
        }
    }

    Log.d(TAG, "findForegroundRoot: returning activeRoot (fallback)")
    return activeRoot
}

/**
 * Count nodes in a tree (for popup detection).
 */
private fun countNodes(root: AccessibilityNodeInfo): Int {
    var count = 1
    for (i in 0 until root.childCount) {
        val child = root.getChild(i) ?: continue
        count += countNodes(child)
    }
    return count
}

/**
 * Find all accessible windows including popups (dropdowns, dialogs).
 * Returns list of roots from all non-Relay windows.
 */
internal fun McpHandler.findAllWindowRoots(service: AgentAccessibilityService): List<AccessibilityNodeInfo> {
    val roots = mutableListOf<AccessibilityNodeInfo>()

    // Add active window first
    val activeRoot = service.rootInActiveWindow
    if (activeRoot != null && activeRoot.packageName?.toString() != context.packageName) {
        roots.add(activeRoot)
    }

    // Add all other windows (popups, dialogs, etc.)
    for (window in service.windows) {
        val windowRoot = window.root
        if (windowRoot != null &&
            windowRoot.packageName?.toString() != context.packageName &&
            !roots.any { it === windowRoot }
        ) {
            roots.add(windowRoot)
        }
    }

    return roots
}

private fun McpHandler.flattenForJson(
    node: com.agent.accessibility.model.AccessibilityNodeData,
    parentId: String?,
    array: JSONArray,
    snapshotId: Long
) {
    // Skip invisible non-actionable containers
    if (node.text == null && node.contentDescription == null && !node.clickable &&
        !node.scrollable && !node.editable && node.viewIdResourceName == null
    ) {
        // But if this container has semantic enrichment (clickable with title/summary), emit it
        val isClickableEmptyContainer = node.clickable &&
            node.text == null && node.contentDescription == null && node.viewIdResourceName == null

        if (!isClickableEmptyContainer) {
            for (child in node.children) {
                flattenForJson(child, parentId, array, snapshotId)
            }
            return
        }
    }

    val elementId = ElementId(snapshotId, node.nodeId).encode()
    val nodeObj = JSONObject().apply {
        put("id", elementId)
        if (parentId != null) put("parentId", parentId)
        put("className", node.className ?: "")

        // Use direct text if available, otherwise use derived semantics
        val displayText = node.text
        val displayDesc = node.contentDescription

        if (!displayText.isNullOrBlank()) {
            put("text", displayText)
        } else if (node.clickable && node.viewIdResourceName == null) {
            // For enriched clickable containers, try to get semantic text
            val descriptor = NodeResolver.snapshotManager.getDescriptor(snapshotId, node.nodeId)
            if (descriptor?.semanticText != null) {
                put("text", descriptor.semanticText)
            }
        }

        if (!displayDesc.isNullOrBlank()) {
            put("contentDescription", displayDesc)
        } else if (node.clickable && node.viewIdResourceName == null) {
            val descriptor = NodeResolver.snapshotManager.getDescriptor(snapshotId, node.nodeId)
            if (descriptor?.semanticDescription != null) {
                put("contentDescription", descriptor.semanticDescription)
            }
        }

        if (node.viewIdResourceName != null) put("viewId", node.viewIdResourceName)
        put("bounds", JSONArray().apply {
            put(node.boundsInScreen.left)
            put(node.boundsInScreen.top)
            put(node.boundsInScreen.right)
            put(node.boundsInScreen.bottom)
        })
        put("clickable", node.clickable)
        put("longClickable", node.longClickable)
        put("scrollable", node.scrollable)
        put("editable", node.editable)
        put("enabled", node.enabled)
        put("focused", node.focused)
        put("selected", node.selected)
        put("checked", node.checked)
        put("visibleToUser", node.visibleToUser)
    }
    array.put(nodeObj)

    for (child in node.children) {
        flattenForJson(child, elementId, array, snapshotId)
    }
}

internal fun McpHandler.observe(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val rootNode = findForegroundRoot(service)
        ?: return toolErrorResponse(id, "No active window")

    try {
        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)

        val descriptors = NodeResolver.buildDescriptors(tree)
        val snapshotId = NodeResolver.snapshotManager.store(descriptors, tree.foregroundPackage)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scene = SemanticProjector.project(tree, snapshotId, metrics.heightPixels, metrics.widthPixels)

        val now = System.currentTimeMillis()
        val timeSinceLastObserve = now - lastObserveTimestamp
        val elementCount = scene.totalDescendantCount()
        val elementCountDelta = if (lastElementCount > 0) {
            kotlin.math.abs(elementCount - lastElementCount).toDouble() / lastElementCount
        } else 0.0

        val keyboardVisible = detectKeyboard(service)
        val dialogVisible = detectDialog(tree)
        val transitioning = timeSinceLastObserve < 200 || elementCountDelta > 0.3
        val stable = !keyboardVisible && !dialogVisible && !transitioning

        lastObserveTimestamp = now
        lastElementCount = elementCount
        lastScene = scene

        val json = JSONObject(scene.toJson())
        json.put("screenState", JSONObject().apply {
            put("state", if (stable) "stable" else if (transitioning) "transitioning" else "unknown")
            put("keyboardVisible", keyboardVisible)
            put("dialogVisible", dialogVisible)
        })
        return toolSuccessResponse(id, json.toString())
    } finally {
        @Suppress("DEPRECATION")
        rootNode.recycle()
    }
}

private fun McpHandler.detectKeyboard(service: AgentAccessibilityService): Boolean {
    for (window in service.windows) {
        if (window.type == android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
            return true
        }
    }
    return false
}

private fun McpHandler.detectDialog(tree: com.agent.accessibility.model.AccessibilityTreeData): Boolean {
    val root = tree.root ?: return false
    val className = root.className ?: return false
    return className.contains("Dialog", ignoreCase = true) ||
           className.contains("AlertDialog", ignoreCase = true) ||
           className.contains("PopupWindow", ignoreCase = true)
}

internal fun McpHandler.observeInternal(service: AgentAccessibilityService): SemanticScene {
    val rootNode = findForegroundRoot(service)
        ?: return SemanticScene(null, null, false, emptyList())
    val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)
    val descriptors = NodeResolver.buildDescriptors(tree)
    val snapshotId = NodeResolver.snapshotManager.store(descriptors, tree.foregroundPackage)
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    return SemanticProjector.project(tree, snapshotId, metrics.heightPixels, metrics.widthPixels)
}

private fun searchElement(
    elements: List<SemanticElement>,
    text: String,
    role: String,
    results: MutableList<SemanticElement>
) {
    for (el in elements) {
        val textMatch = text.isEmpty() ||
            (el.title?.contains(text, ignoreCase = true) == true) ||
            (el.summary?.contains(text, ignoreCase = true) == true)
        val roleMatch = role.isEmpty() || el.role.value == role

        if (textMatch && roleMatch) {
            results.add(el)
        }

        if (el.children.isNotEmpty()) {
            searchElement(el.children, text, role, results)
        }
    }
}

internal fun McpHandler.find(id: String, args: JSONObject): String {
    val searchText = args.optString("text", "")
    val searchRole = args.optString("role", "")

    val scene = lastScene
        ?: return toolErrorResponse(id, "No screen data. Call observe() first.")

    val results = mutableListOf<SemanticElement>()
    searchElement(scene.elements, searchText, searchRole, results)

    if (results.isEmpty()) {
        return toolSuccessResponse(id, JSONObject().apply {
            put("found", false)
        }.toString())
    }

    val best = results[0]
    return toolSuccessResponse(id, JSONObject().apply {
        put("found", true)
        put("element", best.toJson())
    }.toString())
}

internal fun McpHandler.scrollUntil(id: String, args: JSONObject): String {
    val searchText = args.optString("text", "")
    val direction = args.optString("direction", "down")
    val maxScrolls = args.optInt("maxScrolls", 6)

    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    var scene = observeInternal(service)
    lastScene = scene

    var results = mutableListOf<SemanticElement>()
    searchElement(scene.elements, searchText, "", results)
    if (results.isNotEmpty()) {
        return toolSuccessResponse(id, JSONObject().apply {
            put("found", true)
            put("element", results[0].toJson())
            put("scrollsPerformed", 0)
        }.toString())
    }

    val screenH = getScreenHeight()
    val screenW = getScreenWidth()
    val centerX = screenW / 2

    for (scroll in 1..maxScrolls) {
        val startY = if (direction == "down") screenH * 2 / 3 else screenH / 3
        val endY = if (direction == "down") screenH / 3 else screenH * 2 / 3
        dispatchScrollGesture(centerX, startY, centerX, endY, 300)
        Thread.sleep(400)

        scene = observeInternal(service)
        lastScene = scene

        results = mutableListOf()
        searchElement(scene.elements, searchText, "", results)
        if (results.isNotEmpty()) {
            return toolSuccessResponse(id, JSONObject().apply {
                put("found", true)
                put("element", results[0].toJson())
                put("scrollsPerformed", scroll)
            }.toString())
        }
    }

    return toolSuccessResponse(id, JSONObject().apply {
        put("found", false)
        put("scrollsPerformed", maxScrolls)
    }.toString())
}

private fun isSealed(node: AccessibilityNodeInfo): Boolean {
    return try {
        val method = AccessibilityNodeInfo::class.java.getDeclaredMethod("isSealed")
        method.isAccessible = true
        method.invoke(node) as Boolean
    } catch (e: Exception) {
        false
    }
}

internal fun McpHandler.diagSealed(id: String, args: JSONObject): String {
    val elementIdRaw = args.optString("elementId", "")
    val elementId = ElementId.decode(elementIdRaw)
        ?: return toolErrorResponse(id, "invalid_element_id: \"$elementIdRaw\"")

    val log = mutableListOf<String>()
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    // STEP 1: Walk tree #1, store descriptors, recycle EVERYTHING
    log.add("=== STEP 1: Walk tree #1 ===")
    val root1 = findForegroundRoot(service)
        ?: return toolErrorResponse(id, "No active window")
    log.add("root1: identity=${System.identityHashCode(root1)}, sealed=${isSealed(root1)}, pkg=${root1.packageName}")

    val tree1 = com.agent.accessibility.service.AccessibilityTreeReader.readTree(root = root1)
    log.add("Tree1: ${tree1.totalNodeCount} nodes, pkg=${tree1.foregroundPackage}")

    val descriptors = NodeResolver.buildDescriptors(tree1)
    val snapshotId = NodeResolver.snapshotManager.store(descriptors, tree1.foregroundPackage)
    log.add("Stored descriptors: snapshotId=$snapshotId, count=${descriptors.size}")

    // Recycle root1
    @Suppress("DEPRECATION")
    root1.recycle()
    log.add("Recycled root1")

    // STEP 2: Get FRESH root
    log.add("\n=== STEP 2: Fresh root ===")
    val root2 = findForegroundRoot(service)
        ?: return toolErrorResponse(id, "No active window after recycle")
    log.add("root2: identity=${System.identityHashCode(root2)}, sealed=${isSealed(root2)}")

    // STEP 3: Walk tree #2, find target, check sealed state
    log.add("\n=== STEP 3: Walk tree #2 ===")
    val descriptor = NodeResolver.snapshotManager.getDescriptor(elementId.snapshotId, elementId.nodeId)
        ?: return toolErrorResponse(id, "Descriptor not found")

    // Manual traversal to track sealed state
    log.add("\n=== Manual traversal ===")
    var foundNode: AccessibilityNodeInfo? = null
    fun traverse(node: AccessibilityNodeInfo, depth: Int) {
        if (foundNode != null) return
        val nodeSealed = isSealed(node)
        val nodeHash = System.identityHashCode(node)

        // Check if this is our target
        val desc = NodeDescriptor(
            id = -1, parentId = null, packageName = node.packageName?.toString(),
            viewIdResourceName = node.viewIdResourceName, text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            bounds = listOf(0,0,0,0), clickable = node.isClickable, scrollable = node.isScrollable
        )
        val score = descriptor.score(node)

        if (score > 0) {
            log.add("  depth=$depth MATCH: hash=$nodeHash, sealed=$nodeSealed, score=$score, class=${node.className}, text=${node.text}")
            if (foundNode == null) {
                foundNode = node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, depth + 1)
            child.recycle()
        }
    }
    traverse(root2, 0)

    val targetNode = foundNode
        ?: return toolErrorResponse(id, "Could not find target in manual traversal")

    // STEP 4: Check sealed state before performAction
    log.add("\n=== STEP 4: Pre-click sealed check ===")
    log.add("target.identity=${System.identityHashCode(targetNode)}")
    log.add("target.sealed=${isSealed(targetNode)}")
    log.add("target.className=${targetNode.className}")
    log.add("target.text=${targetNode.text}")
    log.add("target.clickable=${targetNode.isClickable}")
    log.add("target.windowId=${targetNode.windowId}")
    log.add("target.viewId=${targetNode.viewIdResourceName}")

    // Check parent (skip - getParent() also calls enforceSealed())
    log.add("target.parent: skipping (getParent() also calls enforceSealed())")

    // STEP 5: Try performAction
    log.add("\n=== STEP 5: performAction() ===")
    val clickResult: Boolean
    try {
        clickResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        log.add("performAction(ACTION_CLICK) = $clickResult")
    } catch (e: Exception) {
        log.add("performAction(ACTION_CLICK) THREW: ${e::class.simpleName}: ${e.message}")
    }

    // STEP 6: Check sealed state AFTER performAction
    log.add("\n=== STEP 6: Post-click sealed check ===")
    try {
        log.add("target.sealed after click=${isSealed(targetNode)}")
    } catch (e: Exception) {
        log.add("target.sealed check THREW: ${e::class.simpleName}")
    }

    // STEP 7: Try other actions
    log.add("\n=== STEP 7: Other actions ===")
    val actions = mapOf(
        "ACTION_LONG_CLICK" to AccessibilityNodeInfo.ACTION_LONG_CLICK,
        "ACTION_FOCUS" to AccessibilityNodeInfo.ACTION_FOCUS,
        "ACTION_SELECT" to AccessibilityNodeInfo.ACTION_SELECT,
    )
    for ((name, action) in actions) {
        try {
            val r = targetNode.performAction(action)
            log.add("$name = $r")
        } catch (e: Exception) {
            log.add("$name THREW: ${e::class.simpleName}: ${e.message}")
        }
    }

    // STEP 8: Get ANOTHER fresh root and try performAction on a node from THAT root
    log.add("\n=== STEP 8: Fresh root, fresh resolve, immediate performAction ===")
    val root3 = findForegroundRoot(service)
        ?: return toolErrorResponse(id, "No active window")
    log.add("root3: identity=${System.identityHashCode(root3)}, sealed=${isSealed(root3)}")

    val result3 = NodeResolver.resolveNode(root3, elementId.snapshotId, elementId.nodeId)
    if (result3 is ResolutionResult.Resolved) {
        log.add("resolved3: identity=${System.identityHashCode(result3.node)}, sealed=${isSealed(result3.node)}")
        try {
            val r = result3.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            log.add("performAction on fresh node = $r")
        } catch (e: Exception) {
            log.add("performAction on fresh node THREW: ${e::class.simpleName}: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    root3.recycle()

    // STEP 9: Test identityHashCode tracking
    log.add("\n=== STEP 9: identityHashCode tracking ===")
    val root4 = findForegroundRoot(service)
    if (root4 != null) {
        // Get a child
        if (root4.childCount > 0) {
            val child1 = root4.getChild(0)
            if (child1 != null) {
                val child1Hash = System.identityHashCode(child1)
                log.add("child1.hash=$child1Hash, sealed=${isSealed(child1)}")
                @Suppress("DEPRECATION")
                child1.recycle()
                log.add("child1 after recycle: sealed=${isSealed(child1)}")

                // Get the same child again
                val child1b = root4.getChild(0)
                if (child1b != null) {
                    val child1bHash = System.identityHashCode(child1b)
                    log.add("child1b.hash=$child1bHash, same object=${child1Hash == child1bHash}")
                    @Suppress("DEPRECATION")
                    child1b.recycle()
                }
            }
        }
        @Suppress("DEPRECATION")
        root4.recycle()
    }

    return toolSuccessResponse(id, log.joinToString("\n"))
}
