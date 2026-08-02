package com.agent.accessibility.service

import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData
import com.agent.accessibility.model.RectData

object AccessibilityTreeReader {

    private var nodeIdCounter = 0

    fun resetNodeIdCounter() {
        nodeIdCounter = 0
    }

    fun readTree(root: AccessibilityNodeInfo?): AccessibilityTreeData {
        if (root == null) {
            return AccessibilityTreeData(
                foregroundPackage = null,
                totalNodeCount = 0,
                root = null
            )
        }

        resetNodeIdCounter()
        val nodeData = convertNode(root)
        val totalCount = nodeData?.flatten()?.size ?: 0

        return AccessibilityTreeData(
            foregroundPackage = root.packageName?.toString(),
            totalNodeCount = totalCount,
            root = nodeData
        )
    }

    @Suppress("DEPRECATION")
    private fun convertNode(node: AccessibilityNodeInfo): AccessibilityNodeData {
        val nodeId = nodeIdCounter++
        val isVisible = node.isVisibleToUser

        val bounds = RectData(0, 0, 0, 0)
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val boundsResult = RectData.fromRect(rect)

            val children = mutableListOf<AccessibilityNodeData>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    children.add(convertNode(child))
                    child.recycle()
                }
            }

            return AccessibilityNodeData(
                nodeId = nodeId,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                packageName = node.packageName?.toString(),
                viewIdResourceName = node.viewIdResourceName,
                boundsInScreen = boundsResult,
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                focusable = node.isFocusable,
                focused = node.isFocused,
                enabled = node.isEnabled,
                selected = node.isSelected,
                checked = node.isChecked,
                visibleToUser = isVisible,
                childCount = node.childCount,
                children = children
            )
        } catch (e: Exception) {
            return AccessibilityNodeData(
                nodeId = nodeId,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                packageName = node.packageName?.toString(),
                viewIdResourceName = node.viewIdResourceName,
                boundsInScreen = bounds,
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                focusable = node.isFocusable,
                focused = node.isFocused,
                enabled = node.isEnabled,
                selected = node.isSelected,
                checked = node.isChecked,
                visibleToUser = isVisible,
                childCount = node.childCount,
                children = emptyList()
            )
        }
    }
}
