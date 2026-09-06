package com.agent.accessibility.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool registry: builds the `tools/list` payload advertised over MCP.
 * One entry per supported tool, with JSON-Schema-style input definitions.
 */
internal fun McpHandler.buildToolsArray(): JSONArray {
    val tools = JSONArray()

    tools.put(toolDef("get_screen_state",
        "Get current foreground app accessibility tree as JSON. " +
        "Each node has an opaque `id` string. " +
        "Pass that exact id to click_node or input_text. " +
        "After screen transitions, call get_screen_state again to get fresh element ids.",
        JSONObject()))

    tools.put(toolDef("click_node",
        "Clicks an element returned by get_screen_state. " +
        "Pass the element's `id` exactly as returned. " +
        "Do not construct or modify the id. " +
        "The server handles re-resolution and scrolling when possible. " +
        "After navigation, call get_screen_state again before clicking.",
        JSONObject().apply {
            put("elementId", stringParam("Element id from get_screen_state (e.g. \"24:86\")"))
        }))

    tools.put(toolDef("tap", "Tap at screen coordinates", JSONObject().apply {
        put("x", intParam("X coordinate"))
        put("y", intParam("Y coordinate"))
    }))
    tools.put(toolDef("swipe", "Swipe between two points", JSONObject().apply {
        put("startX", intParam("Start X"))
        put("startY", intParam("Start Y"))
        put("endX", intParam("End X"))
        put("endY", intParam("End Y"))
        put("durationMs", intParam("Duration in ms"))
    }))
    tools.put(toolDef("input_text",
        "Sets text on an editable element returned by get_screen_state. " +
        "Pass the element's `id` exactly as returned.",
        JSONObject().apply {
            put("elementId", stringParam("Element id from get_screen_state (e.g. \"24:86\")"))
            put("text", stringParam("Text to input"))
        }))
    tools.put(toolDef("back", "Perform back action", JSONObject()))
    tools.put(toolDef("home", "Go to home screen", JSONObject()))
    tools.put(toolDef("launch_app", "Launch an app by package name", JSONObject().apply {
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
    tools.put(toolDef("observe",
        "Returns a semantic representation of the current screen. " +
        "Elements have roles (action, input, toggle, value, section, header, list, tab). " +
        "Each element has an id that works with click_node and input_text. " +
        "Use this instead of get_screen_state for cleaner reasoning.",
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
