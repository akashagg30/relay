// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalDecisionLogTest {

    private class MemorySink : DecisionSink {
        val lines = mutableListOf<String>()
        override fun append(line: String) {
            lines.add(line)
        }
    }

    private fun recordSample(log: GoalDecisionLog, hash: String): String {
        log.record(
            goalHash = hash,
            screenHash = "abcdef1234567890",
            step = 3,
            engine = "heuristic",
            elementId = "1:7",
            action = "click_node",
            confidence = 0.62,
            probabilities = mapOf("1:7" to 0.62),
            guard = null,
            outcome = "changed",
            status = "continued"
        )
        return hash
    }

    @Test
    fun `one line per record with required keys and hashed goal`() {
        val sink = MemorySink()
        val log = GoalDecisionLog(sink) { 1700000000000L }
        val goal = "my secret wifi password flow"
        val hash = goalHash(goal)
        recordSample(log, hash)

        assertEquals(1, sink.lines.size)
        val line = sink.lines[0]
        assertFalse(line.contains("\n"))
        assertFalse(line.contains(goal))
        val parsed = MiniJson.parseObject(line)
        for (key in listOf("ts", "goalHash", "screenHash", "step", "engine", "elementId",
            "action", "confidence", "probabilities", "guard", "outcome", "status")) {
            assertTrue("missing key $key", parsed.containsKey(key))
        }
        assertTrue(hash.matches(Regex("[0-9a-f]{16}")))
        assertEquals(hash, parsed["goalHash"])
        assertEquals(1700000000000L, (parsed["ts"] as Double).toLong())
        assertEquals(3.0, parsed["step"])
        assertEquals(0.62, parsed["confidence"])
    }

    @Test
    fun `writing past maxBytes rotates to goal_decisions_1_jsonl`() {
        val dir = Files.createTempDirectory("goalog").toFile()
        try {
            val sink = FileDecisionSink(dir, maxBytes = 100)
            val log = GoalDecisionLog(sink) { 1L }
            val hash = goalHash("rotate me")
            log.record(hash, "aa", 1, "heuristic", "1:1", "click_node", 0.9,
                null, null, "changed", "continued")
            log.record(hash, "bb", 2, "heuristic", "1:2", "click_node", 0.9,
                null, null, "changed", "continued")

            val rotated = java.io.File(dir, FileDecisionSink.ROTATED_NAME)
            val current = java.io.File(dir, FileDecisionSink.FILE_NAME)
            assertTrue(rotated.exists())
            assertTrue(current.exists())
            assertEquals(1, current.readLines().size)
            assertEquals(1, rotated.readLines().size)
            // Rotated content is still valid JSONL.
            MiniJson.parseObject(rotated.readLines()[0])
        } finally {
            dir.deleteRecursively()
        }
    }
}
