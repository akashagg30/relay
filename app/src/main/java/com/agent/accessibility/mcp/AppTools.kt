package com.agent.accessibility.mcp

import android.content.Intent
import android.util.Log
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-management tools: launching and inspecting installed applications.
 * Implements launch_app, wait, list_apps, search_apps, open_app and current_app.
 */

internal fun McpHandler.launchApp(id: String, args: JSONObject): String {
    val packageName = args.optString("packageName", "")
    if (packageName.isEmpty()) return toolErrorResponse(id, "Empty packageName")

    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        return toolErrorResponse(id, "Cannot launch $packageName: package not found or has no launch intent")
    }

    // Try accessibility service first (works on most devices)
    val service = AgentAccessibilityService.instance
    if (service != null) {
        try {
            val serviceIntent = Intent(intent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            service.startActivity(serviceIntent)
            Log.d(TAG, "Launched $packageName via accessibility service")
            Thread.sleep(500)
            auditLogger.log("launch_app", true, "accessibility", 0,
                "packageName" to packageName)
            return toolSuccessResponse(id, JSONObject().apply {
                put("success", true)
                put("packageName", packageName)
                put("method", "accessibility")
            }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility launch failed for $packageName, trying context", e)
        }
    }

    // Fallback: context.startActivity
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    try {
        context.startActivity(intent)
        Log.d(TAG, "Launched $packageName via context")
        Thread.sleep(500)
        auditLogger.log("launch_app", true, "context", 0,
            "packageName" to packageName)
        return toolSuccessResponse(id, JSONObject().apply {
            put("success", true)
            put("packageName", packageName)
            put("method", "context")
        }.toString())
    } catch (e: Exception) {
        Log.e(TAG, "Launch failed for $packageName", e)
        return toolErrorResponse(id, "Launch failed: ${e.message}")
    }
}

internal fun McpHandler.wait(id: String, args: JSONObject): String {
    val ms = args.optLong("milliseconds", 1000).coerceIn(0, MAX_WAIT_MS)
    Thread.sleep(ms)
    return toolSuccessResponse(id, JSONObject().apply {
        put("success", true)
        put("waitedMs", ms)
    }.toString())
}

internal fun McpHandler.listApps(id: String): String {
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

internal fun McpHandler.searchApps(id: String, args: JSONObject): String {
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

internal fun McpHandler.openApp(id: String, args: JSONObject): String {
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

internal fun McpHandler.currentApp(id: String): String {
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
