package com.agent.accessibility.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SemanticScene(
    val app: String?,
    val screenTitle: String?,
    val scrollable: Boolean,
    val elements: List<SemanticElement>
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
        if (!enabled) json.put("enabled", false)
        if (checked != null) json.put("checked", checked)
        if (selected != null) json.put("selected", selected)
        if (focused != null) json.put("focused", focused)
        if (scrollable) json.put("scrollable", true)
        if (presentation != PresentationState.VISIBLE) json.put("presentation", presentation.value)
        if (bounds != null) json.put("bounds", bounds.toJson())
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

data class BoundsHint(
    val position: String,
    val vertical: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("position", position)
            put("vertical", vertical)
        }
    }

    companion object {
        fun fromBounds(left: Int, top: Int, right: Int, bottom: Int, screenH: Int): BoundsHint {
            val position = when {
                left < 100 -> "left"
                right > 900 -> "right"
                else -> "center"
            }
            val vertical = when {
                screenH <= 0 -> "middle"
                top < screenH * 0.2 -> "top"
                bottom > screenH * 0.8 -> "bottom"
                else -> "middle"
            }
            return BoundsHint(position, vertical)
        }
    }
}
