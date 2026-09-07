package com.agent.accessibility.mcp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Local log storage for MCP tool calls.
 * Always stores locally. Optional sync to cloud for debugging.
 */
class LocalLogStore(private val context: Context) {

    companion object {
        private const val TAG = "LocalLogStore"
        private const val MAX_LOCAL_ENTRIES = 1000
        private const val LOG_FILE = "mcp_logs.json"
    }

    data class LogEntry(
        val timestamp: Long,
        val tool: String,
        val success: Boolean,
        val method: String? = null,
        val durationMs: Long = 0,
        val error: String? = null,
        val context: Map<String, Any> = emptyMap()
    )

    private val entries = ConcurrentLinkedDeque<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        loadFromFile()
    }

    /**
     * Log a tool call. Always stores locally.
     */
    fun log(
        tool: String,
        success: Boolean,
        method: String? = null,
        durationMs: Long = 0,
        error: String? = null,
        vararg contextPairs: Pair<String, Any>
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tool = tool,
            success = success,
            method = method,
            durationMs = durationMs,
            error = error,
            context = contextPairs.toMap()
        )
        entries.addFirst(entry)
        while (entries.size > MAX_LOCAL_ENTRIES) entries.removeLast()

        // Save to file periodically (every 10 entries)
        if (entries.size % 10 == 0) {
            saveToFile()
        }

        val status = if (success) "✓" else "✗"
        Log.d(TAG, "$status $tool ${method ?: ""} ${durationMs}ms${if (error != null) " err=$error" else ""}")
    }

    /**
     * Get recent log entries as JSON.
     */
    fun getRecentJson(limit: Int = 50): String {
        val array = JSONArray()
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
     * Get summary statistics.
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
     * Check if cloud sync is enabled.
     */
    fun isSyncEnabled(): Boolean {
        val prefs = context.getSharedPreferences("mcp_logs", Context.MODE_PRIVATE)
        return prefs.getBoolean("sync_enabled", false)
    }

    /**
     * Enable/disable cloud sync.
     */
    fun setSyncEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("mcp_logs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("sync_enabled", enabled).apply()
        Log.d(TAG, "Cloud sync ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get unsynced entries for cloud upload.
     */
    fun getUnsyncedEntries(): List<LogEntry> {
        val prefs = context.getSharedPreferences("mcp_logs", Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("last_sync_timestamp", 0)
        return entries.filter { it.timestamp > lastSync }.toList()
    }

    /**
     * Mark entries as synced.
     */
    fun markSynced(timestamp: Long) {
        val prefs = context.getSharedPreferences("mcp_logs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_sync_timestamp", timestamp).apply()
    }

    /**
     * Save logs to file.
     */
    private fun saveToFile() {
        try {
            val file = File(context.filesDir, LOG_FILE)
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(JSONObject().apply {
                    put("t", entry.timestamp)
                    put("tool", entry.tool)
                    put("ok", entry.success)
                    entry.method?.let { put("m", it) }
                    put("ms", entry.durationMs)
                    entry.error?.let { put("err", it) }
                    if (entry.context.isNotEmpty()) {
                        put("ctx", JSONObject(entry.context))
                    }
                })
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logs", e)
        }
    }

    /**
     * Load logs from file.
     */
    private fun loadFromFile() {
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (!file.exists()) return
            val text = file.readText()
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                entries.addLast(LogEntry(
                    timestamp = obj.getLong("t"),
                    tool = obj.getString("tool"),
                    success = obj.getBoolean("ok"),
                    method = obj.optString("m", null),
                    durationMs = obj.getLong("ms"),
                    error = obj.optString("err", null)
                ))
            }
            Log.d(TAG, "Loaded ${entries.size} log entries from file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
        }
    }

    /**
     * Clear all logs.
     */
    fun clear() {
        entries.clear()
        saveToFile()
        Log.d(TAG, "Logs cleared")
    }
}
