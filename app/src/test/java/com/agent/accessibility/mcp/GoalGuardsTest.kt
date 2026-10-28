// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalGuardsTest {

    private fun decision(
        confidence: Double,
        text: String? = null,
        action: GoalAction = GoalAction.CLICK_NODE
    ): Decision {
        val spec = if (text != null) GoalActionSpec(action, "1", text) else null
        return Decision(action = spec, confidence = confidence, reason = "test")
    }

    private fun element(id: String, title: String?): SemanticElement {
        return SemanticElement(elementId = id, role = SemanticRole.ACTION, title = title)
    }

    @Test
    fun `pause wins over low confidence and destructive word`() {
        val target = element("1", "Buy now — delete all files")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.1),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.PAUSE, verdict)
    }

    @Test
    fun `destructive word with low confidence escalates instead of blocking`() {
        val target = element("2", "Delete file")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.1),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.ESCALATE_LOW_CONFIDENCE, verdict)
    }

    @Test
    fun `destructive word on visible title blocks`() {
        val target = element("3", "Delete photo")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.9),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.DESTRUCTIVE, verdict)
    }

    @Test
    fun `word inside elementId only does not block`() {
        val target = element("9:delete", "Settings")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.9),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.PASS, verdict)
    }

    @Test
    fun `unchanged streak of three is stuck`() {
        val target = element("4", "Settings")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.9),
            target = target,
            unchangedStreak = 3
        )
        assertEquals(GuardVerdict.STUCK, verdict)
    }

    @Test
    fun `unchanged streak of two still passes`() {
        val target = element("4", "Settings")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.9),
            target = target,
            unchangedStreak = 2
        )
        assertEquals(GuardVerdict.PASS, verdict)
    }

    @Test
    fun `confidence exactly at threshold passes`() {
        val target = element("5", "Settings")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.55),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.PASS, verdict)
    }

    @Test
    fun `confidence one tick under threshold escalates`() {
        val target = element("5", "Settings")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.5499),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.ESCALATE_LOW_CONFIDENCE, verdict)
    }

    @Test
    fun `pause keyword in typed text pauses`() {
        val target = element("6", "Message box")
        val verdict = GoalGuards.evaluate(
            pauseKeywords = GoalGuards.DEFAULT_PAUSE_KEYWORDS,
            confidenceThreshold = 0.55,
            decision = decision(0.9, text = "please publish this", action = GoalAction.INPUT_TEXT),
            target = target,
            unchangedStreak = 0
        )
        assertEquals(GuardVerdict.PAUSE, verdict)
    }

    @Test
    fun `word boundary prevents substring false positive`() {
        // "sender" must not trip the "send" pause keyword.
        assertNull(GoalGuards.matchedKeyword("sender settings", listOf("send")))
        assertEquals("send", GoalGuards.matchedKeyword("Send message", listOf("send")))
    }
}
