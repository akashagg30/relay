package com.agent.accessibility.mcp

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData

data class NodeDescriptor(
    val id: Int,
    val parentId: Int?,
    val viewIdResourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: List<Int>,
    val clickable: Boolean
) {
    fun matches(node: AccessibilityNodeInfo): Boolean {
        val nodeViewId = node.viewIdResourceName
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        val nodeClass = node.className?.toString()

        if (viewIdResourceName != null && nodeViewId != null && viewIdResourceName == nodeViewId) {
            return true
        }

        if (text != null && nodeText != null && text == nodeText &&
            contentDescription == nodeDesc &&
            className == nodeClass
        ) {
            return true
        }

        if (contentDescription != null && nodeDesc != null && contentDescription == nodeDesc &&
            className == nodeClass
        ) {
            return true
        }

        return false
    }
}

object NodeResolver {

    private const val TAG = "NodeResolver"

    private var lastDescriptors: Map<Int, NodeDescriptor> = emptyMap()

    fun updateFromTree(tree: AccessibilityTreeData) {
        val flat = tree.root?.flatten() ?: emptyList()
        val descriptorMap = mutableMapOf<Int, NodeDescriptor>()

        buildDescriptorMap(tree.root, null, descriptorMap)
        lastDescriptors = descriptorMap
    }

    private fun buildDescriptorMap(
        node: AccessibilityNodeData?,
        parentId: Int?,
        map: MutableMap<Int, NodeDescriptor>
    ) {
        if (node == null) return

        map[node.nodeId] = NodeDescriptor(
            id = node.nodeId,
            parentId = parentId,
            viewIdResourceName = node.viewIdResourceName,
            text = node.text,
            contentDescription = node.contentDescription,
            className = node.className,
            bounds = listOf(
                node.boundsInScreen.left,
                node.boundsInScreen.top,
                node.boundsInScreen.right,
                node.boundsInScreen.bottom
            ),
            clickable = node.clickable
        )

        for (child in node.children) {
            buildDescriptorMap(child, node.nodeId, map)
        }
    }

    fun resolveNode(root: AccessibilityNodeInfo?, nodeId: Int): AccessibilityNodeInfo? {
        if (root == null) return null

        val descriptor = lastDescriptors[nodeId]
        if (descriptor == null) {
            Log.w(TAG, "No descriptor for node $nodeId")
            return null
        }

        val found = findMatchingNode(root, descriptor)
        if (found != null) {
            Log.d(TAG, "Resolved node $nodeId via descriptor match")
            return found
        }

        Log.w(TAG, "Could not resolve node $nodeId - stale node")
        return null
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor
    ): AccessibilityNodeInfo? {
        if (descriptor.matches(node)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findMatchingNode(child, descriptor)
            if (result != null) return result
            child.recycle()
        }

        return null
    }

    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            val next = parent.parent
            parent = next
        }
        return null
    }

    fun findEditableNode(root: AccessibilityNodeInfo?, nodeId: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        return resolveNode(root, nodeId)
    }
}
