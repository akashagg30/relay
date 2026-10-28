// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

/**
 * Tier-A engine of the `act_toward_goal` micro-loop: a deterministic,
 * dependency-free heuristic that scores visible candidates against the goal.
 * Pure Kotlin — no Android APIs, no network — so it is 100% JVM-unit-testable.
 */
object HeuristicDecider {

    private val STOPWORDS: Set<String> = setOf(
        "the", "a", "an", "to", "in", "on", "of", "and",
        "then", "please", "now", "it", "this", "that"
    )

    private const val SELECTION_FLOOR = 0.30
    private const val QUOTE_PATTERN = "[\"“']([^\"”']{1,120})[\"”']"

    /** Flatten [SemanticScene.elements] including children, in document order. */
    private fun flatten(elements: List<SemanticElement>): List<SemanticElement> {
        val out = mutableListOf<SemanticElement>()
        for (el in elements) {
            out.add(el)
            if (el.children.isNotEmpty()) out.addAll(flatten(el.children))
        }
        return out
    }

    /**
     * Actionable candidates: visible + enabled elements with an interactive
     * role. Document order is preserved (depth-first pre-order).
     */
    fun candidates(scene: SemanticScene): List<SemanticElement> {
        return flatten(scene.elements).filter { el ->
            el.presentation == PresentationState.VISIBLE &&
                el.enabled &&
                (el.role == SemanticRole.ACTION || el.role == SemanticRole.INPUT ||
                    el.role == SemanticRole.TOGGLE || el.role == SemanticRole.TAB ||
                    el.role == SemanticRole.MENU)
        }
    }

    /** Lowercase goal tokens: split on non-alphanumeric, drop stopwords and 1-char tokens. */
    fun tokens(goal: String): List<String> {
        return goal.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it.length > 1 && it !in STOPWORDS }
    }

    /** First single/double-quoted literal in the goal, or null. */
    fun quotedLiteral(goal: String): String? {
        return Regex(QUOTE_PATTERN).find(goal)?.groupValues?.getOrNull(1)
    }

    private fun compact(s: String): String = s.filter { it.isLetterOrDigit() }

    /** True when [token] appears in the visible text, ignoring punctuation/case. */
    private fun tokenPresent(token: String, visibleLower: String, visibleCompact: String): Boolean {
        return visibleLower.contains(token) || visibleCompact.contains(token)
    }

    /**
     * Leading verb of the goal. Two-word prefixes (`search for`, `turn on`,
     * `turn off`) are recognised before falling back to the first word.
     */
    private fun leadingVerb(goal: String): String {
        val lower = goal.trim().lowercase()
        if (lower.startsWith("search for")) return "search for"
        if (lower.startsWith("turn on")) return "turn on"
        if (lower.startsWith("turn off")) return "turn off"
        return lower.split(Regex("[^a-z0-9]+")).firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun rolePrior(verb: String, role: SemanticRole): Double {
        return when {
            verb in setOf("type", "enter", "fill", "search", "write", "search for") &&
                (role == SemanticRole.INPUT || role == SemanticRole.TAB) -> 0.25
            verb in setOf("open", "click", "tap", "select", "go", "navigate", "choose") &&
                (role == SemanticRole.ACTION || role == SemanticRole.TAB ||
                    role == SemanticRole.MENU) -> 0.20
            verb in setOf("enable", "disable", "turn on", "turn off", "toggle", "check", "uncheck") &&
                role == SemanticRole.TOGGLE -> 0.30
            else -> 0.0
        }
    }

    private fun wantsOn(goalLower: String): Boolean {
        if (goalLower.contains(Regex("(?<![a-z])turn on(?![a-z])"))) return true
        if (goalLower.contains(Regex("(?<![a-z])enable(?![a-z])"))) return true
        return goalLower.contains(Regex("(?<![a-z])check(?![a-z])"))
    }

    private fun wantsOff(goalLower: String): Boolean {
        if (goalLower.contains(Regex("(?<![a-z])turn off(?![a-z])"))) return true
        if (goalLower.contains(Regex("(?<![a-z])disable(?![a-z])"))) return true
        return goalLower.contains(Regex("(?<![a-z])uncheck(?![a-z])"))
    }

    fun decide(scene: SemanticScene, goal: String): Decision {
        val goalTokens = tokens(goal)

        // Goal-already-met: every goal token is already visible on screen.
        if (goalTokens.isNotEmpty()) {
            val all = flatten(scene.elements)
            val sceneLower = all.joinToString(" ") { GoalGuards.visibleText(it) }.lowercase()
            val sceneCompact = compact(sceneLower)
            if (goalTokens.all { tokenPresent(it, sceneLower, sceneCompact) }) {
                return Decision(
                    action = null,
                    confidence = 1.0,
                    reason = "goal_text_present",
                    goalDone = true
                )
            }
        }

        val cands = candidates(scene)
        if (cands.isEmpty()) {
            return Decision(action = null, confidence = 0.0, reason = "no_candidate")
        }

        val verb = leadingVerb(goal)
        val phrase = goalTokens.joinToString(" ")
        var best: SemanticElement? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = LinkedHashMap<SemanticElement, Double>()
        for (el in cands) {
            val visibleLower = GoalGuards.visibleText(el).lowercase()
            val visibleCompact = compact(visibleLower)
            val textScore = if (goalTokens.isEmpty()) {
                0.0
            } else {
                goalTokens.count { tokenPresent(it, visibleLower, visibleCompact) }.toDouble() /
                    goalTokens.size.toDouble()
            }
            val phraseBonus =
                if (phrase.isNotEmpty() && visibleLower.contains(phrase)) 0.3 else 0.0
            val score = maxOf(0.0, textScore + phraseBonus + rolePrior(verb, el.role))
            scores[el] = score
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }

        val chosen = best ?: return Decision(action = null, confidence = 0.0, reason = "no_candidate")
        if (bestScore < SELECTION_FLOOR) {
            return Decision(action = null, confidence = 0.0, reason = "no_candidate")
        }

        val second = scores.entries
            .filter { it.key != chosen }
            .maxOfOrNull { it.value } ?: 0.0
        val margin = (bestScore - second) / maxOf(bestScore, 1e-6)
        val confidence = (0.45 + 0.55 * margin).coerceIn(0.0, 1.0)

        if (chosen.role == SemanticRole.INPUT) {
            val literal = quotedLiteral(goal)
            if (literal != null) {
                return Decision(
                    action = GoalActionSpec(GoalAction.INPUT_TEXT, chosen.elementId, literal),
                    confidence = confidence,
                    reason = "input_literal"
                )
            }
            return Decision(action = null, confidence = confidence, reason = "text_input_required")
        }

        if (chosen.role == SemanticRole.TOGGLE) {
            val goalLower = goal.lowercase()
            if ((wantsOn(goalLower) && chosen.checked == true) ||
                (wantsOff(goalLower) && chosen.checked == false)
            ) {
                return Decision(action = null, confidence = 1.0, reason = "already_set", goalDone = true)
            }
        }

        return Decision(
            action = GoalActionSpec(GoalAction.CLICK_NODE, chosen.elementId),
            confidence = confidence,
            reason = "heuristic_click"
        )
    }
}
