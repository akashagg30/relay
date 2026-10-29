// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A raw-tree node the projection could not represent faithfully (R-15).
 *
 * Surfaced to callers as `treeIncomplete` so a gap is never silent: either the
 * raw accessibility tree itself lacked the information (no rect, merged
 * semantics) or the node was actionable but exposed no text/description/viewId
 * to name it with.
 */
data class TreeGap(
    val elementId: String?,
    val nodeId: Int,
    val reason: String,
    val text: String? = null,
    val className: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (elementId != null) put("id", elementId)
        put("nodeId", nodeId)
        put("reason", reason)
        if (!text.isNullOrBlank()) put("text", text)
        if (!className.isNullOrBlank()) put("className", className)
    }

    companion object {
        const val DEGENERATE_BOUNDS = "degenerate_bounds"
        const val ACTIONABLE_WITHOUT_IDENTITY = "actionable_without_identity"
        const val FOCUSABLE_WITHOUT_IDENTITY = "focusable_without_identity"
    }
}

data class SemanticScene(
    val app: String?,
    val screenTitle: String?,
    val scrollable: Boolean,
    val elements: List<SemanticElement>,
    val treeIncomplete: List<TreeGap> = emptyList()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("app", app ?: "unknown")
        json.put("screenTitle", screenTitle ?: "unknown")
        json.put("scrollable", scrollable)
        json.put("screenHash", computeScreenHash())
        val arr = JSONArray()
        for (el in elements) {
            arr.put(el.toJson())
        }
        json.put("elements", arr)
        return json.toString()
    }

    fun computeScreenHash(): String {
        val sb = StringBuilder()
        sb.append(app ?: "")
        sb.append("|")
        sb.append(screenTitle ?: "")
        sb.append("|")
        appendElementHash(sb, elements)
        return sha256(sb.toString()).take(16)
    }

    private fun normalizeForHash(text: String?): String {
        if (text == null) return ""
        return text
            .replace(Regex("\\d{1,2}:\\d{2}"), "TIME")
            .replace(Regex("\\d{4}-\\d{2}-\\d{2}"), "DATE")
            .replace(Regex("\\d+ (seconds?|minutes?|hours?|days?) ago"), "RELTIME")
            .trim()
    }

    private fun appendElementHash(sb: StringBuilder, elements: List<SemanticElement>) {
        for (el in elements) {
            sb.append(el.role.value)
            sb.append("|")
            sb.append(normalizeForHash(el.title))
            sb.append("|")
            sb.append(normalizeForHash(el.summary))
            sb.append("|")
            sb.append(normalizeForHash(el.value))
            sb.append("|")
            sb.append(el.checked)
            sb.append("|")
            sb.append(el.selected)
            sb.append("|")
            sb.append(el.focused)
            sb.append("|")
            if (el.children.isNotEmpty()) {
                appendElementHash(sb, el.children)
            }
            sb.append(")")
        }
    }

    fun sizeBytes(): Int = toJson().length

    fun elementCount(): Int = elements.size

    fun totalDescendantCount(): Int {
        var count = 0
        for (el in elements) {
            count += 1 + el.children.size
        }
        return count
    }

    /** Every element in the scene, depth-first, including nested children. */
    fun flatten(): List<SemanticElement> {
        val out = mutableListOf<SemanticElement>()
        fun walk(list: List<SemanticElement>) {
            for (el in list) {
                out.add(el)
                walk(el.children)
            }
        }
        walk(elements)
        return out
    }
}

private fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

data class SemanticElement(
    val elementId: String,
    val role: SemanticRole,
    val title: String? = null,
    val summary: String? = null,
    val value: String? = null,
    val placeholder: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val focused: Boolean? = null,
    val scrollable: Boolean = false,
    val presentation: PresentationState = PresentationState.VISIBLE,
    val bounds: BoundsHint? = null,
    /**
     * Android resource id (`viewIdResourceName`) of the source node, e.g.
     * `com.android.vending:id/title`. Stable across snapshots, unlike the
     * per-snapshot element id, so it is the best long-lived handle a caller
     * has. R-15: emitted whenever the source node has one.
     */
    val viewId: String? = null,
    /**
     * Element ids of raw nodes folded into this one (a label supplying a
     * button's title, a text child folded into a summary). R-15: the
     * projection never drops a node without recording its id here, so
     * "retained ids ∪ mergedFrom" covers everything the projection decided
     * to represent.
     */
    val mergedFrom: List<String> = emptyList(),
    val children: List<SemanticElement> = emptyList()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", elementId)
        json.put("role", role.value)
        if (title != null) json.put("title", title)
        if (summary != null) json.put("summary", summary)
        if (value != null) json.put("value", value)
        if (placeholder != null) json.put("placeholder", placeholder)
        if (viewId != null) json.put("viewId", viewId)
        if (!enabled) json.put("enabled", false)
        if (checked != null) json.put("checked", checked)
        if (selected != null) json.put("selected", selected)
        if (focused != null) json.put("focused", focused)
        if (scrollable) json.put("scrollable", true)
        if (presentation != PresentationState.VISIBLE) json.put("presentation", presentation.value)
        if (bounds != null) json.put("bounds", bounds.toJson())
        if (mergedFrom.isNotEmpty()) {
            val arr = JSONArray()
            for (id in mergedFrom) arr.put(id)
            json.put("mergedFrom", arr)
        }
        if (children.isNotEmpty()) {
            val arr = JSONArray()
            for (child in children) {
                arr.put(child.toJson())
            }
            json.put("children", arr)
        }
        return json
    }
}

enum class SemanticRole(val value: String) {
    HEADER("header"),
    SECTION("section"),
    ACTION("action"),
    INPUT("input"),
    TOGGLE("toggle"),
    VALUE("value"),
    LABEL("label"),
    IMAGE("image"),
    LIST("list"),
    TAB("tab"),
    MENU("menu"),
    PLAYER("player")
}

enum class PresentationState(val value: String) {
    VISIBLE("visible"),
    OFFSCREEN("offscreen"),
    HIDDEN("hidden")
}

/**
 * Element bounds.
 *
 * Carries BOTH a coarse semantic hint and the real pixel rect.
 *
 * The hint is a readability aid for callers reasoning about layout in words; the
 * pixel rect is what actually makes coordinate-based fallback correct. Only the
 * hint was retained originally, which meant coordinate_fallback had to reverse-
 * engineer a guess from words like "left"/"top" and routinely missed the target
 * while still reporting success.
 */
data class BoundsHint(
    val position: String,
    val vertical: String,
    val left: Int = -1,
    val top: Int = -1,
    val right: Int = -1,
    val bottom: Int = -1
) {
    /** True when the real pixel rect is known and usable. */
    val hasPixels: Boolean get() = left >= 0 && top >= 0 && right > left && bottom > top

    /** Centre point of the real rect, for direct use as a tap target. */
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("position", position)
            put("vertical", vertical)
            if (hasPixels) {
                put("x", left)
                put("y", top)
                put("width", right - left)
                put("height", bottom - top)
            }
        }
    }

    companion object {
        fun fromBounds(left: Int, top: Int, right: Int, bottom: Int, screenH: Int, screenW: Int): BoundsHint {
            val position = when {
                screenW <= 0 -> "center"
                left < screenW * 0.1 -> "left"
                right > screenW * 0.9 -> "right"
                else -> "center"
            }
            val vertical = when {
                screenH <= 0 -> "middle"
                top < screenH * 0.2 -> "top"
                bottom > screenH * 0.8 -> "bottom"
                else -> "middle"
            }
            return BoundsHint(position, vertical, left, top, right, bottom)
        }
    }
}
