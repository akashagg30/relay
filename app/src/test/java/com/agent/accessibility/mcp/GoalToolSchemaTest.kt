// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalToolSchemaTest {

    @Test
    fun `tool schema exposes goal contract and escalate handoff`() {
        val parsed = MiniJson.parseObject(goalToolDefJson())

        assertEquals("act_toward_goal", parsed["name"])
        @Suppress("UNCHECKED_CAST")
        val inputSchema = parsed["inputSchema"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val props = inputSchema["properties"] as Map<String, Any?>
        for (key in listOf("goal", "maxSteps", "confidenceThreshold", "pauseBeforeKeywords")) {
            assertTrue("missing property $key", props.containsKey(key))
        }
        val description = parsed["description"] as String
        assertTrue(description.contains("escalate"))
        assertTrue(description.contains("done|escalate|paused|stuck|blocked|max_steps"))
    }

    @Test
    fun `result json matches the frontier contract shape`() {
        val result = GoalResult(
            status = GoalStatus.ESCALATE,
            engine = EngineTier.HEURISTIC,
            fallbackReason = "no_api_key",
            steps = listOf(
                StepRecord(1, "click_node", "1:7", null, 0.62, "changed",
                    "ab12cd34ef567890", "heuristic", null)
            ),
            elementIdsJson = elementsToJsonArray(listOf(
                SemanticElement(elementId = "2:3", role = SemanticRole.ACTION, title = "Wi-Fi")
            )),
            confidence = ConfidenceStats(0.55, 0.5, 0.7, 0.6, 2),
            reason = "no_candidate",
            elapsedMs = 1834,
            effectiveMaxSteps = 5
        )
        val parsed = MiniJson.parseObject(goalResultJson(result, "Open Wi-Fi"))

        assertEquals("escalate", parsed["status"])
        assertEquals("Open Wi-Fi", parsed["goal"])
        assertEquals("heuristic", parsed["engine"])
        assertEquals("no_api_key", parsed["fallbackReason"])
        assertEquals(5.0, parsed["effectiveMaxSteps"])
        assertEquals(1.0, parsed["stepsTaken"])
        @Suppress("UNCHECKED_CAST")
        val steps = parsed["steps"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val first = steps[0] as Map<String, Any?>
        for (key in listOf("index", "action", "elementId", "text", "confidence",
            "outcome", "stateHash", "engine", "guard")) {
            assertTrue("missing step key $key", first.containsKey(key))
        }
        @Suppress("UNCHECKED_CAST")
        val ids = parsed["elementIds"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val firstId = ids[0] as Map<String, Any?>
        assertEquals("2:3", firstId["id"])
        @Suppress("UNCHECKED_CAST")
        val confidence = parsed["confidence"] as Map<String, Any?>
        for (key in listOf("threshold", "min", "max", "mean", "count")) {
            assertTrue("missing confidence key $key", confidence.containsKey(key))
        }
        assertEquals("no_candidate", parsed["reason"])
    }

    @Test
    fun `non-escalate result carries empty element ids`() {
        val result = GoalResult(
            status = GoalStatus.DONE,
            engine = EngineTier.HEURISTIC,
            fallbackReason = null,
            steps = emptyList(),
            elementIdsJson = null,
            confidence = ConfidenceStats(0.55, 0.0, 0.0, 0.0, 0),
            reason = "goal_text_present",
            elapsedMs = 10,
            effectiveMaxSteps = 5
        )
        val parsed = MiniJson.parseObject(goalResultJson(result, "done goal"))

        @Suppress("UNCHECKED_CAST")
        val ids = parsed["elementIds"] as List<Any?>
        assertTrue(ids.isEmpty())
    }
}
