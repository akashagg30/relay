// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class AuditLogger(context: Context) {

    companion object {
        private const val TAG = "AuditLogger"
        private const val PREFS_KEY = "audit_logs"
        private const val MAX_LOGS = 100
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_audit", Context.MODE_PRIVATE)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    data class AuditEntry(
        val timestamp: String,
        val sourceIp: String,
        val authStatus: String, // "accepted" or "rejected"
        val method: String,
        val responseCode: Int
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("timestamp", timestamp)
                put("sourceIp", sourceIp)
                put("authStatus", authStatus)
                put("method", method)
                put("responseCode", responseCode)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): AuditEntry {
                return AuditEntry(
                    timestamp = json.getString("timestamp"),
                    sourceIp = json.getString("sourceIp"),
                    authStatus = json.getString("authStatus"),
                    method = json.getString("method"),
                    responseCode = json.getInt("responseCode")
                )
            }
        }
    }

    fun logRequest(sourceIp: String, authStatus: String, method: String, responseCode: Int) {
        val entry = AuditEntry(
            timestamp = dateFormatter.format(Date()),
            sourceIp = sourceIp,
            authStatus = authStatus,
            method = method,
            responseCode = responseCode
        )

        // Log to Android Log for logcat visibility
        Log.d(TAG, "Request: $sourceIp | $authStatus | $method | $responseCode")

        // Store in SharedPreferences
        val logs = getLogsArray()
        logs.put(entry.toJson())

        // Keep only last MAX_LOGS entries (FIFO)
        while (logs.length() > MAX_LOGS) {
            val newLogs = JSONArray()
            for (i in 1 until logs.length()) {
                newLogs.put(logs.get(i))
            }
            // Clear and rebuild the original array
            val originalLength = logs.length()
            for (i in originalLength - 1 downTo 0) {
                logs.remove(i)
            }
            for (i in 0 until newLogs.length()) {
                logs.put(newLogs.get(i))
            }
        }

        prefs.edit().putString(PREFS_KEY, logs.toString()).apply()
    }

    fun getRecentLogs(limit: Int = 10): List<AuditEntry> {
        val logs = getLogsArray()
        val result = mutableListOf<AuditEntry>()
        
        // Get last N entries
        val start = maxOf(0, logs.length() - limit)
        for (i in start until logs.length()) {
            try {
                val entry = AuditEntry.fromJson(logs.getJSONObject(i))
                result.add(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse log entry", e)
            }
        }
        
        return result.reversed() // Most recent first
    }

    fun clearLogs() {
        prefs.edit().remove(PREFS_KEY).apply()
        Log.d(TAG, "Audit logs cleared")
    }

    private fun getLogsArray(): JSONArray {
        val logsString = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        return try {
            JSONArray(logsString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored logs, resetting", e)
            JSONArray()
        }
    }
}