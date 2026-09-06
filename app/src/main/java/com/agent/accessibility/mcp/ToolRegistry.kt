package com.agent.accessibility.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool registry: builds the `tools/list` payload advertised over MCP.
 * Only essential tools — no redundant or deprecated ones.
 */
internal fun McpHandler.buildToolsArray(): JSONArray {
    val tools = JSONArray()

    // --- Screen Reading ---

    tools.put(toolDef("observe",
        "Returns a semantic representation of the current screen. " +
        "Elements have roles (action, input, toggle, value, section, header, list, tab). " +
        "Each element has an id that works with click_node and input_text. " +
        "Call this after every screen transition.",
        JSONObject()))

    tools.put(toolDef("find",
        "Search the current screen for an element matching text and/or role. " +
        "Returns the element if found, or found=false. " +
        "Uses the last observe() result — call observe() first if the screen may have changed.",
        JSONObject().apply {
            put("text", stringParam("Text to search for (case-insensitive substring match)"))
            put("role", stringParam("Element role to match: action, input, toggle, value, header, list, tab"))
        }))

    tools.put(toolDef("current_app",
        "Returns the currently foreground application.",
        JSONObject()))

    // --- Interaction ---

    tools.put(toolDef("click_node",
        "Clicks an element returned by observe(). " +
        "Pass the element's `id` exactly as returned. " +
        "After navigation, call observe() again before clicking.",
        JSONObject().apply {
            put("elementId", stringParam("Element id from observe (e.g. \"1:7\")"))
        }))

    tools.put(toolDef("input_text",
        "Sets text on an editable element returned by observe(). " +
        "Pass the element's `id` exactly as returned.",
        JSONObject().apply {
            put("elementId", stringParam("Element id from observe (e.g. \"1:7\")"))
            put("text", stringParam("Text to input"))
        }))

    tools.put(toolDef("swipe", "Swipe between two points", JSONObject().apply {
        put("startX", intParam("Start X"))
        put("startY", intParam("Start Y"))
        put("endX", intParam("End X"))
        put("endY", intParam("End Y"))
        put("durationMs", intParam("Duration in ms"))
    }))

    // --- Navigation ---

    tools.put(toolDef("back", "Perform back action", JSONObject()))
    tools.put(toolDef("home", "Go to home screen", JSONObject()))

    tools.put(toolDef("scroll_until",
        "Scroll the screen until an element with matching text is visible. " +
        "One call replaces multiple scroll attempts.",
        JSONObject().apply {
            put("text", stringParam("Text to search for while scrolling"))
            put("direction", stringParam("Scroll direction: down or up (default: down)"))
            put("maxScrolls", intParam("Maximum scroll attempts (default: 6)"))
        }))

    // --- App Management ---

    tools.put(toolDef("open_app",
        "Open an installed application by its human-readable name. " +
        "If multiple apps match equally well, returns ambiguous_app instead of guessing.",
        JSONObject().apply {
            put("name", stringParam("Application name (e.g. Chrome, WhatsApp, Maps)"))
        }))

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
