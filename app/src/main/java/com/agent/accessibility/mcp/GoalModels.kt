// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

/** Terminal status of an `act_toward_goal` micro-loop run. */
enum class GoalStatus(val wire: String) {
    DONE("done"),
    ESCALATE("escalate"),
    PAUSED("paused"),
    STUCK("stuck"),
    BLOCKED("blocked"),
    MAX_STEPS("max_steps")
}

/** Primitive actions the loop can emit. */
enum class GoalAction(val wire: String) {
    CLICK_NODE("click_node"),
    INPUT_TEXT("input_text"),
    SUBMIT("submit"),
    SWIPE("swipe"),
    BACK("back"),
    WAIT("wait"),
    NONE("none")
}

/** Which engine produced a decision: local heuristic or frontier Jev model. */
enum class EngineTier(val wire: String) {
    HEURISTIC("heuristic"),
    JEV("jev")
}

/** A concrete action proposal. `text` is only set for text-bearing actions. */
data class GoalActionSpec(
    val action: GoalAction,
    val elementId: String? = null,
    val text: String? = null
)

/**
 * One engine decision. `action == null` means "no directly executable action":
 * either the goal is already met (`goalDone == true`) or the loop must
 * escalate / wait (`reason` explains which).
 */
data class Decision(
    val action: GoalActionSpec?,
    val confidence: Double,
    val reason: String,
    val probabilities: Map<String, Double>? = null,
    val goalDone: Boolean = false
)

/** Verdict of the pre-execution guard chain. */
enum class GuardVerdict {
    PASS,
    PAUSE,
    ESCALATE_LOW_CONFIDENCE,
    DESTRUCTIVE,
    STUCK
}

/** Immutable audit record of one executed (or vetoed) loop step. */
data class StepRecord(
    val index: Int,
    val action: String,
    val elementId: String?,
    val text: String?,
    val confidence: Double,
    val outcome: String,
    val stateHash: String,
    val engine: String,
    val guard: String? = null
)
