// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.net.SocketTimeoutException

/** One engine decision plus which tier produced it and why it fell back. */
data class RoutedDecision(
    val decision: Decision,
    val engine: EngineTier,
    val fallbackReason: String?
)

/**
 * The single place tier selection happens.
 *
 * - Tier [EngineTier.HEURISTIC]: local [HeuristicDecider], `fallbackReason == null`.
 * - Tier [EngineTier.JEV]: [JevSystemOneClient] when a key is configured;
 *   missing key or any [JevResult.Failed] degrades to the heuristic with the
 *   explicit reason carried in [RoutedDecision.fallbackReason] (never silent).
 * - Never throws: every failure path yields an explicit reason code.
 */
class GoalEngineRouter(
    private val decider: (SemanticScene, String) -> Decision = HeuristicDecider::decide,
    private val jev: (SemanticScene, String, List<SemanticElement>) -> JevResult =
        { _, _, _ -> JevResult.Failed("no_api_key") }
) {

    fun decide(scene: SemanticScene, goal: String, config: EngineConfig): RoutedDecision {
        try {
            if (config.tier() != EngineTier.JEV) {
                return RoutedDecision(safeDecide(scene, goal), EngineTier.HEURISTIC, null)
            }
            val key = try {
                config.apiKey()
            } catch (e: Exception) {
                null
            }
            if (key.isNullOrBlank()) {
                return RoutedDecision(safeDecide(scene, goal), EngineTier.HEURISTIC, "no_api_key")
            }
            val candidates = try {
                HeuristicDecider.candidates(scene)
            } catch (e: Exception) {
                emptyList()
            }
            val result = try {
                jev(scene, goal, candidates)
            } catch (e: SocketTimeoutException) {
                JevResult.Failed("timeout")
            } catch (e: Exception) {
                JevResult.Failed("network")
            }
            return when (result) {
                is JevResult.Failed ->
                    RoutedDecision(safeDecide(scene, goal), EngineTier.HEURISTIC, result.reason)
                is JevResult.Ok ->
                    RoutedDecision(mapOk(result, goal, candidates), EngineTier.JEV, null)
            }
        } catch (e: Exception) {
            return RoutedDecision(
                Decision(action = null, confidence = 0.0, reason = "router_error"),
                EngineTier.HEURISTIC,
                "router_error"
            )
        }
    }

    private fun safeDecide(scene: SemanticScene, goal: String): Decision {
        return try {
            decider(scene, goal)
        } catch (e: Exception) {
            Decision(action = null, confidence = 0.0, reason = "router_error")
        }
    }

    private fun mapOk(
        result: JevResult.Ok,
        goal: String,
        candidates: List<SemanticElement>
    ): Decision {
        if (result.goalDone) {
            return Decision(
                action = null,
                confidence = result.confidence,
                reason = "jev_goal_done",
                probabilities = result.probabilities,
                goalDone = true
            )
        }
        val target = candidates.firstOrNull { it.elementId == result.elementId }
        if (target == null) {
            return Decision(
                action = null,
                confidence = result.confidence,
                reason = "jev_no_target",
                probabilities = result.probabilities
            )
        }
        if (target.role == SemanticRole.INPUT) {
            val literal = try {
                HeuristicDecider.quotedLiteral(goal)
            } catch (e: Exception) {
                null
            }
            if (literal != null) {
                return Decision(
                    action = GoalActionSpec(GoalAction.INPUT_TEXT, target.elementId, literal),
                    confidence = result.confidence,
                    reason = "jev_input",
                    probabilities = result.probabilities
                )
            }
            return Decision(
                action = null,
                confidence = result.confidence,
                reason = "text_input_required",
                probabilities = result.probabilities
            )
        }
        return Decision(
            action = GoalActionSpec(GoalAction.CLICK_NODE, target.elementId),
            confidence = result.confidence,
            reason = "jev_click",
            probabilities = result.probabilities
        )
    }
}
