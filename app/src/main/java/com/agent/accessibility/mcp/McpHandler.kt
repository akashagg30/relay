package com.agent.accessibility.mcp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// Shared constants for the MCP tool implementation files in this package.
internal const val TAG = "McpHandler"
internal const val MAX_WAIT_MS = 10_000L
internal const val SHOW_ON_SCREEN_DELAY_MS = 200L
internal const val SCROLL_DELAY_MS = 200L
internal const val MAX_SCROLL_ATTEMPTS = 5
internal const val ACTION_SHOW_ON_SCREEN = 0x00100000
internal const val CLICK_DEADLINE_MS = 4000L

/**
 * JSON-RPC protocol layer for the Relay MCP server: request parsing,
 * method dispatch, and response framing.
 *
 * Tool implementations live in focused files alongside this one, as
 * extension functions on this class:
 * - `ToolRegistry.kt`      tool definitions / schemas served by tools/list
 * - `ObservationTools.kt`  get_screen_state, observe, find, scroll_until, diag_sealed
 * - `InteractionTools.kt`  click_node, tap, swipe, input_text, back, home + gesture helpers
 * - `AppTools.kt`          launch_app, list_apps, search_apps, open_app, current_app, wait
 */
class McpHandler(internal val context: Context) {

    internal val appRegistry = AppRegistry(context)
    internal val auditLogger = ToolAuditLogger(context)

    // Cached observation state shared by observe / find / scroll_until.
    @Volatile internal var lastObserveTimestamp = 0L
    @Volatile internal var lastElementCount = 0
    @Volatile internal var lastScene: SemanticScene? = null

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
                put("name", "relay")
                put("version", com.agent.accessibility.BuildConfig.APP_VERSION)
            })
        }
        return successResponse(id, result)
    }

    private fun handleToolsList(id: String): String {
        val result = JSONObject().apply { put("tools", buildToolsArray()) }
        return successResponse(id, result)
    }

    private fun handleToolsCall(id: String, params: JSONObject): String {
        val toolName = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        Log.d(TAG, "Tool call: $toolName")

        return when (toolName) {
            "observe" -> observe(id)
            "click_node" -> clickNode(id, args)
            "input_text" -> inputText(id, args)
            "swipe" -> swipe(id, args)
            "back" -> back(id)
            "home" -> home(id)
            "find" -> find(id, args)
            "scroll_until" -> scrollUntil(id, args)
            "current_app" -> currentApp(id)
            "open_app" -> openApp(id, args)
            else -> errorResponse(id, "Unknown tool: $toolName")
        }
    }

    // --- JSON-RPC response framing ---

    internal fun successResponse(id: String, result: JSONObject): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    internal fun toolSuccessResponse(id: String, textContent: String): String {
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

    internal fun toolErrorResponse(id: String, message: String): String {
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

    internal fun errorResponse(id: String, message: String): String {
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
