package com.agent.accessibility.mcp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Tracks MCP tool usage for debugging and observability.
 * Records every tool call with success/failure, method, timing, and context.
 */
class ToolAuditLogger(private val context: Context) {

    companion object {
        private const val TAG = "ToolAudit"
        private const val MAX_ENTRIES = 200
    }

    data class AuditEntry(
        val timestamp: Long,
        val tool: String,
        val success: Boolean,
        val method: String? = null,
        val durationMs: Long = 0,
        val error: String? = null,
        val context: Map<String, Any> = emptyMap()
    )

    private val entries = ConcurrentLinkedDeque<AuditEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Log a tool call.
     */
    fun log(
        tool: String,
        success: Boolean,
        method: String? = null,
        durationMs: Long = 0,
        error: String? = null,
        vararg contextPairs: Pair<String, Any>
    ) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            tool = tool,
            success = success,
            method = method,
            durationMs = durationMs,
            error = error,
            context = contextPairs.toMap()
        )
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()

        val status = if (success) "✓" else "✗"
        val ctx = if (context.isNotEmpty()) " ${contextPairs.joinToString { "${it.key}=${it.value}" }}" else ""
        Log.d(TAG, "$status $tool ${method ?: ""} ${durationMs}ms${if (error != null) " err=$error" else ""}$ctx")
    }

    /**
     * Get recent audit entries as JSON.
     */
    fun getRecentJson(limit: Int = 20): String {
        val array = org.json.JSONArray()
        entries.take(limit).forEach { entry ->
            val time = dateFormat.format(Date(entry.timestamp))
            array.put(JSONObject().apply {
                put("time", time)
                put("tool", entry.tool)
                put("ok", entry.success)
                put("ms", entry.durationMs)
                entry.method?.let { put("method", it) }
                entry.error?.let { put("error", it) }
                if (entry.context.isNotEmpty()) {
                    put("ctx", JSONObject(entry.context))
                }
            })
        }
        return array.toString()
    }

    /**
     * Get summary stats.
     */
    fun getSummary(): JSONObject {
        val recent = entries.take(100)
        val byTool = recent.groupBy { it.tool }
        val summary = JSONObject()

        for ((tool, calls) in byTool) {
            val total = calls.size
            val success = calls.count { it.success }
            val avgMs = if (calls.isNotEmpty()) calls.map { it.durationMs }.average().toLong() else 0
            val methods = calls.mapNotNull { it.method }.groupingBy { it }.eachCount()

            summary.put(tool, JSONObject().apply {
                put("total", total)
                put("success", success)
                put("fail", total - success)
                put("avgMs", avgMs)
                if (methods.isNotEmpty()) put("methods", JSONObject(methods))
            })
        }
        return summary
    }

    /**
     * Get a human-readable summary for debugging.
     */
    fun getReadableSummary(): String {
        val summary = getSummary()
        val sb = StringBuilder("=== Tool Audit Summary (last 100 calls) ===\n")
        for (tool in summary.keys()) {
            val stats = summary.getJSONObject(tool)
            sb.appendLine("$tool: ${stats.getInt("success")}/${stats.getInt("total")} ok, avg ${stats.getLong("avgMs")}ms")
        }
        return sb.toString()
    }
}
