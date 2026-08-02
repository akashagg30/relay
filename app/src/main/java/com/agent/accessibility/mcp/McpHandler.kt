package com.agent.accessibility.mcp

import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.SnapshotSource
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class McpHandler(private val context: Context) {

    private val appRegistry = AppRegistry(context)

    companion object {
        private const val TAG = "McpHandler"
        private const val MAX_WAIT_MS = 10_000L
        private const val SHOW_ON_SCREEN_DELAY_MS = 200L
        private const val SCROLL_DELAY_MS = 200L
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val ACTION_SHOW_ON_SCREEN = 0x00100000
        private const val CLICK_DEADLINE_MS = 4000L
    }

    fun init() {
        appRegistry.register()
    }

    fun destroy() {
        appRegistry.unregister()
    }

    fun handleRequest(body: String): String {
        return try {
            val request = JSONObject(body)
            val method = request.optString("method", "")
            val hasId = request.has("id")
            val id = request.optString("id", "")
            val params = request.optJSONObject("params") ?: JSONObject()

            // Notifications (no id) should not get a response
            if (!hasId) {
                Log.d(TAG, "Notification: $method")
                return ""
            }

            when (method) {
                "initialize" -> handleInitialize(id, params)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, params)
                else -> errorResponse(id, "Method not found: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request parse error", e)
            errorResponse("", "Parse error: ${e.message}")
        }
    }

    private fun handleInitialize(id: String, params: JSONObject = JSONObject()): String {
        val clientVersion = params.optString("protocolVersion", "2024-11-05")

        val result = JSONObject().apply {
            put("protocolVersion", clientVersion)
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
            })
            put("serverInfo", JSONObject().apply {
                put("name", "android-agent")
                put("version", "2.1.0")
            })
        }
        return successResponse(id, result)
    }

    private fun handleToolsList(id: String): String {
        val tools = JSONArray()

        tools.put(toolDef("get_screen_state",
            "Get current foreground app accessibility tree as JSON. " +
            "Each node has an opaque `id` string. " +
            "Pass that exact id to click_node or input_text. " +
            "After screen transitions, call get_screen_state again to get fresh element ids.",
            JSONObject()))

        tools.put(toolDef("click_node",
            "Clicks an element returned by get_screen_state. " +
            "Pass the element's `id` exactly as returned. " +
            "Do not construct or modify the id. " +
            "The server handles re-resolution and scrolling when possible. " +
            "After navigation, call get_screen_state again before clicking.",
            JSONObject().apply {
                put("elementId", stringParam("Element id from get_screen_state (e.g. \"24:86\")"))
            }))

        tools.put(toolDef("tap", "Tap at screen coordinates", JSONObject().apply {
            put("x", intParam("X coordinate"))
            put("y", intParam("Y coordinate"))
        }))
        tools.put(toolDef("swipe", "Swipe between two points", JSONObject().apply {
            put("startX", intParam("Start X"))
            put("startY", intParam("Start Y"))
            put("endX", intParam("End X"))
            put("endY", intParam("End Y"))
            put("durationMs", intParam("Duration in ms"))
        }))
        tools.put(toolDef("input_text",
            "Sets text on an editable element returned by get_screen_state. " +
            "Pass the element's `id` exactly as returned.",
            JSONObject().apply {
                put("elementId", stringParam("Element id from get_screen_state (e.g. \"24:86\")"))
                put("text", stringParam("Text to input"))
            }))
        tools.put(toolDef("back", "Perform back action", JSONObject()))
        tools.put(toolDef("home", "Go to home screen", JSONObject()))
        tools.put(toolDef("launch_app", "Launch an app by package name", JSONObject().apply {
            put("packageName", stringParam("Android package name"))
        }))
        tools.put(toolDef("wait", "Wait for specified milliseconds", JSONObject().apply {
            put("milliseconds", intParam("Wait time in ms (max 10000)"))
        }))
        tools.put(toolDef("list_apps",
            "Returns launchable applications installed on the device.",
            JSONObject()))
        tools.put(toolDef("search_apps",
            "Search installed applications by name.",
            JSONObject().apply {
                put("query", stringParam("Search query (app name)"))
            }))
        tools.put(toolDef("open_app",
            "Open an installed application by its human-readable name. " +
            "If multiple apps match equally well, returns ambiguous_app instead of guessing.",
            JSONObject().apply {
                put("name", stringParam("Application name (e.g. Chrome, WhatsApp, Maps)"))
            }))
        tools.put(toolDef("current_app",
            "Returns the currently foreground application.",
            JSONObject()))
        tools.put(toolDef("observe",
            "Returns a semantic representation of the current screen. " +
            "Elements have roles (action, input, toggle, value, section, header, list, tab). " +
            "Each element has an id that works with click_node and input_text. " +
            "Use this instead of get_screen_state for cleaner reasoning.",
            JSONObject()))

        tools.put(toolDef("diag_sealed",
            "DIAGNOSTIC: Tests node sealed lifecycle. Walk tree, recycle all, re-walk, try performAction.",
            JSONObject().apply {
                put("elementId", stringParam("Element id from get_screen_state (e.g. \"24:86\")"))
            }))

        val result = JSONObject().apply { put("tools", tools) }
        return successResponse(id, result)
    }

    private fun handleToolsCall(id: String, params: JSONObject): String {
        val toolName = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        Log.d(TAG, "Tool call: $toolName")

        return when (toolName) {
            "get_screen_state" -> getScreenState(id)
            "click_node" -> clickNode(id, args)
            "tap" -> tap(id, args)
            "swipe" -> swipe(id, args)
            "input_text" -> inputText(id, args)
            "back" -> back(id)
            "home" -> home(id)
            "launch_app" -> launchApp(id, args)
            "wait" -> wait(id, args)
            "list_apps" -> listApps(id)
            "search_apps" -> searchApps(id, args)
            "open_app" -> openApp(id, args)
            "current_app" -> currentApp(id)
            "observe" -> observe(id)
            "diag_sealed" -> diagSealed(id, args)
            else -> errorResponse(id, "Unknown tool: $toolName")
        }
    }

    private fun getScreenState(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val rootNode = findForegroundRoot(service)
            ?: return toolErrorResponse(id, "No active window")

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
    }

    private fun findForegroundRoot(service: AgentAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow

        if (root != null && root.packageName?.toString() != context.packageName) {
            return root
        }

        for (window in service.windows) {
            val windowRoot = window.root
            if (windowRoot != null && windowRoot.packageName?.toString() != context.packageName) {
                return windowRoot
            }
        }

        return root
    }

    private fun flattenForJson(
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

    private fun clickNode(id: String, args: JSONObject): String {
        val startTime = System.currentTimeMillis()
        val deadline = startTime + CLICK_DEADLINE_MS

        val elementIdRaw = args.optString("elementId", "")
        val elementId = ElementId.decode(elementIdRaw)
            ?: return toolErrorResponse(id, "invalid_element_id: \"$elementIdRaw\" is not a valid element id")

        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val t0 = System.currentTimeMillis()
        val descriptor = NodeResolver.snapshotManager.getDescriptor(elementId.snapshotId, elementId.nodeId)
            ?: return toolErrorResponse(id, "element_expired: the element's snapshot is no longer cached. " +
                "Call get_screen_state to get fresh elements.")

        val t1 = System.currentTimeMillis()
        Log.d(TAG, "[click_node] resolve: ${t1 - t0}ms")

        val traversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
            ?: return toolErrorResponse(id, "element_not_found: element $elementIdRaw could not be resolved " +
                "against the current screen. Call get_screen_state to refresh.")

        val t2 = System.currentTimeMillis()
        Log.d(TAG, "[click_node] resolveFresh: ${t2 - t1}ms, visible=${traversal.isVisible}, " +
            "method=${traversal.method}, bounds=[${traversal.bounds.left},${traversal.bounds.top}," +
            "${traversal.bounds.right},${traversal.bounds.bottom}]")

        try {
            if (traversal.isVisible) {
                val actionTarget = traversal.clickableAncestor ?: traversal.matchedNode
                val clickResult = actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val total = System.currentTimeMillis() - startTime
                Log.d(TAG, "[click_node] total=${total}ms (visible click), result=$clickResult")

                if (clickResult) {
                    return toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("method", "accessibility_click")
                        put("resolutionMethod", traversal.method)
                    }.toString())
                }

                val bounds = traversal.bounds
                if (NodeResolver.isSaneBounds(bounds)) {
                    val screenW = getScreenWidth()
                    val screenH = getScreenHeight()
                    if (bounds.centerX() in 0..screenW && bounds.centerY() in 0..screenH) {
                        val tapResult = dispatchTap(bounds.centerX(), bounds.centerY())
                        if (tapResult) {
                            Log.d(TAG, "Coordinate fallback succeeded")
                            return toolSuccessResponse(id, JSONObject().apply {
                                put("success", true)
                                put("method", "coordinate_fallback")
                            }.toString())
                        }
                    }
                }
                return toolErrorResponse(id, "action_failed: node resolved but click/ancestor actions " +
                    "failed. resolutionMethod=${traversal.method}")
            }

            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "[click_node] deadline reached before scroll")
                return toolErrorResponse(id, "target_offscreen_unreachable: action_deadline_exceeded before scroll")
            }

            val scrollResult = bringIntoViewFresh(id, elementId, traversal, deadline)
            if (scrollResult != null) {
                return toolErrorResponse(id, scrollResult)
            }

            if (System.currentTimeMillis() >= deadline) {
                return toolErrorResponse(id, "target_offscreen_unreachable: action_deadline_exceeded after scroll")
            }

            val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
                ?: return toolErrorResponse(id, "target_offscreen_unreachable: could not re-resolve after scroll")

            try {
                val actionTarget = reTraversal.clickableAncestor ?: reTraversal.matchedNode
                val clickResult = actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickResult) {
                    return toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("method", "accessibility_click_scrolled")
                    }.toString())
                }

                val bounds = reTraversal.bounds
                if (NodeResolver.isSaneBounds(bounds)) {
                    val tapResult = dispatchTap(bounds.centerX(), bounds.centerY())
                    if (tapResult) {
                        return toolSuccessResponse(id, JSONObject().apply {
                            put("success", true)
                            put("method", "coordinate_fallback_scrolled")
                        }.toString())
                    }
                }
                return toolErrorResponse(id, "target_offscreen_unreachable: could not bring target into view")
            } finally {
                NodeResolver.recycleAll(reTraversal)
            }
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    private fun performClick(
        id: String,
        target: AccessibilityNodeInfo,
        method: String
    ): String {
        // 1. Direct click on the actionable target
        if (target.isClickable) {
            val clickResult = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clickResult) {
                Log.d(TAG, "Direct click succeeded ($method)")
                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "accessibility_click")
                    put("resolutionMethod", method)
                }.toString())
            }
        }

        // 2. Clickable ancestor of the target (target might itself be a child)
        val ancestor = NodeResolver.findClickableAncestor(target)
        if (ancestor != null) {
            val ancestorResult = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ancestor.recycle()
            if (ancestorResult) {
                Log.d(TAG, "Clickable ancestor click succeeded ($method)")
                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "clickable_ancestor")
                    put("resolutionMethod", method)
                }.toString())
            }
        }

        // 3. Coordinate fallback — ONLY with sane, visible bounds from current target
        val bounds = getNodeBounds(target)
        if (bounds != null && NodeResolver.isSaneBounds(bounds)) {
            val screenW = getScreenWidth()
            val screenH = getScreenHeight()
            if (bounds.centerX() in 0..screenW && bounds.centerY() in 0..screenH) {
                val tapResult = dispatchTap(bounds.centerX(), bounds.centerY())
                if (tapResult) {
                    Log.d(TAG, "Coordinate fallback succeeded ($method)")
                    return toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("method", "coordinate_fallback")
                        put("resolutionMethod", method)
                    }.toString())
                }
            }
        }

        return toolErrorResponse(id, "action_failed: node resolved but click/ancestor actions " +
            "failed. resolutionMethod=$method")
    }

    private fun bringIntoViewFresh(
        id: String,
        elementId: ElementId,
        traversal: FreshTraversalResult,
        deadline: Long
    ): String? {
        val methodStart = System.currentTimeMillis()
        var previousBounds: List<Int>? = null
        var noProgressCount = 0

        val service = AgentAccessibilityService.instance
            ?: return "accessibility_service_not_running"

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val viewportBottom = metrics.heightPixels

        val node = traversal.matchedNode
        val logical = NodeResolver.getLogicalActionableTarget(node,
            NodeResolver.snapshotManager.getDescriptor(elementId.snapshotId, elementId.nodeId)
                ?: return "element_expired")
        val direction = NodeResolver.determineScrollDirection(node, logical.clickableAncestor, 0, viewportBottom)

        if (direction == ScrollDirection.UNKNOWN) {
            return "target_offscreen_unreachable: cannot determine scroll direction"
        }

        val scrollable = NodeResolver.findScrollableAncestor(node)
        if (scrollable != null) {
            scrollable.recycle()
        }

        for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
            if (System.currentTimeMillis() >= deadline) {
                break
            }

            val screenH = metrics.heightPixels
            val screenW = metrics.widthPixels
            val centerX = screenW / 2
            val scrollDistance = screenH / 3
            val gestureResult = when (direction) {
                ScrollDirection.FORWARD -> dispatchScrollGesture(centerX, screenH / 2, centerX, screenH / 2 - scrollDistance, 300)
                ScrollDirection.BACKWARD -> dispatchScrollGesture(centerX, screenH / 2, centerX, screenH / 2 + scrollDistance, 300)
                ScrollDirection.UNKNOWN -> false
            }

            Thread.sleep(SCROLL_DELAY_MS)

            val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
            if (reTraversal != null) {
                try {
                    if (reTraversal.isVisible) {
                        Log.d(TAG, "[bringIntoViewFresh] scroll succeeded at attempt $attempt")
                        return null
                    }

                    val currentBounds = reTraversal.bounds
                    val currentBoundsList = listOf(currentBounds.left, currentBounds.top, currentBounds.right, currentBounds.bottom)
                    if (currentBoundsList == previousBounds) {
                        noProgressCount++
                        if (noProgressCount >= 2) {
                            return "target_offscreen_unreachable: no_scroll_progress"
                        }
                    } else {
                        noProgressCount = 0
                        previousBounds = currentBoundsList
                    }
                } finally {
                    NodeResolver.recycleAll(reTraversal)
                }
            }
        }

        return "target_offscreen_unreachable: target could not be brought into view"
    }

    private fun bringIntoView(
        id: String,
        elementId: ElementId,
        node: AccessibilityNodeInfo,
        logical: LogicallyResolved,
        deadline: Long
    ): String? {
        val methodStart = System.currentTimeMillis()
        var previousBounds: List<Int>? = null
        var noProgressCount = 0

        // Phase 1: ACTION_SHOW_ON_SCREEN (try once, short wait)
        try {
            val actionTarget = logical.node
            if ((actionTarget.actions and ACTION_SHOW_ON_SCREEN) != 0) {
                val showResult = actionTarget.performAction(ACTION_SHOW_ON_SCREEN)
                Log.d(TAG, "[bringIntoView] SHOW_ON_SCREEN: performAction=$showResult")

                Thread.sleep(SHOW_ON_SCREEN_DELAY_MS)

                val service = AgentAccessibilityService.instance
                if (service != null) {
                    val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
                    if (reTraversal != null) {
                        try {
                            if (reTraversal.isVisible) {
                                Log.d(TAG, "[bringIntoView] SHOW_ON_SCREEN succeeded")
                                return null
                            }
                            if (NodeResolver.isSaneBounds(reTraversal.bounds)) {
                                previousBounds = listOf(reTraversal.bounds.left, reTraversal.bounds.top,
                                    reTraversal.bounds.right, reTraversal.bounds.bottom)
                            }
                        } finally {
                            NodeResolver.recycleAll(reTraversal)
                        }
                    }
                }
            } else {
                Log.d(TAG, "[bringIntoView] SHOW_ON_SCREEN not supported on target")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[bringIntoView] SHOW_ON_SCREEN failed", e)
        }

        if (System.currentTimeMillis() >= deadline) {
            Log.w(TAG, "[bringIntoView] deadline reached after SHOW_ON_SCREEN")
            return "target_offscreen_unreachable: action_deadline_exceeded after show_on_screen"
        }

        // Phase 2: Directional scrolling with progress detection
        val scrollable = NodeResolver.findScrollableAncestor(node)
        if (scrollable == null) {
            Log.d(TAG, "[bringIntoView] no scrollable ancestor found")
            return "target_offscreen_unreachable: no scrollable ancestor"
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val viewportTop = 0
        val viewportBottom = metrics.heightPixels

        val direction = NodeResolver.determineScrollDirection(node, logical.clickableAncestor, viewportTop, viewportBottom)
        val targetBounds = Rect()
        (logical.clickableAncestor ?: node).getBoundsInScreen(targetBounds)
        Log.d(TAG, "[bringIntoView] scroll direction=$direction, viewport=[$viewportTop,$viewportBottom], " +
            "targetBounds=[${targetBounds.left},${targetBounds.top},${targetBounds.right},${targetBounds.bottom}]")

        if (direction == ScrollDirection.UNKNOWN) {
            scrollable.recycle()
            Log.d(TAG, "[bringIntoView] UNKNOWN direction")
            return "target_offscreen_unreachable: cannot determine scroll direction"
        }

        Log.d(TAG, "[bringIntoView] scrollable: class=${scrollable.className}, " +
            "scrollable=${scrollable.isScrollable}, childCount=${scrollable.childCount}")

        for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "[bringIntoView] deadline reached at attempt $attempt")
                break
            }

            val scrollStart = System.currentTimeMillis()
            val screenH = metrics.heightPixels
            val screenW = metrics.widthPixels
            val centerX = screenW / 2
            val scrollDistance = screenH / 3
            val gestureResult = when (direction) {
                ScrollDirection.FORWARD -> dispatchScrollGesture(centerX, screenH / 2, centerX, screenH / 2 - scrollDistance, 300)
                ScrollDirection.BACKWARD -> dispatchScrollGesture(centerX, screenH / 2, centerX, screenH / 2 + scrollDistance, 300)
                ScrollDirection.UNKNOWN -> false
            }

            Thread.sleep(SCROLL_DELAY_MS)

            val service = AgentAccessibilityService.instance
            if (service != null) {
                val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
                if (reTraversal != null) {
                    try {
                        if (reTraversal.isVisible) {
                            scrollable.recycle()
                            Log.d(TAG, "[bringIntoView] scroll succeeded at attempt $attempt")
                            return null
                        }

                        val currentBoundsList = listOf(reTraversal.bounds.left, reTraversal.bounds.top,
                            reTraversal.bounds.right, reTraversal.bounds.bottom)
                        Log.d(TAG, "[bringIntoView] attempt $attempt: gestureResult=$gestureResult, bounds=$currentBoundsList, visible=false")
                        if (currentBoundsList == previousBounds) {
                            noProgressCount++
                            Log.d(TAG, "[bringIntoView] no progress at attempt $attempt, noProgressCount=$noProgressCount")
                            if (noProgressCount >= 2) {
                                scrollable.recycle()
                                Log.d(TAG, "[bringIntoView] stopping: no scroll progress")
                                return "target_offscreen_unreachable: no_scroll_progress"
                            }
                        } else {
                            noProgressCount = 0
                            previousBounds = currentBoundsList
                        }
                    } finally {
                        NodeResolver.recycleAll(reTraversal)
                    }
                }
            }

            val scrollDuration = System.currentTimeMillis() - scrollStart
            Log.d(TAG, "[bringIntoView] scroll attempt $attempt: ${scrollDuration}ms")
        }

        scrollable.recycle()
        Log.d(TAG, "[bringIntoView] exhausted")
        return "target_offscreen_unreachable: target could not be brought into view"
    }

    private fun getNodeBounds(node: AccessibilityNodeInfo): android.graphics.Rect? {
        return try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (!NodeResolver.isSaneBounds(rect)) null else rect
        } catch (e: Exception) {
            null
        }
    }

    private fun getScreenWidth(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    private fun dispatchTap(x: Int, y: Int): Boolean {
        val service = AgentAccessibilityService.instance ?: return false
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        return service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun dispatchScrollGesture(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        val service = AgentAccessibilityService.instance ?: return false
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        return service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun tap(id: String, args: JSONObject): String {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return toolErrorResponse(id, "Invalid coordinates")

        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

        val result = service.dispatchGesture(gestureBuilder.build(), null, null)
        Log.d(TAG, "Tap at ($x, $y): $result")
        return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
    }

    private fun swipe(id: String, args: JSONObject): String {
        val startX = args.optInt("startX", -1)
        val startY = args.optInt("startY", -1)
        val endX = args.optInt("endX", -1)
        val endY = args.optInt("endY", -1)
        val durationMs = args.optLong("durationMs", 500)

        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            return toolErrorResponse(id, "Invalid coordinates")
        }

        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

        val result = service.dispatchGesture(gestureBuilder.build(), null, null)
        Log.d(TAG, "Swipe ($startX,$startY) -> ($endX,$endY): $result")
        return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
    }

    private fun inputText(id: String, args: JSONObject): String {
        val elementIdRaw = args.optString("elementId", "")
        val elementId = ElementId.decode(elementIdRaw)
            ?: return toolErrorResponse(id, "invalid_element_id: \"$elementIdRaw\" is not a valid element id")

        val text = args.optString("text", "")
        if (text.isEmpty()) return toolErrorResponse(id, "Empty text")

        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val traversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
            ?: return toolErrorResponse(id, "element_not_found: element $elementIdRaw could not be resolved")

        return try {
            val node = traversal.matchedNode
            if (!node.isEditable) {
                toolErrorResponse(id, "Element $elementIdRaw is not editable")
            } else {
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val setResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                Log.d(TAG, "Input text to element $elementIdRaw: $setResult")
                toolSuccessResponse(id, JSONObject().apply { put("success", setResult) }.toString())
            }
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    private fun back(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Log.d(TAG, "Back: $result")
        return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
    }

    private fun home(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Log.d(TAG, "Home: $result")
        return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
    }

    private fun launchApp(id: String, args: JSONObject): String {
        val packageName = args.optString("packageName", "")
        if (packageName.isEmpty()) return toolErrorResponse(id, "Empty packageName")

        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            return toolErrorResponse(id, "Cannot launch $packageName: package not found or has no launch intent")
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.d(TAG, "Launched $packageName")
            return toolSuccessResponse(id, JSONObject().apply {
                put("success", true)
                put("packageName", packageName)
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed for $packageName", e)
            return toolErrorResponse(id, "Launch failed: ${e.message}")
        }
    }

    private fun wait(id: String, args: JSONObject): String {
        val ms = args.optLong("milliseconds", 1000).coerceIn(0, MAX_WAIT_MS)
        Thread.sleep(ms)
        return toolSuccessResponse(id, JSONObject().apply {
            put("success", true)
            put("waitedMs", ms)
        }.toString())
    }

    private fun listApps(id: String): String {
        val apps = appRegistry.getLaunchableApps()
        val array = JSONArray()
        for (app in apps) {
            array.put(app.toJson())
        }
        return toolSuccessResponse(id, JSONObject().apply {
            put("count", apps.size)
            put("apps", array)
        }.toString())
    }

    private fun searchApps(id: String, args: JSONObject): String {
        val query = args.optString("query", "")
        if (query.isEmpty()) return toolErrorResponse(id, "Empty query")

        val results = appRegistry.searchApps(query)
        val array = JSONArray()
        for (result in results) {
            array.put(JSONObject().apply {
                put("name", result.app.name)
                put("package", result.app.packageName)
                put("score", result.score)
                put("category", result.app.category ?: JSONObject.NULL)
            })
        }
        return toolSuccessResponse(id, JSONObject().apply {
            put("query", query)
            put("count", results.size)
            put("results", array)
        }.toString())
    }

    private fun openApp(id: String, args: JSONObject): String {
        val name = args.optString("name", "")
        if (name.isEmpty()) return toolErrorResponse(id, "Empty app name")

        return when (val match = appRegistry.findBestMatch(name)) {
            is AppMatch.Single -> {
                val launched = appRegistry.launchApp(match.app.packageName)
                if (launched) {
                    toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("name", match.app.name)
                        put("package", match.app.packageName)
                    }.toString())
                } else {
                    toolErrorResponse(id, "Failed to launch ${match.app.name}")
                }
            }
            is AppMatch.Ambiguous -> {
                val candidates = JSONArray()
                for (app in match.candidates) {
                    candidates.put(JSONObject().apply {
                        put("name", app.name)
                        put("package", app.packageName)
                    })
                }
                toolErrorResponse(id, JSONObject().apply {
                    put("error", "ambiguous_app")
                    put("message", "Multiple apps match '$name'. Specify a more precise name.")
                    put("candidates", candidates)
                }.toString())
            }
            is AppMatch.NotFound -> {
                toolErrorResponse(id, "No app found matching '$name'")
            }
        }
    }

    private fun currentApp(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val root = findForegroundRoot(service)
        val packageName = root?.packageName?.toString() ?: "unknown"

        var label = packageName
        var activity: String? = null
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            label = pm.getApplicationLabel(appInfo).toString()

            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent?.component != null) {
                activity = intent.component?.className
            }
        } catch (_: Exception) {}

        return toolSuccessResponse(id, JSONObject().apply {
            put("name", label)
            put("package", packageName)
            put("activity", activity ?: JSONObject.NULL)
        }.toString())
    }

    private fun observe(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

        val rootNode = findForegroundRoot(service)
            ?: return toolErrorResponse(id, "No active window")

        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)

        val descriptors = NodeResolver.buildDescriptors(tree)
        val snapshotId = NodeResolver.snapshotManager.store(descriptors, tree.foregroundPackage)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scene = SemanticProjector.project(tree, snapshotId, metrics.heightPixels)

        return toolSuccessResponse(id, scene.toJson())
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

    private fun diagSealed(id: String, args: JSONObject): String {
        val elementIdRaw = args.optString("elementId", "")
        val elementId = ElementId.decode(elementIdRaw)
            ?: return toolErrorResponse(id, "invalid_element_id: \"$elementIdRaw\"")

        val log = mutableListOf<String>()
        val service = AgentAccessibilityService.instance
            ?: return toolErrorResponse(id, "Accessibility service not running")

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

    private fun toolDef(name: String, description: String, inputSchema: JSONObject): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", inputSchema)
            })
        }
    }

    private fun intParam(description: String): JSONObject {
        return JSONObject().apply {
            put("type", "integer")
            put("description", description)
        }
    }

    private fun stringParam(description: String): JSONObject {
        return JSONObject().apply {
            put("type", "string")
            put("description", description)
        }
    }

    private fun successResponse(id: String, result: JSONObject): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun toolSuccessResponse(id: String, textContent: String): String {
        val result = JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", textContent)
                })
            })
        }
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun toolErrorResponse(id: String, message: String): String {
        val result = JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", message)
                })
            })
            put("isError", true)
        }
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun errorResponse(id: String, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", -1)
                put("message", message)
            })
        }.toString()
    }
}
