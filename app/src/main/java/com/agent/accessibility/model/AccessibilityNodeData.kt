package com.agent.accessibility.model

import android.graphics.Rect

data class AccessibilityNodeData(
    val nodeId: Int,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val viewIdResourceName: String?,
    val boundsInScreen: RectData,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val childCount: Int,
    val children: List<AccessibilityNodeData>
) {
    fun toPrettyString(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val shortClassName = className?.substringAfterLast('.') ?: "Unknown"
        sb.appendLine("$prefix[$nodeId] $shortClassName")

        if (!text.isNullOrBlank()) {
            sb.appendLine("$prefix    text=\"$text\"")
        }
        if (!contentDescription.isNullOrBlank()) {
            sb.appendLine("$prefix    description=\"$contentDescription\"")
        }
        if (viewIdResourceName != null) {
            sb.appendLine("$prefix    id=$viewIdResourceName")
        }
        sb.appendLine("$prefix    bounds=[${boundsInScreen.left},${boundsInScreen.top},${boundsInScreen.right},${boundsInScreen.bottom}]")

        val flags = mutableListOf<String>()
        if (clickable) flags.add("clickable")
        if (longClickable) flags.add("longClickable")
        if (scrollable) flags.add("scrollable")
        if (editable) flags.add("editable")
        if (focusable) flags.add("focusable")
        if (focused) flags.add("focused")
        if (!enabled) flags.add("disabled")
        if (selected) flags.add("selected")
        if (checked) flags.add("checked")

        if (flags.isNotEmpty()) {
            sb.appendLine("$prefix    flags=[${flags.joinToString(", ")}]")
        }

        children.forEach { child ->
            sb.append(child.toPrettyString(indent + 1))
        }

        return sb.toString()
    }

    fun flatten(): List<AccessibilityNodeData> {
        val result = mutableListOf(this)
        children.forEach { result.addAll(it.flatten()) }
        return result
    }

    fun findById(id: Int): AccessibilityNodeData? {
        if (nodeId == id) return this
        for (child in children) {
            val found = child.findById(id)
            if (found != null) return found
        }
        return null
    }
}

data class RectData(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    companion object {
        fun fromRect(rect: Rect): RectData {
            return RectData(rect.left, rect.top, rect.right, rect.bottom)
        }
    }
}

data class AccessibilityTreeData(
    val foregroundPackage: String?,
    val totalNodeCount: Int,
    val root: AccessibilityNodeData?,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toPrettyString(): String {
        if (root == null) return "No accessibility tree available"
        val sb = StringBuilder()
        sb.appendLine("Package: $foregroundPackage")
        sb.appendLine("Total nodes: $totalNodeCount")
        sb.appendLine("─".repeat(40))
        sb.append(root.toPrettyString())
        return sb.toString()
    }
}
