// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.ui

/**
 * Pure validation/masking helpers for [GoalEngineSettingsCard].
 *
 * Kept free of Compose/Android imports so the rules the settings UI
 * enforces (endpoint scheme, threshold range, key masking) are covered by
 * plain JVM unit tests.
 */

/** Accepted confidence-threshold range enforced by the settings UI. */
const val GOAL_THRESHOLD_MIN = 0.30
const val GOAL_THRESHOLD_MAX = 0.95

/** Returns true when [value] is a persistable endpoint (must be `https://`). */
fun isValidGoalEndpoint(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("https://") && trimmed.length > "https://".length
}

/** Parses a threshold field value; returns null when not a number. */
fun parseGoalThreshold(value: String): Double? = value.trim().toDoubleOrNull()

/** Returns true when [value] parses and lies in the accepted range 0.30..0.95. */
fun isValidGoalThreshold(value: String): Boolean {
    val parsed = parseGoalThreshold(value) ?: return false
    return parsed in GOAL_THRESHOLD_MIN..GOAL_THRESHOLD_MAX
}

/**
 * Masked hint for a stored key: `sk-…abcd` (last 4 chars only).
 * The full key is never displayed, never logged, and never leaves
 * the `goal_engine` SharedPreferences.
 */
fun maskGoalApiKey(key: String): String =
    if (key.length <= 4) "sk-…••••" else "sk-…" + key.takeLast(4)
