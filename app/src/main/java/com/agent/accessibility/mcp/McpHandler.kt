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
    internal val localLogStore = LocalLogStore(context)

    // Cached observation state shared by observe / find / scroll_until.
    @Volatile internal var lastObserveTimestamp = 0L
    @Volatile internal var lastElementCount = 0
    @Volatile internal var lastScene: SemanticScene? = null
    @Volatile internal var observeFallbackCount = 0

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

        Log.d(TAG, "Tool call: $toolName args=${args.toString().take(200)}")

        return when (toolName) {
            "get_screen_state" -> getScreenState(id)
            "click_node" -> clickNode(id, args)
            "swipe" -> swipe(id, args)
            "input_text" -> inputText(id, args)
            "back" -> back(id)
            "home" -> home(id)
            "launch_app" -> launchApp(id, args)
            "wait" -> wait(id, args)
            "list_apps" -> listApps(id)
            "search_apps" -> searchApps(id, args)
            "open_app" -> openApp(id, args)
            "search_in_app" -> searchInApp(id, args)
            "current_app" -> currentApp(id)
            "observe" -> observe(id)
            "find" -> find(id, args)
            "scroll_until" -> scrollUntil(id, args)
            "diag_sealed" -> diagSealed(id, args)
            "get_audit_log" -> getAuditLog(id, args)
            "get_audit_summary" -> getAuditSummary(id)
            "set_log_sync" -> setLogSync(id, args)
            "get_log_sync" -> getLogSync(id)
            "set_sync_endpoint" -> setSyncEndpoint(id, args)
            "get_logs" -> getLogs(id, args)
            "share_logs" -> shareLogs(id)
            "take_screenshot" -> takeScreenshot(id)
            "screenshot_with_overlay" -> screenshotWithOverlay(id)
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

    internal fun getAuditLog(id: String, args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        return toolSuccessResponse(id, JSONObject().apply {
            put("entries", org.json.JSONArray(auditLogger.getRecentJson(limit)))
        }.toString())
    }

    internal fun setLogSync(id: String, args: JSONObject): String {
        val enabled = args.optBoolean("enabled", false)
        localLogStore.setSyncEnabled(enabled)
        return toolSuccessResponse(id, JSONObject().apply {
            put("syncEnabled", localLogStore.isSyncEnabled())
        }.toString())
    }

    internal fun getLogSync(id: String): String {
        return toolSuccessResponse(id, JSONObject().apply {
            put("syncEnabled", localLogStore.isSyncEnabled())
            put("endpoint", localLogStore.getSyncEndpoint())
        }.toString())
    }

    internal fun setSyncEndpoint(id: String, args: JSONObject): String {
        val url = args.optString("url", "")
        if (url.isEmpty()) return toolErrorResponse(id, "Empty URL")
        localLogStore.setSyncEndpoint(url)
        return toolSuccessResponse(id, JSONObject().apply {
            put("endpoint", localLogStore.getSyncEndpoint())
        }.toString())
    }

    internal fun getAuditSummary(id: String): String {
        return toolSuccessResponse(id, JSONObject().apply {
            put("summary", auditLogger.getSummary())
        }.toString())
    }

    internal fun getLogs(id: String, args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        return toolSuccessResponse(id, JSONObject().apply {
            put("entries", org.json.JSONArray(localLogStore.getRecentJson(limit)))
        }.toString())
    }

    internal fun shareLogs(id: String): String {
        try {
            val logText = localLogStore.getRecentJson(100)
            val file = java.io.File(context.cacheDir, "relay_logs.txt")
            file.writeText(logText)

            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                android.net.Uri.fromFile(file)
            }

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Relay MCP Logs")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share Logs"))
            return toolSuccessResponse(id, JSONObject().apply { put("shared", true) }.toString())
        } catch (e: Exception) {
            return toolErrorResponse(id, "Share failed: ${e.message}")
        }
    }
}
