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
 * Local log storage for MCP tool calls with daily rotation.
 * Always stores locally. Optional sync to cloud for debugging.
 */
class LocalLogStore(private val context: Context) {

    companion object {
        private const val TAG = "LocalLogStore"
        private const val MAX_ENTRIES_PER_DAY = 5000
        private const val MAX_ROTATION_DAYS = 7
        private const val LOG_DIR = "mcp_logs"
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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        loadTodayLogs()
        cleanupOldLogs()
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

        // Rotate if too many entries today
        if (entries.size > MAX_ENTRIES_PER_DAY) {
            rotateTodayLogs()
        }

        // Save periodically (every 20 entries)
        if (entries.size % 20 == 0) {
            saveTodayLogs()
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
            val time = timestampFormat.format(Date(entry.timestamp))
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
     * Get all logs for today as JSON (for sync).
     */
    fun getTodayLogsJson(): String {
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
     * Get sync endpoint URL.
     */
    fun getSyncEndpoint(): String {
        val prefs = context.getSharedPreferences("mcp_logs", Context.MODE_PRIVATE)
        return prefs.getString("sync_endpoint", "") ?: ""
    }

    /**
     * Set sync endpoint URL.
     */
    fun setSyncEndpoint(endpoint: String) {
        val prefs = context.getSharedPreferences("mcp_logs", Context.MODE_PRIVATE)
        prefs.edit().putString("sync_endpoint", endpoint).apply()
        Log.d(TAG, "Sync endpoint set: $endpoint")
    }

    /**
     * Rotate today's logs to a dated file.
     */
    private fun rotateTodayLogs() {
        saveTodayLogs()
        entries.clear()
    }

    /**
     * Save today's logs to a dated file.
     */
    private fun saveTodayLogs() {
        try {
            val logDir = File(context.filesDir, LOG_DIR)
            logDir.mkdirs()
            val today = dateFormat.format(Date())
            val file = File(logDir, "$today.json")
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
     * Load today's logs from file.
     */
    private fun loadTodayLogs() {
        try {
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) return
            val today = dateFormat.format(Date())
            val file = File(logDir, "$today.json")
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
            Log.d(TAG, "Loaded ${entries.size} log entries for today")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
        }
    }

    /**
     * Cleanup old log files (keep last 7 days).
     */
    private fun cleanupOldLogs() {
        try {
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) return
            val cutoff = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -MAX_ROTATION_DAYS)
            }.time
            val cutoffStr = dateFormat.format(cutoff)
            logDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".json") && file.name < cutoffStr) {
                    file.delete()
                    Log.d(TAG, "Deleted old log: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old logs", e)
        }
    }

    /**
     * Clear all logs.
     */
    fun clear() {
        entries.clear()
        val logDir = File(context.filesDir, LOG_DIR)
        logDir.deleteRecursively()
        Log.d(TAG, "All logs cleared")
    }
}
