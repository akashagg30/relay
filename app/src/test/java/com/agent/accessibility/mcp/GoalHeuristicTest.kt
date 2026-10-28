// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalHeuristicTest {

    private fun el(
        id: String,
        role: SemanticRole,
        title: String? = null,
        enabled: Boolean = true,
        presentation: PresentationState = PresentationState.VISIBLE,
        checked: Boolean? = null,
        children: List<SemanticElement> = emptyList()
    ): SemanticElement {
        return SemanticElement(
            elementId = id,
            role = role,
            title = title,
            enabled = enabled,
            presentation = presentation,
            checked = checked,
            children = children
        )
    }

    private fun scene(vararg elements: SemanticElement): SemanticScene {
        return SemanticScene(
            app = "com.test.app",
            screenTitle = "Test",
            scrollable = false,
            elements = elements.toList()
        )
    }

    @Test
    fun `picks action wifi over header and unrelated action`() {
        val s = scene(
            el("h1", SemanticRole.HEADER, "Settings"),
            el("a1", SemanticRole.ACTION, "Network & internet"),
            el("a2", SemanticRole.ACTION, "Wi-Fi")
        )
        val d = HeuristicDecider.decide(s, "open wifi settings")
        assertNotNull(d.action)
        assertEquals(GoalAction.CLICK_NODE, d.action!!.action)
        assertEquals("a2", d.action!!.elementId)
        assertEquals("heuristic_click", d.reason)
    }

    @Test
    fun `turn on bluetooth with toggle off clicks the toggle`() {
        val s = scene(el("t1", SemanticRole.TOGGLE, "Bluetooth", checked = false))
        val d = HeuristicDecider.decide(s, "turn on bluetooth")
        assertNotNull(d.action)
        assertEquals(GoalAction.CLICK_NODE, d.action!!.action)
        assertEquals("t1", d.action!!.elementId)
    }

    @Test
    fun `turn on bluetooth with toggle already on is goal done`() {
        val s = scene(el("t1", SemanticRole.TOGGLE, "Bluetooth", checked = true))
        val d = HeuristicDecider.decide(s, "turn on bluetooth")
        assertNull(d.action)
        assertTrue(d.goalDone)
        assertEquals("already_set", d.reason)
        assertEquals(1.0, d.confidence, 0.0)
    }

    @Test
    fun `turn off bluetooth with toggle already off is goal done`() {
        val s = scene(el("t1", SemanticRole.TOGGLE, "Bluetooth", checked = false))
        val d = HeuristicDecider.decide(s, "turn off bluetooth")
        assertNull(d.action)
        assertTrue(d.goalDone)
        assertEquals("already_set", d.reason)
    }

    @Test
    fun `quoted literal on input produces input text`() {
        val s = scene(el("i1", SemanticRole.INPUT, "Search"))
        val d = HeuristicDecider.decide(s, "search for \"coffee\"")
        assertNotNull(d.action)
        assertEquals(GoalAction.INPUT_TEXT, d.action!!.action)
        assertEquals("i1", d.action!!.elementId)
        assertEquals("coffee", d.action!!.text)
        assertEquals("input_literal", d.reason)
    }

    @Test
    fun `input with no quoted literal refuses to invent text`() {
        val s = scene(el("i1", SemanticRole.INPUT, "Search"))
        val d = HeuristicDecider.decide(s, "search coffee nearby")
        assertNull(d.action)
        assertEquals("text_input_required", d.reason)
    }

    @Test
    fun `empty scene yields no candidate`() {
        val d = HeuristicDecider.decide(scene(), "open settings")
        assertNull(d.action)
        assertEquals("no_candidate", d.reason)
        assertEquals(0.0, d.confidence, 0.0)
    }

    @Test
    fun `perfect tie yields exactly 0_45 confidence and first in document order`() {
        val s = scene(
            el("a1", SemanticRole.ACTION, "Wi-Fi"),
            el("a2", SemanticRole.ACTION, "WiFi")
        )
        val d = HeuristicDecider.decide(s, "open wifi settings")
        assertNotNull(d.action)
        assertEquals("a1", d.action!!.elementId)
        assertEquals(0.45, d.confidence, 0.0)
    }

    @Test
    fun `offscreen and disabled elements are never selected`() {
        val s = scene(
            el("a1", SemanticRole.ACTION, "Bluetooth", presentation = PresentationState.OFFSCREEN),
            el("a2", SemanticRole.ACTION, "Bluetooth", enabled = false),
            el("a3", SemanticRole.ACTION, "Bluetooth")
        )
        val d = HeuristicDecider.decide(s, "open bluetooth")
        assertNotNull(d.action)
        assertEquals("a3", d.action!!.elementId)
    }

    @Test
    fun `only matching element offscreen yields no candidate`() {
        val s = scene(
            el("a1", SemanticRole.ACTION, "Bluetooth", presentation = PresentationState.OFFSCREEN)
        )
        val d = HeuristicDecider.decide(s, "open bluetooth")
        assertNull(d.action)
        assertEquals("no_candidate", d.reason)
    }

    @Test
    fun `goal tokens already on screen means goal done`() {
        val s = scene(el("v1", SemanticRole.VALUE, "Wi-Fi settings connected"))
        val d = HeuristicDecider.decide(s, "wifi settings")
        assertNull(d.action)
        assertTrue(d.goalDone)
        assertEquals("goal_text_present", d.reason)
        assertEquals(1.0, d.confidence, 0.0)
    }

    @Test
    fun `tokens drops stopwords and single chars`() {
        assertEquals(listOf("open", "wi", "fi", "settings"), HeuristicDecider.tokens("Open the Wi-Fi settings!"))
        assertEquals(listOf("search", "for", "coffee"), HeuristicDecider.tokens("search for \"coffee\""))
        assertEquals(emptyList<String>(), HeuristicDecider.tokens("a to on it"))
    }

    @Test
    fun `quoted literal extraction`() {
        assertEquals("coffee", HeuristicDecider.quotedLiteral("search for \"coffee\""))
        assertNull(HeuristicDecider.quotedLiteral("search coffee nearby"))
    }

    @Test
    fun `nested children are flattened in document order`() {
        val s = scene(
            el(
                "p1", SemanticRole.SECTION, "Section",
                children = listOf(el("c1", SemanticRole.ACTION, "Wi-Fi"))
            )
        )
        val ids = HeuristicDecider.candidates(s).map { it.elementId }
        assertEquals(listOf("c1"), ids)
    }
}
