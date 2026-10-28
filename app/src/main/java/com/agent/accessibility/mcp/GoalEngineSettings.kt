// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.content.Context

/**
 * Which engine decides the next loop step: the on-device deterministic
 * heuristic ([EngineTier.HEURISTIC], tier A) or the frontier Jev model
 * ([EngineTier.JEV], tier B, user-supplied key).
 */
interface EngineConfig {
    fun tier(): EngineTier
    fun apiKey(): String?
    fun endpoint(): String
    fun confidenceThreshold(): Double
}

/**
 * [EngineConfig] backed by `SharedPreferences` (`goal_engine`).
 *
 * Key policy: the user supplies their own key in app settings. It is stored
 * locally, never hardcoded, never bundled, and never logged. [apiKey]
 * returns `null` when the stored value is blank so the router can fall back
 * to tier A with an explicit `no_api_key` reason.
 */
class PrefsEngineConfig(context: Context) : EngineConfig {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun tier(): EngineTier {
        return when (prefs.getString(KEY_TIER, TIER_HEURISTIC)) {
            TIER_JEV -> EngineTier.JEV
            else -> EngineTier.HEURISTIC
        }
    }

    override fun apiKey(): String? {
        val raw = prefs.getString(KEY_API_KEY, "") ?: ""
        return raw.ifBlank { null }
    }

    override fun endpoint(): String {
        val raw = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        return raw.ifBlank { DEFAULT_ENDPOINT }
    }

    override fun confidenceThreshold(): Double {
        val raw = prefs.getString(KEY_THRESHOLD, DEFAULT_THRESHOLD_TEXT) ?: DEFAULT_THRESHOLD_TEXT
        return raw.toDoubleOrNull() ?: DEFAULT_THRESHOLD
    }

    fun setTier(t: EngineTier) {
        prefs.edit().putString(KEY_TIER, t.wire).apply()
    }

    fun setApiKey(k: String) {
        prefs.edit().putString(KEY_API_KEY, k).apply()
    }

    fun setEndpoint(u: String) {
        prefs.edit().putString(KEY_ENDPOINT, u).apply()
    }

    fun setThreshold(v: Double) {
        prefs.edit().putString(KEY_THRESHOLD, v.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "goal_engine"
        const val KEY_TIER = "tier"
        const val KEY_API_KEY = "jev_api_key"
        const val KEY_ENDPOINT = "jev_endpoint"
        const val KEY_THRESHOLD = "confidence_threshold"

        const val TIER_HEURISTIC = "heuristic"
        const val TIER_JEV = "jev"

        const val DEFAULT_ENDPOINT = "https://opencode.ai/zen/v1/systemone"
        const val DEFAULT_THRESHOLD = 0.55
        const val DEFAULT_THRESHOLD_TEXT = "0.55"
    }
}
