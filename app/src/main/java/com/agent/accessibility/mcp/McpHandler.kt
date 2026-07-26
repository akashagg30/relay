package com.agent.accessibility.mcp

import android.content.Context
import android.content.Intent
import android.graphics.Path
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

    companion object {
        private const val TAG = "McpHandler"
        private const val MAX_WAIT_MS = 10_000L
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
                "initialize" -> handleInitialize(id)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, params)
                else -> errorResponse(id, "Method not found: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request parse error", e)
            errorResponse("", "Parse error: ${e.message}")
        }
    }

    private fun handleInitialize(id: String): String {
        val result = JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
            })
            put("serverInfo", JSONObject().apply {
                put("name", "android-agent")
                put("version", "1.0.0")
            })
        }
        return successResponse(id, result)
    }

    private fun handleToolsList(id: String): String {
        val tools = JSONArray()

        tools.put(toolDef("get_screen_state", "Get current foreground app accessibility tree", JSONObject()))
        tools.put(toolDef("click_node", "Click a node by ID", JSONObject().apply {
            put("nodeId", intParam("Node ID from get_screen_state"))
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
        tools.put(toolDef("input_text", "Type text into an editable node", JSONObject().apply {
            put("nodeId", intParam("Editable node ID"))
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
            else -> errorResponse(id, "Unknown tool: $toolName")
        }
    }

    private fun getScreenState(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val rootNode = service.rootInActiveWindow
            ?: return errorResponse(id, "No active window")

        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)
        NodeResolver.updateFromTree(tree)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val nodesArray = JSONArray()
        tree.root?.let { flattenForJson(it, null, nodesArray) }

        val result = JSONObject().apply {
            put("packageName", tree.foregroundPackage ?: "unknown")
            put("timestamp", tree.timestamp)
            put("screenWidth", metrics.widthPixels)
            put("screenHeight", metrics.heightPixels)
            put("nodes", nodesArray)
        }

        return successResponse(id, result)
    }

    private fun flattenForJson(
        node: com.agent.accessibility.model.AccessibilityNodeData,
        parentId: Int?,
        array: JSONArray
    ) {
        if (node.text == null && node.contentDescription == null && !node.clickable &&
            !node.scrollable && !node.editable && node.viewIdResourceName == null
        ) {
            for (child in node.children) {
                flattenForJson(child, node.nodeId, array)
            }
            return
        }

        val nodeObj = JSONObject().apply {
            put("id", node.nodeId)
            if (parentId != null) put("parentId", parentId)
            put("className", node.className ?: "")
            if (!node.text.isNullOrBlank()) put("text", node.text)
            if (!node.contentDescription.isNullOrBlank()) put("contentDescription", node.contentDescription)
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
        }
        array.put(nodeObj)

        for (child in node.children) {
            flattenForJson(child, node.nodeId, array)
        }
    }

    private fun clickNode(id: String, args: JSONObject): String {
        val nodeId = args.optInt("nodeId", -1)
        if (nodeId < 0) return errorResponse(id, "Invalid nodeId")

        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val rootNode = service.rootInActiveWindow
            ?: return errorResponse(id, "No active window")

        val node = NodeResolver.resolveNode(rootNode, nodeId)
            ?: return errorResponse(id, "stale_node: could not resolve node $nodeId against current hierarchy")

        val clickResult = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clickResult) {
            Log.d(TAG, "Clicked node $nodeId directly")
            return successResponse(id, JSONObject().apply {
                put("success", true)
                put("method", "ACTION_CLICK")
            })
        }

        val ancestor = NodeResolver.findClickableAncestor(node)
        if (ancestor != null) {
            val ancestorResult = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ancestor.recycle()
            Log.d(TAG, "Clicked ancestor of node $nodeId")
            return successResponse(id, JSONObject().apply {
                put("success", ancestorResult)
                put("method", "ACTION_CLICK_ANCESTOR")
            })
        }

        return errorResponse(id, "Node $nodeId is not clickable and no clickable ancestor found")
    }

    private fun tap(id: String, args: JSONObject): String {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return errorResponse(id, "Invalid coordinates")

        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

        val result = service.dispatchGesture(gestureBuilder.build(), null, null)
        Log.d(TAG, "Tap at ($x, $y): $result")
        return successResponse(id, JSONObject().apply { put("success", result) })
    }

    private fun swipe(id: String, args: JSONObject): String {
        val startX = args.optInt("startX", -1)
        val startY = args.optInt("startY", -1)
        val endX = args.optInt("endX", -1)
        val endY = args.optInt("endY", -1)
        val durationMs = args.optLong("durationMs", 500)

        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            return errorResponse(id, "Invalid coordinates")
        }

        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

        val result = service.dispatchGesture(gestureBuilder.build(), null, null)
        Log.d(TAG, "Swipe ($startX,$startY) -> ($endX,$endY): $result")
        return successResponse(id, JSONObject().apply { put("success", result) })
    }

    private fun inputText(id: String, args: JSONObject): String {
        val nodeId = args.optInt("nodeId", -1)
        val text = args.optString("text", "")
        if (nodeId < 0) return errorResponse(id, "Invalid nodeId")
        if (text.isEmpty()) return errorResponse(id, "Empty text")

        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val rootNode = service.rootInActiveWindow
            ?: return errorResponse(id, "No active window")

        val node = NodeResolver.resolveNode(rootNode, nodeId)
            ?: return errorResponse(id, "stale_node: could not resolve node $nodeId")

        if (!node.isEditable) {
            node.recycle()
            rootNode.recycle()
            return errorResponse(id, "Node $nodeId is not editable")
        }

        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        node.recycle()
        rootNode.recycle()

        Log.d(TAG, "Input text to node $nodeId: $result")
        return successResponse(id, JSONObject().apply { put("success", result) })
    }

    private fun back(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Log.d(TAG, "Back: $result")
        return successResponse(id, JSONObject().apply { put("success", result) })
    }

    private fun home(id: String): String {
        val service = AgentAccessibilityService.instance
            ?: return errorResponse(id, "Accessibility service not running")

        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Log.d(TAG, "Home: $result")
        return successResponse(id, JSONObject().apply { put("success", result) })
    }

    private fun launchApp(id: String, args: JSONObject): String {
        val packageName = args.optString("packageName", "")
        if (packageName.isEmpty()) return errorResponse(id, "Empty packageName")

        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            return errorResponse(id, "Cannot launch $packageName: package not found or has no launch intent")
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.d(TAG, "Launched $packageName")
            return successResponse(id, JSONObject().apply {
                put("success", true)
                put("packageName", packageName)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed for $packageName", e)
            return errorResponse(id, "Launch failed: ${e.message}")
        }
    }

    private fun wait(id: String, args: JSONObject): String {
        val ms = args.optLong("milliseconds", 1000).coerceIn(0, MAX_WAIT_MS)
        Thread.sleep(ms)
        return successResponse(id, JSONObject().apply {
            put("success", true)
            put("waitedMs", ms)
        })
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
