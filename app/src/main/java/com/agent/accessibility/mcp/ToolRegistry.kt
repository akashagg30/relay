// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool registry: builds the `tools/list` payload advertised over MCP.
 * One entry per supported tool, with JSON-Schema-style input definitions.
 */
internal fun McpHandler.buildToolsArray(): JSONArray {
    val tools = JSONArray()

    tools.put(toolDef("observe",
        "Returns a semantic representation of the current screen. " +
        "Elements have roles (action, input, toggle, value, section, header, list, tab). " +
        "Each element has an id for click_node and input_text. " +
        "IMPORTANT: Element IDs change between calls. Use them immediately after observe(). " +
        "Don't cache IDs across multiple observe() calls.",
        JSONObject()))

    tools.put(toolDef("click_node",
        "Clicks an element returned by observe(). " +
        "Pass the element's `id` exactly as returned by observe(). " +
        "IMPORTANT: Always call observe() first to get fresh element IDs, then immediately click. " +
        "Element IDs change between observe() calls, so don't cache them. " +
        "Returns screenChanged=true if the click navigated to a different app.",
        JSONObject().apply {
            put("id", stringParam("Element id from observe (e.g. \"1:7\")"))
        }))

    tools.put(toolDef("swipe", "Swipe between two points", JSONObject().apply {
        put("startX", intParam("Start X"))
        put("startY", intParam("Start Y"))
        put("endX", intParam("End X"))
        put("endY", intParam("End Y"))
        put("durationMs", intParam("Duration in ms"))
    }))

    tools.put(toolDef("input_text",
        "Sets text on an editable element returned by observe(). " +
        "Pass the element's `id` exactly as returned by observe().",
        JSONObject().apply {
            put("id", stringParam("Element id from observe (e.g. \"1:7\")"))
            put("text", stringParam("Text to input"))
        }))

    tools.put(toolDef("press_key",
        "Press a keyboard key. Use after input_text to submit forms or trigger actions.",
        JSONObject().apply {
            put("key", stringParam("Key to press: enter, back, home, tab, etc."))
        }))

    tools.put(toolDef("submit",
        "Find and click the submit/search/go button on the current screen. " +
        "Use after typing in a search bar or form field. More reliable than press_key enter.",
        JSONObject()))

    tools.put(toolDef("back", "Perform back action", JSONObject()))
    tools.put(toolDef("home", "Go to home screen", JSONObject()))

    tools.put(toolDef("launch_app",
        "Launch an app by package name. Use open_app for human-readable names.",
        JSONObject().apply {
            put("packageName", stringParam("Android package name"))
        }))

    tools.put(toolDef("wait", "Wait for specified milliseconds", JSONObject().apply {
        put("milliseconds", intParam("Wait time in ms (max 10000)"))
    }))

    tools.put(toolDef("list_apps",
        "Returns launchable applications installed on the device.",
        JSONObject()))
    tools.put(toolDef("search_apps",
        "Search installed applications by name.",
        JSONObject().apply {
            put("query", stringParam("Search query (app name)"))
        }))
    tools.put(toolDef("open_app",
        "Open an installed application by its human-readable name. " +
        "If multiple apps match equally well, returns ambiguous_app instead of guessing.",
        JSONObject().apply {
            put("name", stringParam("Application name (e.g. Chrome, WhatsApp, Maps)"))
        }))



    tools.put(toolDef("current_app",
        "Returns the currently foreground application.",
        JSONObject()))

    tools.put(toolDef("find",
        "Search the current screen for an element matching text and/or role. " +
        "Returns the element if found, or found=false. " +
        "Uses the last observe() result - call observe() first if the screen may have changed.",
        JSONObject().apply {
            put("text", stringParam("Text to search for (case-insensitive substring match)"))
            put("role", stringParam("Element role to match: action, input, toggle, value, header, list, tab"))
        }))

    tools.put(toolDef("scroll_until",
        "Scroll the screen until an element with matching text is visible. " +
        "Relay owns the observe-scroll-observe loop. One call replaces multiple tool calls.",
        JSONObject().apply {
            put("text", stringParam("Text to search for while scrolling"))
            put("direction", stringParam("Scroll direction: down or up (default: down)"))
            put("maxScrolls", intParam("Maximum scroll attempts (default: 6)"))
        }))

    tools.put(toolDef("diag_sealed",
        "DIAGNOSTIC: Tests node sealed lifecycle. Walk tree, recycle all, re-walk, try performAction.",
        JSONObject().apply {
            put("elementId", stringParam("Element id from get_screen_state (e.g. \\\"24:86\\\")"))
        }))

    tools.put(toolDef("get_audit_log",
        "Get recent MCP tool call audit log. Shows success/failure, method used, timing. " +
        "Use for debugging tool reliability issues.",
        JSONObject().apply {
            put("limit", intParam("Number of recent entries (default 20, max 100)"))
        }))

    tools.put(toolDef("get_audit_summary",
        "Get aggregate tool usage statistics. Shows success rates, average timing, and methods used per tool.",
        JSONObject()))

    tools.put(toolDef("get_session",
        "Check if MCP is busy with another session. Returns current session info.",
        JSONObject()))

    tools.put(toolDef("set_log_sync",
        "Enable or disable cloud log sync. When enabled, logs are sent to the developer for debugging. " +
        "User must explicitly enable this. Logs are always stored locally regardless.",
        JSONObject().apply {
            put("enabled", JSONObject().apply {
                put("type", "boolean")
                put("description", "true to enable sync, false to disable")
            })
        }))

    tools.put(toolDef("get_log_sync",
        "Check if cloud log sync is enabled.",
        JSONObject()))

    tools.put(toolDef("set_sync_endpoint",
        "Set the cloud sync endpoint URL. Logs will be synced to this URL when sync is enabled.",
        JSONObject().apply {
            put("url", JSONObject().apply {
                put("type", "string")
                put("description", "Endpoint URL for log sync (e.g. http://100.x.x.x:8080/logs)")
            })
        }))

    tools.put(toolDef("get_logs",
        "Get recent log entries. Returns the last N tool calls with timestamps, success/failure, and timing.",
        JSONObject().apply {
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "Number of recent entries to return (default 20, max 100)")
            })
        }))

    tools.put(toolDef("share_logs",
        "Open Android share sheet to share logs as a text file. User can share via email, messaging, etc.",
        JSONObject()))

    tools.put(toolDef("take_screenshot",
        "Capture the current screen as a base64-encoded PNG image. " +
        "Returns the image data, dimensions, and format. " +
        "Use this to visually inspect what's on screen.",
        JSONObject()))

    tools.put(toolDef("screenshot_with_overlay",
        "Take a screenshot AND overlay bounding boxes on all interactive UI elements. " +
        "Shows element IDs, class names, text content, and clickable indicators. " +
        "Use this when you need VLM-style visual understanding of the screen with element annotations.",
        JSONObject()))

    return tools
}

private fun toolDef(name: String, description: String, inputSchema: JSONObject): JSONObject {
    return JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", inputSchema)
        })
    }
}

private fun intParam(description: String): JSONObject {
    return JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }
}

private fun stringParam(description: String): JSONObject {
    return JSONObject().apply {
        put("type", "string")
        put("description", description)
    }
}
