// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-14 wiring, at the tool boundary.
 *
 * The element id path is shared by every tool that takes an id (click_node,
 * input_text, diag_sealed, ...). These tests drive the same resolution seam
 * the tools call and then assert the exact JSON-RPC frame the client sees, so
 * a missing id fails identically everywhere instead of each tool inventing
 * its own message.
 */
class ParseElementIdWiringTest {

    private fun rejected(args: JSONObject): String {
        val resolution = resolveElementId(args)
        assertTrue(
            "expected a rejection for ${args}, got $resolution",
            resolution is ElementIdResolution.Rejected
        )
        return (resolution as ElementIdResolution.Rejected).message
    }

    @Test
    fun `missing id is the canonical error and is framed as a tool error`() {
        val message = rejected(JSONObject())
        assertEquals(ELEMENT_ID_MISSING_MESSAGE, message)

        val frame = JSONObject(toolErrorJson("7", message))
        assertEquals("2.0", frame.getString("jsonrpc"))
        assertEquals("7", frame.getString("id"))
        val result = frame.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        val content = result.getJSONArray("content").getJSONObject(0)
        assertEquals("text", content.getString("type"))
        assertEquals(message, content.getString("text"))
        assertNull(frame.optJSONObject("error"))
    }

    @Test
    fun `invalid id names the offending value so the caller can fix it`() {
        val message = rejected(JSONObject().put("id", "12"))
        assertTrue(message.startsWith("invalid_element_id:"))
        assertTrue("message must quote the raw value: $message", message.contains("\"12\""))

        // Same shape for an alias, so legacy clients get the same diagnostic.
        assertTrue(rejected(JSONObject().put("elementId", "1:")).contains("\"1:\""))
    }

    @Test
    fun `valid id resolves to the snapshot and node it encodes`() {
        val resolution = resolveElementId(JSONObject().put("id", "24:86"))
        assertTrue(resolution is ElementIdResolution.Resolved)
        assertEquals(ElementId(24, 86), (resolution as ElementIdResolution.Resolved).elementId)
    }

    @Test
    fun `legacy aliases resolve exactly like the canonical key`() {
        val expected = resolveElementId(JSONObject().put("id", "3:11"))
        for (alias in listOf("elementId", "nodeId")) {
            assertEquals(
                "alias '$alias' must resolve identically",
                expected,
                resolveElementId(JSONObject().put(alias, "3:11"))
            )
        }
    }

    @Test
    fun `id wins over aliases and blank values fall through`() {
        val resolution = resolveElementId(
            JSONObject().put("id", "1:7").put("elementId", "2:9").put("nodeId", "3:11")
        )
        assertEquals(ElementId(1, 7), (resolution as ElementIdResolution.Resolved).elementId)

        assertEquals(
            ElementId(3, 11),
            (
                resolveElementId(
                    JSONObject().put("id", "   ").put("nodeId", "3:11")
                ) as ElementIdResolution.Resolved
                ).elementId
        )
    }
}
