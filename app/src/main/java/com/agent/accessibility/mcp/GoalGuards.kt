// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

/**
 * Pre-execution guard chain for the `act_toward_goal` micro-loop.
 *
 * Priority is strict, first match wins:
 * `PAUSE` -> `ESCALATE_LOW_CONFIDENCE` -> `DESTRUCTIVE` -> `STUCK` -> `PASS`.
 *
 * Why the destructive guard sits *after* the confidence gate: a low-confidence
 * destructive guess is most likely a misread (the cheap local tier firing on a
 * vague resemblance), so it escalates to the frontier Jev tier for a second
 * opinion instead of silently blocking. A confident destructive match blocks
 * loudly with [GuardVerdict.DESTRUCTIVE], which the loop surfaces as BLOCKED.
 */
object GoalGuards {

    val DESTRUCTIVE_KEYWORDS: List<String> = listOf(
        "delete",
        "remove",
        "uninstall",
        "clear data",
        "factory reset",
        "erase",
        "format",
        "purchase",
        "buy",
        "pay",
        "checkout",
        "confirm order",
        "send money",
        "transfer",
        "log out",
        "sign out",
        "deactivate",
        "revoke"
    )

    val DEFAULT_PAUSE_KEYWORDS: List<String> = listOf(
        "pay",
        "buy",
        "purchase",
        "post",
        "publish",
        "send"
    )

    /**
     * Human-visible text of an element: title + summary + value + placeholder.
     * Never includes elementId, role, or class/resource-id — matching on those
     * would block benign controls whose internal ids happen to contain a
     * scary word (e.g. id `btn_delete` on a button labelled "Settings").
     */
    fun visibleText(el: SemanticElement): String {
        return listOfNotNull(el.title, el.summary, el.value, el.placeholder)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * First keyword found in [text], or null. Matching is case-insensitive with
     * letter-boundary guards: `(?<![a-z])kw(?![a-z])` on the lowercased text,
     * so `send` matches "Send message" but not "sender".
     */
    fun matchedKeyword(text: String, keywords: List<String>): String? {
        val lower = text.lowercase()
        for (kw in keywords) {
            val pattern = "(?<![a-z])" + Regex.escape(kw.lowercase()) + "(?![a-z])"
            if (Regex(pattern).containsMatchIn(lower)) return kw
        }
        return null
    }

    /**
     * Runs the guard chain. The haystack is the target's visible text plus any
     * literal text the decision is about to type — both are human-visible, so
     * the visible-text-only product rule holds for the whole chain.
     */
    fun evaluate(
        pauseKeywords: List<String>,
        confidenceThreshold: Double,
        decision: Decision,
        target: SemanticElement?,
        unchangedStreak: Int
    ): GuardVerdict {
        val haystack = buildString {
            if (target != null) {
                append(visibleText(target))
                append(' ')
            }
            append(decision.action?.text ?: "")
        }
        if (matchedKeyword(haystack, pauseKeywords) != null) return GuardVerdict.PAUSE
        if (decision.confidence < confidenceThreshold) return GuardVerdict.ESCALATE_LOW_CONFIDENCE
        if (matchedKeyword(haystack, DESTRUCTIVE_KEYWORDS) != null) return GuardVerdict.DESTRUCTIVE
        if (unchangedStreak >= 3) return GuardVerdict.STUCK
        return GuardVerdict.PASS
    }
}
