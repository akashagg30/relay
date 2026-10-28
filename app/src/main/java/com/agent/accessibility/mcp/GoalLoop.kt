// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

/**
 * The bounded perceive → decide → act → verify loop behind `act_toward_goal`.
 *
 * Pure Kotlin except for the injected [GoalEnvironment]: no Android APIs, no
 * network, so the whole loop runs under plain JVM unit tests with a fake
 * environment and a scripted router.
 *
 * Loop algorithm (in order): clamp `maxSteps` (tier A `1..5`, tier B `1..10`,
 * never `<= 0`); snapshot; per step check the foreground policy, route one
 * decision, run the fixed guard chain (PAUSE → low-confidence → DESTRUCTIVE
 * → STUCK), honour `goalDone`, escalate on a null action, otherwise perform,
 * settle, re-snapshot and verify in code (no model call) by requiring every
 * non-stopword goal token in the new screen's visible text. On ESCALATE only,
 * one extra fresh snapshot is serialised into [GoalResult.elementIdsJson] —
 * the zero-RTT takeover payload, already in `observe()` element format with
 * nested children, so the frontier can `click_node` an id immediately.
 */
interface GoalEnvironment {
    fun snapshot(): SemanticScene
    fun perform(spec: GoalActionSpec): Boolean
    fun waitMs(ms: Long)
    fun isForegroundAllowed(): Boolean
}

/** Min/max/mean over the confidence of every decision made (terminal included). */
data class ConfidenceStats(
    val threshold: Double,
    val min: Double,
    val max: Double,
    val mean: Double,
    val count: Int
)

data class GoalResult(
    val status: GoalStatus,
    val engine: EngineTier,
    val fallbackReason: String?,
    val steps: List<StepRecord>,
    val elementIdsJson: String?,
    val confidence: ConfidenceStats,
    val reason: String,
    val elapsedMs: Long,
    val effectiveMaxSteps: Int
)

class GoalLoop(
    private val env: GoalEnvironment,
    private val router: (SemanticScene, String) -> RoutedDecision,
    private val log: GoalDecisionLog
) {

    fun run(
        goal: String,
        maxSteps: Int,
        confidenceThreshold: Double,
        pauseBeforeKeywords: List<String>
    ): GoalResult {
        val startMs = System.currentTimeMillis()
        val gh = goalHash(goal)
        var scene = env.snapshot()
        var sceneHash = scene.computeScreenHash()
        var unchangedStreak = 0
        val steps = mutableListOf<StepRecord>()
        val confidences = mutableListOf<Double>()
        var lastEngine = EngineTier.HEURISTIC
        var lastFallback: String? = null
        var capKnown = false
        // Provisional tier-A clamp until the first routed decision reveals the tier.
        var effectiveMaxSteps = maxSteps.coerceIn(1, MAX_STEPS_TIER_A)

        fun stats(): ConfidenceStats {
            if (confidences.isEmpty()) {
                return ConfidenceStats(confidenceThreshold, 0.0, 0.0, 0.0, 0)
            }
            return ConfidenceStats(
                confidenceThreshold,
                confidences.min(),
                confidences.max(),
                confidences.average(),
                confidences.size
            )
        }

        fun finish(status: GoalStatus, reason: String, withFreshIds: Boolean): GoalResult {
            val ids = if (withFreshIds) elementsToJsonArray(env.snapshot().elements) else null
            return GoalResult(
                status, lastEngine, lastFallback, steps.toList(), ids,
                stats(), reason, System.currentTimeMillis() - startMs, effectiveMaxSteps
            )
        }

        var index = 1
        while (index <= effectiveMaxSteps) {
            if (!env.isForegroundAllowed()) {
                return finish(GoalStatus.BLOCKED, "policy_blocked", false)
            }
            val routed = try {
                router(scene, goal)
            } catch (_: Exception) {
                RoutedDecision(
                    Decision(action = null, confidence = 0.0, reason = "router_error"),
                    EngineTier.HEURISTIC,
                    "router_error"
                )
            }
            lastEngine = routed.engine
            lastFallback = routed.fallbackReason
            if (!capKnown) {
                capKnown = true
                val cap = if (routed.engine == EngineTier.JEV) MAX_STEPS_TIER_JEV else MAX_STEPS_TIER_A
                effectiveMaxSteps = maxSteps.coerceIn(1, cap)
            }
            val decision = routed.decision
            confidences.add(decision.confidence)
            val target = decision.action?.elementId?.let { findElement(scene.elements, it) }

            when (GoalGuards.evaluate(
                pauseBeforeKeywords, confidenceThreshold, decision, target, unchangedStreak
            )) {
                GuardVerdict.PAUSE -> {
                    val kw = matchedPauseKeyword(pauseBeforeKeywords, decision, target) ?: "unknown"
                    log.record(gh, sceneHash, index, routed.engine.wire,
                        decision.action?.elementId, decision.action?.action?.wire ?: "none",
                        decision.confidence, decision.probabilities,
                        GuardVerdict.PAUSE.name, "not_performed", GoalStatus.PAUSED.wire)
                    return finish(GoalStatus.PAUSED, "pause_keyword:$kw", false)
                }
                GuardVerdict.ESCALATE_LOW_CONFIDENCE -> {
                    log.record(gh, sceneHash, index, routed.engine.wire,
                        decision.action?.elementId, decision.action?.action?.wire ?: "none",
                        decision.confidence, decision.probabilities,
                        GuardVerdict.ESCALATE_LOW_CONFIDENCE.name, "not_performed",
                        GoalStatus.ESCALATE.wire)
                    return finish(GoalStatus.ESCALATE, "low_confidence:${decision.confidence}", true)
                }
                GuardVerdict.DESTRUCTIVE -> {
                    val kw = matchedDestructiveKeyword(decision, target) ?: "unknown"
                    log.record(gh, sceneHash, index, routed.engine.wire,
                        decision.action?.elementId, decision.action?.action?.wire ?: "none",
                        decision.confidence, decision.probabilities,
                        GuardVerdict.DESTRUCTIVE.name, "not_performed", GoalStatus.BLOCKED.wire)
                    return finish(GoalStatus.BLOCKED, "destructive_action:$kw", false)
                }
                GuardVerdict.STUCK -> {
                    log.record(gh, sceneHash, index, routed.engine.wire,
                        decision.action?.elementId, decision.action?.action?.wire ?: "none",
                        decision.confidence, decision.probabilities,
                        GuardVerdict.STUCK.name, "not_performed", GoalStatus.STUCK.wire)
                    return finish(GoalStatus.STUCK, "unchanged_3_steps", false)
                }
                GuardVerdict.PASS -> Unit
            }

            if (decision.goalDone) {
                log.record(gh, sceneHash, index, routed.engine.wire, null, "none",
                    decision.confidence, decision.probabilities, null,
                    "no_action", GoalStatus.DONE.wire)
                return finish(GoalStatus.DONE, decision.reason, false)
            }

            val spec = decision.action
            if (spec == null) {
                log.record(gh, sceneHash, index, routed.engine.wire, null, "none",
                    decision.confidence, decision.probabilities, null,
                    "no_action", GoalStatus.ESCALATE.wire)
                return finish(GoalStatus.ESCALATE, decision.reason, true)
            }

            val ok = try {
                env.perform(spec)
            } catch (_: Exception) {
                false
            }
            env.waitMs(STEP_SETTLE_MS)
            val newScene = env.snapshot()
            val newHash = newScene.computeScreenHash()
            val changed = newHash != sceneHash
            val outcome = if (!ok) "failed" else if (changed) "changed" else "unchanged"

            if (goalVisible(newScene, goal)) {
                steps.add(StepRecord(index, spec.action.wire, spec.elementId, spec.text,
                    decision.confidence, outcome, newHash, routed.engine.wire, null))
                log.record(gh, newHash, index, routed.engine.wire, spec.elementId,
                    spec.action.wire, decision.confidence, decision.probabilities,
                    null, outcome, GoalStatus.DONE.wire)
                return finish(GoalStatus.DONE, "goal_text_present", false)
            }

            steps.add(StepRecord(index, spec.action.wire, spec.elementId, spec.text,
                decision.confidence, outcome, newHash, routed.engine.wire, null))
            log.record(gh, newHash, index, routed.engine.wire, spec.elementId,
                spec.action.wire, decision.confidence, decision.probabilities,
                null, outcome, "continued")
            unchangedStreak = if (changed) 0 else unchangedStreak + 1
            scene = newScene
            sceneHash = newHash
            index++
        }
        return finish(GoalStatus.MAX_STEPS, "max_steps_exceeded", false)
    }

    companion object {
        /** Settle delay between performing an action and re-snapshotting. */
        const val STEP_SETTLE_MS = 400L

        /** Tier-A (heuristic) step budget. */
        const val MAX_STEPS_TIER_A = 5

        /** Tier-B (Jev) step budget. */
        const val MAX_STEPS_TIER_JEV = 10
    }
}

/** Depth-first search for an element id, descending into children. */
private fun findElement(elements: List<SemanticElement>, id: String): SemanticElement? {
    for (el in elements) {
        if (el.elementId == id) return el
        val nested = findElement(el.children, id)
        if (nested != null) return nested
    }
    return null
}

/** Same haystack the guard chain matches against: target visible text plus typed text. */
private fun guardHaystack(decision: Decision, target: SemanticElement?): String {
    return buildString {
        if (target != null) {
            append(GoalGuards.visibleText(target))
            append(' ')
        }
        append(decision.action?.text ?: "")
    }
}

private fun matchedPauseKeyword(
    pauseKeywords: List<String>,
    decision: Decision,
    target: SemanticElement?
): String? = GoalGuards.matchedKeyword(guardHaystack(decision, target), pauseKeywords)

private fun matchedDestructiveKeyword(decision: Decision, target: SemanticElement?): String? =
    GoalGuards.matchedKeyword(guardHaystack(decision, target), GoalGuards.DESTRUCTIVE_KEYWORDS)

/**
 * In-code verification: every non-stopword goal token must appear in the
 * screen's visible text (substring or punctuation-insensitive match), mirroring
 * the decider's tokenisation so the loop and the engine agree on "done".
 */
private fun goalVisible(scene: SemanticScene, goal: String): Boolean {
    val tokens = HeuristicDecider.tokens(goal)
    if (tokens.isEmpty()) return false
    val visibleLower = flattenVisible(scene.elements).joinToString(" ").lowercase()
    val visibleCompact = visibleLower.filter { it.isLetterOrDigit() }
    return tokens.all { token ->
        visibleLower.contains(token) || visibleCompact.contains(token)
    }
}

private fun flattenVisible(elements: List<SemanticElement>): List<String> {
    val out = mutableListOf<String>()
    for (el in elements) {
        out.add(GoalGuards.visibleText(el))
        if (el.children.isNotEmpty()) out.addAll(flattenVisible(el.children))
    }
    return out
}

/**
 * Serialise elements in `observe()` format (nested children included) without
 * `org.json`, which is Android-stubbed under plain JVM unit tests. Mirrors
 * [SemanticElement.toJson] field-for-field so the on-device payload and the
 * tested shape are identical.
 */
internal fun elementsToJsonArray(elements: List<SemanticElement>): String {
    return elements.joinToString(",", "[", "]") { elementToJsonString(it) }
}

internal fun elementToJsonString(el: SemanticElement): String {
    val sb = StringBuilder()
    sb.append("{\"id\":${MiniJson.quote(el.elementId)},\"role\":${MiniJson.quote(el.role.value)}")
    if (el.title != null) sb.append(",\"title\":${MiniJson.quote(el.title)}")
    if (el.summary != null) sb.append(",\"summary\":${MiniJson.quote(el.summary)}")
    if (el.value != null) sb.append(",\"value\":${MiniJson.quote(el.value)}")
    if (el.placeholder != null) sb.append(",\"placeholder\":${MiniJson.quote(el.placeholder)}")
    if (!el.enabled) sb.append(",\"enabled\":false")
    if (el.checked != null) sb.append(",\"checked\":${el.checked}")
    if (el.selected != null) sb.append(",\"selected\":${el.selected}")
    if (el.focused != null) sb.append(",\"focused\":${el.focused}")
    if (el.scrollable) sb.append(",\"scrollable\":true")
    if (el.presentation != PresentationState.VISIBLE) {
        sb.append(",\"presentation\":${MiniJson.quote(el.presentation.value)}")
    }
    if (el.bounds != null) sb.append(",\"bounds\":${boundsToJsonString(el.bounds)}")
    if (el.children.isNotEmpty()) {
        sb.append(",\"children\":${elementsToJsonArray(el.children)}")
    }
    sb.append("}")
    return sb.toString()
}

private fun boundsToJsonString(bounds: BoundsHint): String {
    val sb = StringBuilder()
    sb.append("{\"position\":${MiniJson.quote(bounds.position)},")
    sb.append("\"vertical\":${MiniJson.quote(bounds.vertical)}")
    if (bounds.hasPixels) {
        sb.append(",\"x\":${bounds.left},\"y\":${bounds.top},")
        sb.append("\"width\":${bounds.right - bounds.left},\"height\":${bounds.bottom - bounds.top}")
    }
    sb.append("}")
    return sb.toString()
}
