// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.util.DisplayMetrics
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

// ── Shared constants ──────────────────────────────────────────────────────────
internal const val SCROLL_DURATION_MS = 300L
internal const val GESTURE_DURATION_MS = 100L
internal const val DEFAULT_SCROLL_RATIO = 3
internal const val MATCH_THRESHOLD = 1000
internal const val PREFIX_MATCH_SCORE = 900
internal const val CONTAINS_MATCH_SCORE = 800

// ── Shared helpers ────────────────────────────────────────────────────────────

/**
 * Error when no element id is supplied at all. Names the canonical field
 * and lists the accepted legacy aliases. Never reports the empty value.
 */
internal const val ELEMENT_ID_MISSING_MESSAGE =
    "missing required field 'id' (element id from observe, e.g. \"1:7\"; " +
        "legacy aliases 'elementId'/'nodeId' accepted)"

/**
 * First non-blank element id across the canonical key and its legacy
 * aliases, or null when none is present. `id` wins, then `elementId`,
 * then `nodeId`, so old clients keep working.
 */
internal fun parseElementIdRaw(args: JSONObject): String? {
    for (key in listOf("id", "elementId", "nodeId")) {
        val value = args.optString(key, "")
        if (value.isNotBlank()) return value
    }
    return null
}

/**
 * Parsed element ID from tool arguments.
 * Returns (elementId, errorResponse) — if errorResponse is non-null, the caller should return it.
 */
internal fun parseElementId(
    args: JSONObject,
    id: String,
    handler: McpHandler
): Pair<ElementId?, String?> {
    return when (val resolution = resolveElementId(args)) {
        is ElementIdResolution.Resolved -> resolution.elementId to null
        is ElementIdResolution.Rejected -> null to handler.toolErrorResponse(id, resolution.message)
    }
}

/**
 * Device-free resolution half of [parseElementId]: pure input in, resolved
 * id or the exact operator-facing rejection message out. This is the seam
 * tool-level JVM tests drive, so the wiring (canonical key + legacy aliases
 * + identical error text on every tool) is pinned without an Android Context.
 */
internal sealed class ElementIdResolution {
    data class Resolved(val elementId: ElementId) : ElementIdResolution()
    data class Rejected(val message: String) : ElementIdResolution()
}

internal fun resolveElementId(args: JSONObject): ElementIdResolution {
    val raw = parseElementIdRaw(args)
        ?: return ElementIdResolution.Rejected(ELEMENT_ID_MISSING_MESSAGE)
    val decoded = ElementId.decode(raw)
        ?: return ElementIdResolution.Rejected("invalid_element_id: \"$raw\" is not a valid element id")
    return ElementIdResolution.Resolved(decoded)
}

// ── Tool response framing (pure: no Android types) ───────────────────────────
//
// Every tool returns through these three builders, so tests can pin the
// JSON-RPC wire contract once instead of per tool. [McpHandler] delegates
// here; nothing in this section touches Android.

/** `result.content[0].text` = the tool payload, no error flag. */
internal fun toolSuccessJson(id: String, textContent: String): String {
    return JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "result",
            JSONObject().apply {
                put(
                    "content",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", textContent)
                        })
                    }
                )
            }
        )
    }.toString()
}

/** Tool-level failure: same envelope plus `isError=true`. */
internal fun toolErrorJson(id: String, message: String): String {
    return JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "result",
            JSONObject().apply {
                put(
                    "content",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", message)
                        })
                    }
                )
                put("isError", true)
            }
        )
    }.toString()
}

/** Protocol-level error object (JSON-RPC 2.0 codes: -32700, -32601, ...). */
internal fun protocolErrorJson(id: String, code: Int, message: String): String {
    return JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
    }.toString()
}

internal const val RPC_METHOD_NOT_FOUND = -32601
internal const val RPC_PARSE_ERROR = -32700


/**
 * Perform a scroll gesture in the given direction.
 */
internal fun performScroll(
    handler: McpHandler,
    direction: ScrollDirection,
    centerX: Int,
    centerY: Int,
    screenHeight: Int
): Boolean {
    val scrollDistance = screenHeight / DEFAULT_SCROLL_RATIO
    return when (direction) {
        ScrollDirection.FORWARD -> handler.dispatchScrollGesture(
            centerX, centerY, centerX, centerY - scrollDistance, SCROLL_DURATION_MS
        )
        ScrollDirection.BACKWARD -> handler.dispatchScrollGesture(
            centerX, centerY, centerX, centerY + scrollDistance, SCROLL_DURATION_MS
        )
        ScrollDirection.UNKNOWN -> false
    }
}
