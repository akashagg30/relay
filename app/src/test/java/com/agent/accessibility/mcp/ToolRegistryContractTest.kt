// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tool surface has three copies that must never drift:
 *
 *   1. `tools/list`      ([buildToolsArray])  — what clients see
 *   2. the dispatch set  ([HANDLED_TOOL_NAMES]) — what actually runs
 *   3. the README table  — what humans read
 *
 * README claimed 17 tools while the server served 28, and `get_session`
 * stayed callable after it left the advertised list. This test makes both
 * failures impossible to reintroduce: it is the binding the 1.0.113 delivery
 * was supposed to add.
 */
class ToolRegistryContractTest {

    private val expectedTools = listOf(
        "observe",
        "click_node",
        "swipe",
        "input_text",
        "press_key",
        "submit",
        "back",
        "home",
        "launch_app",
        "wait",
        "list_apps",
        "search_apps",
        "open_app",
        "current_app",
        "find",
        "scroll_until",
        "diag_sealed",
        "get_audit_log",
        "get_audit_summary",
        "set_log_sync",
        "get_log_sync",
        "set_sync_endpoint",
        "get_logs",
        "share_logs",
        "take_screenshot",
        "screenshot_with_overlay",
        "get_app_policy",
        "act_toward_goal"
    )

    private fun advertisedNames(): List<String> {
        val tools = buildToolsArray()
        return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
    }

    @Test
    fun `tools list advertises exactly the supported tool set`() {
        val names = advertisedNames()
        assertEquals("live tools/list count", 28, names.size)
        assertEquals("no duplicate tool names", names.size, names.toSet().size)
        assertEquals(expectedTools.toSet(), names.toSet())
    }

    @Test
    fun `every advertised tool documents itself and takes an object`() {
        val tools = buildToolsArray()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            val name = tool.getString("name")
            assertTrue("$name has no description", tool.getString("description").isNotBlank())
            val schema = tool.getJSONObject("inputSchema")
            assertEquals("$name inputSchema.type", "object", schema.getString("type"))
            assertTrue("$name inputSchema.properties", schema.has("properties"))
        }
    }

    @Test
    fun `advertised, dispatchable and compat alias surfaces agree`() {
        val advertised = advertisedNames().toSet()

        // Everything advertised must be dispatchable.
        assertTrue(
            "advertised but not implemented: ${advertised - HANDLED_TOOL_NAMES}",
            HANDLED_TOOL_NAMES.containsAll(advertised)
        )

        // Anything dispatchable but not advertised is a compat alias and must
        // be declared as one (this is the get_session failure mode).
        val unadvertised = HANDLED_TOOL_NAMES - advertised
        assertEquals(COMPAT_ALIAS_TOOL_NAMES, unadvertised)

        // ...and an alias must never be advertised again by accident.
        assertTrue((advertised intersect COMPAT_ALIAS_TOOL_NAMES).isEmpty())
    }

    @Test
    fun `removed tools are not silently callable`() {
        assertFalse("get_session was removed from Relay", "get_session" in HANDLED_TOOL_NAMES)
        val response = JSONObject(protocolErrorJson("1", RPC_METHOD_NOT_FOUND, "Method not found: x"))
        assertEquals(-32601, response.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `README states the live tool count and names every advertised tool`() {
        val readme = repoReadme().readText()

        assertTrue(
            "README must state the live tool count (28); found a stale count instead: " +
                Regex("[0-9]+ (essential )?tools").findAll(readme).map { it.value }.joinToString(),
            readme.contains("28 tools")
        )
        assertFalse(
            "README still advertises the old 17-tool surface",
            Regex("\\b17 (essential )?tools\\b").containsMatchIn(readme)
        )

        val undocumented = expectedTools.filter { !readme.contains("`$it`") }
        assertTrue("README does not name advertised tools: $undocumented", undocumented.isEmpty())

        // The compat alias is documented as deprecated, not as a live tool.
        assertTrue(readme.contains("`get_screen_state`"))
        assertTrue(readme.contains("legacy", ignoreCase = true))
    }

    private fun repoReadme(): File {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, "README.md")
            if (candidate.isFile && candidate.readText().contains("Relay")) return candidate
            dir = dir?.parentFile
        }
        error("README.md not found above ${File("").absolutePath}")
    }
}
