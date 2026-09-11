// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData
import com.agent.accessibility.model.RectData
import com.agent.accessibility.service.AgentAccessibilityService

data class ElementId(val snapshotId: Long, val nodeId: Int) {
    fun encode(): String = "$snapshotId:$nodeId"

    companion object {
        fun decode(raw: String): ElementId? {
            val colon = raw.indexOf(':')
            if (colon <= 0 || colon >= raw.length - 1) return null
            val snapshotId = raw.substring(0, colon).toLongOrNull() ?: return null
            val nodeId = raw.substring(colon + 1).toIntOrNull() ?: return null
            if (snapshotId < 0 || nodeId < 0) return null
            return ElementId(snapshotId, nodeId)
        }
    }
}

data class NodeDescriptor(
    val id: Int,
    val parentId: Int?,
    val packageName: String?,
    val viewIdResourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: List<Int>,
    val clickable: Boolean,
    val scrollable: Boolean,
    val semanticText: String? = null,
    val semanticDescription: String? = null
) {
    fun score(node: AccessibilityNodeInfo): Int {
        val nodePackage = node.packageName?.toString()
        val nodeViewId = node.viewIdResourceName
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        val nodeClass = node.className?.toString()

        var score = 0

        // Package match: REQUIRED if descriptor has a package (fail immediately if mismatched)
        // If descriptor package is null, skip package check (don't penalize)
        if (packageName != null && nodePackage != null && packageName != nodePackage) {
            return -1
        }
        if (packageName != null && nodePackage == null) return -1

        // viewIdResourceName: strong signal
        if (viewIdResourceName != null && nodeViewId != null) {
            if (viewIdResourceName == nodeViewId) {
                score += 50
            } else {
                return -1
            }
        } else if (viewIdResourceName != null || nodeViewId != null) {
            // One has viewId, other doesn't — weak mismatch, but don't reject
        }

        // text: strong signal (check direct text first, then semantic text)
        val effectiveText = text ?: semanticText
        if (effectiveText != null && nodeText != null) {
            if (effectiveText == nodeText) {
                score += 40
            } else {
                if (score >= 50) return -1
            }
        }

        // contentDescription: strong signal (check direct desc first, then semantic desc)
        val effectiveDesc = contentDescription ?: semanticDescription
        if (effectiveDesc != null && nodeDesc != null) {
            if (effectiveDesc == nodeDesc) {
                score += 40
            } else if (score >= 50 && effectiveText != null) {
                return -1
            }
        }

        // className: supporting signal
        if (className != null && nodeClass != null) {
            if (className == nodeClass) {
                score += 5
            }
        }

        // At least one strong attribute must match
        if (score < 5) return -1

        return score
    }
}

data class LogicallyResolved(
    val node: AccessibilityNodeInfo,
    val score: Int,
    val method: String,
    val clickableAncestor: AccessibilityNodeInfo?
)

data class FreshTraversalResult(
    val matchedNode: AccessibilityNodeInfo,
    val score: Int,
    val method: String,
    val clickableAncestor: AccessibilityNodeInfo?,
    val bounds: Rect,
    val isVisible: Boolean,
    val rootNode: AccessibilityNodeInfo
)

sealed class ResolutionResult {
    data class Resolved(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val method: String
    ) : ResolutionResult()

    data object StaleSnapshot : ResolutionResult()
    data object TargetNotFound : ResolutionResult()
    data object AmbiguousTarget : ResolutionResult()
    data object PackageMismatch : ResolutionResult()
}

data class ClickResult(
    val success: Boolean,
    val method: String,
    val scrollAttempts: Int = 0
)

class SnapshotManager {
    companion object {
        private const val MAX_SNAPSHOTS = 50
    }

    private data class SnapshotEntry(
        val snapshotId: Long,
        val descriptors: Map<Int, NodeDescriptor>,
        val foregroundPackage: String?,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val entries = ArrayDeque<SnapshotEntry>(MAX_SNAPSHOTS)
    private var nextSnapshotId = 1L

    @Synchronized
    fun store(descriptors: Map<Int, NodeDescriptor>, foregroundPackage: String?): Long {
        val id = nextSnapshotId++
        entries.addLast(SnapshotEntry(id, descriptors, foregroundPackage))
        while (entries.size > MAX_SNAPSHOTS) {
            entries.removeFirst()
        }
        return id
    }

    @Synchronized
    fun getDescriptor(snapshotId: Long, nodeId: Int): NodeDescriptor? {
        return entries.find { it.snapshotId == snapshotId }?.descriptors?.get(nodeId)
    }

    @Synchronized
    fun getSnapshotPackage(snapshotId: Long): String? {
        return entries.find { it.snapshotId == snapshotId }?.foregroundPackage
    }

    @Synchronized
    fun snapshotExists(snapshotId: Long): Boolean {
        return entries.any { it.snapshotId == snapshotId }
    }

    @Synchronized
    fun getLatestSnapshotId(): Long? {
        return entries.lastOrNull()?.snapshotId
    }
}

object NodeResolver {

    private const val TAG = "NodeResolver"
    private const val MAX_ANCESTOR_WALK = 10
    private const val MAX_SCROLL_ATTEMPTS = 5
    private const val AMBIGUITY_THRESHOLD = 15

    val snapshotManager = SnapshotManager()

    fun buildDescriptors(tree: AccessibilityTreeData): Map<Int, NodeDescriptor> {
        val map = mutableMapOf<Int, NodeDescriptor>()
        buildDescriptorMap(tree.root, null, map)
        return map
    }

    private fun buildDescriptorMap(
        node: AccessibilityNodeData?,
        parentId: Int?,
        map: MutableMap<Int, NodeDescriptor>
    ) {
        if (node == null) return

        var semanticText: String? = null
        var semanticDescription: String? = null

        if (node.clickable && node.text == null && node.contentDescription == null && node.viewIdResourceName == null) {
            val semantics = deriveSemanticText(node)
            semanticText = semantics.first
            semanticDescription = semantics.second
        }

        map[node.nodeId] = NodeDescriptor(
            id = node.nodeId,
            parentId = parentId,
            packageName = node.packageName,
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
            clickable = node.clickable,
            scrollable = node.scrollable,
            semanticText = semanticText,
            semanticDescription = semanticDescription
        )

        for (child in node.children) {
            buildDescriptorMap(child, node.nodeId, map)
        }
    }

    internal fun deriveSemanticText(node: AccessibilityNodeData): Pair<String?, String?> {
        var title: String? = null
        var summary: String? = null
        deriveFromDescendants(node, maxDepth = 5, stopAtClickable = true) { child ->
            if (title == null && child.viewIdResourceName == "android:id/title" && !child.text.isNullOrBlank()) {
                title = child.text
            }
            if (summary == null && child.viewIdResourceName == "android:id/summary" && !child.text.isNullOrBlank()) {
                summary = child.text
            }
        }
        return Pair(title, summary)
    }

    private fun deriveFromDescendants(
        node: AccessibilityNodeData,
        maxDepth: Int,
        stopAtClickable: Boolean,
        visitor: (AccessibilityNodeData) -> Unit
    ) {
        if (maxDepth <= 0) return
        for (child in node.children) {
            if (stopAtClickable && child.clickable) {
                // Don't cross into independently-clickable subtrees.
                // But if this child has viewId/title/summary, we can still read it
                // because it's inside our container.
                // We only stop at children that are themselves clickable containers
                // that would have their own semantic enrichment.
                if (child.text == null && child.contentDescription == null && child.viewIdResourceName == null) {
                    continue
                }
            }
            visitor(child)
            deriveFromDescendants(child, maxDepth - 1, stopAtClickable, visitor)
        }
    }

    fun resolveNode(
        root: AccessibilityNodeInfo?,
        snapshotId: Long,
        nodeId: Int
    ): ResolutionResult {
        if (root == null) return ResolutionResult.TargetNotFound

        val descriptor = snapshotManager.getDescriptor(snapshotId, nodeId)
            ?: return ResolutionResult.StaleSnapshot

        return resolveWithScore(root, descriptor)
    }

    fun resolveFresh(
        service: AgentAccessibilityService,
        snapshotId: Long,
        nodeId: Int
    ): FreshTraversalResult? {
        val root = service.rootInActiveWindow ?: return null
        val descriptor = snapshotManager.getDescriptor(snapshotId, nodeId) ?: return null

        val liveNodes = mutableListOf<AccessibilityNodeInfo>()
        var bestNodeIndex = -1
        var bestScore = 0

        fun traverse(node: AccessibilityNodeInfo) {
            val score = descriptor.score(node)
            if (score > bestScore) {
                bestScore = score
                bestNodeIndex = liveNodes.size
            }
            liveNodes.add(node)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
            }
        }

        try {
            traverse(root)
        } catch (e: Exception) {
            Log.e(TAG, "resolveFresh traversal failed", e)
            for (n in liveNodes) {
                try { n.recycle() } catch (_: Exception) {}
            }
            root.recycle()
            return null
        }

        if (bestScore <= 0 || bestNodeIndex < 0) {
            for (n in liveNodes) {
                try { n.recycle() } catch (_: Exception) {}
            }
            return null
        }

        val matchedNode = liveNodes[bestNodeIndex]

        val logical = getLogicalActionableTarget(matchedNode, descriptor)
        val isVisible = isLogicalTargetVisible(matchedNode, logical.clickableAncestor)
        val bounds = Rect()
        (logical.clickableAncestor ?: matchedNode).getBoundsInScreen(bounds)

        val ancestorToKeep = logical.clickableAncestor
        for (i in liveNodes.indices) {
            if (i != bestNodeIndex && liveNodes[i] !== ancestorToKeep) {
                try { liveNodes[i].recycle() } catch (_: Exception) {}
            }
        }

        return FreshTraversalResult(
            matchedNode = matchedNode,
            score = bestScore,
            method = "single_match",
            clickableAncestor = ancestorToKeep,
            bounds = bounds,
            isVisible = isVisible,
            rootNode = root
        )
    }

    fun recycleAll(result: FreshTraversalResult) {
        try {
            result.clickableAncestor?.recycle()
        } catch (_: Exception) {}
        try {
            result.matchedNode.recycle()
        } catch (_: Exception) {}
        try {
            result.rootNode.recycle()
        } catch (_: Exception) {}
    }

    private fun collectCandidatesLive(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor
    ): MutableList<Triple<AccessibilityNodeInfo, Int, String>> {
        val results = mutableListOf<Triple<AccessibilityNodeInfo, Int, String>>()
        collectCandidatesLiveRecursive(node, descriptor, results)
        return results
    }

    private fun collectCandidatesLiveRecursive(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor,
        results: MutableList<Triple<AccessibilityNodeInfo, Int, String>>
    ) {
        val score = descriptor.score(node)
        if (score > 0) {
            results.add(Triple(node, score, "single_match"))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCandidatesLiveRecursive(child, descriptor, results)
            child.recycle()
        }
    }

    fun performActionOnFreshNode(
        service: AgentAccessibilityService,
        snapshotId: Long,
        nodeId: Int,
        action: Int,
        bundle: android.os.Bundle? = null
    ): Boolean {
        val result = resolveFresh(service, snapshotId, nodeId) ?: return false
        return try {
            val actionTarget = result.clickableAncestor ?: result.matchedNode
            val success = if (bundle != null) {
                actionTarget.performAction(action, bundle)
            } else {
                actionTarget.performAction(action)
            }
            recycleAll(result)
            success
        } catch (e: Exception) {
            Log.w(TAG, "performActionOnFreshNode failed", e)
            recycleAll(result)
            false
        }
    }

    private fun resolveWithScore(
        root: AccessibilityNodeInfo,
        descriptor: NodeDescriptor
    ): ResolutionResult {
        val candidates = collectCandidates(root, descriptor)

        if (candidates.isEmpty()) {
            return ResolutionResult.TargetNotFound
        }

        if (candidates.size == 1) {
            val (node, score) = candidates[0]
            return ResolutionResult.Resolved(node, score, "single_match")
        }

        val sorted = candidates.sortedByDescending { it.second }
        val best = sorted[0]
        val secondBest = sorted[1]

        if (best.second - secondBest.second >= AMBIGUITY_THRESHOLD) {
            return ResolutionResult.Resolved(best.first, best.second, "best_match")
        }

        Log.w(TAG, "Ambiguous resolution: best=${best.second} second=${secondBest.second} " +
                "for viewId=${descriptor.viewIdResourceName} text=${descriptor.text}")
        return ResolutionResult.AmbiguousTarget
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor
    ): List<Pair<AccessibilityNodeInfo, Int>> {
        val results = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectCandidatesRecursive(node, descriptor, results)
        return results
    }

    private fun collectCandidatesRecursive(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor,
        results: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        val score = descriptor.score(node)
        if (score > 0) {
            results.add(Pair(node, score))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCandidatesRecursive(child, descriptor, results)
            child.recycle()
        }
    }

    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < MAX_ANCESTOR_WALK) {
            if (parent.isClickable) return parent
            val next = parent.parent
            parent = next
            depth++
        }
        return null
    }

    fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < MAX_ANCESTOR_WALK) {
            if (parent.isScrollable) return parent
            val next = parent.parent
            parent = next
            depth++
        }
        return null
    }

    fun collectCandidatesPublic(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor,
        results: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        collectCandidatesRecursive(node, descriptor, results)
    }

    fun isSaneBounds(rect: Rect): Boolean {
        return isSaneBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    fun isSaneBounds(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        return right > left && bottom > top
    }

    fun getLogicalActionableTarget(
        node: AccessibilityNodeInfo,
        descriptor: NodeDescriptor
    ): LogicallyResolved {
        if (node.isClickable) {
            return LogicallyResolved(node, 0, "directly_clickable", null)
        }

        val ancestor = findClickableAncestor(node)
        if (ancestor != null) {
            return LogicallyResolved(ancestor, 0, "clickable_ancestor", ancestor)
        }

        return LogicallyResolved(node, 0, "no_clickable_ancestor", null)
    }

    fun isLogicalTargetVisible(
        node: AccessibilityNodeInfo,
        ancestor: AccessibilityNodeInfo?
    ): Boolean {
        val primaryTarget = ancestor ?: node
        val primaryVisible = primaryTarget.isVisibleToUser
        val primaryBounds = Rect()
        primaryTarget.getBoundsInScreen(primaryBounds)
        val primarySane = isSaneBounds(primaryBounds)

        if (primaryVisible && primarySane) {
            return true
        }

        if (node !== primaryTarget) {
            val nodeVisible = node.isVisibleToUser
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            val nodeSane = isSaneBounds(nodeBounds)

            if (nodeVisible && nodeSane) {
                return true
            }
        }

        return false
    }

    fun isLogicalTargetVisibleByBounds(
        isVisibleToUser: Boolean,
        bounds: List<Int>,
        childVisibleToUser: Boolean = false,
        childBounds: List<Int>? = null
    ): Boolean {
        if (bounds.size != 4) return false
        val sane = isSaneBounds(bounds[0], bounds[1], bounds[2], bounds[3])
        if (isVisibleToUser && sane) return true

        if (childBounds != null && childBounds.size == 4) {
            val childSane = isSaneBounds(childBounds[0], childBounds[1], childBounds[2], childBounds[3])
            if (childVisibleToUser && childSane) return true
        }

        return false
    }

    fun determineScrollDirection(
        node: AccessibilityNodeInfo,
        ancestor: AccessibilityNodeInfo?,
        viewportTop: Int,
        viewportBottom: Int
    ): ScrollDirection {
        val target = ancestor ?: node
        val bounds = Rect()
        target.getBoundsInScreen(bounds)

        // For degenerate bounds (scrolled off-screen), use centerY for direction
        val centerY = if (isSaneBounds(bounds)) {
            bounds.centerY()
        } else {
            (bounds.top + bounds.bottom) / 2
        }

        if (centerY <= viewportTop) {
            return ScrollDirection.BACKWARD
        }
        if (centerY >= viewportBottom) {
            return ScrollDirection.FORWARD
        }

        return ScrollDirection.UNKNOWN
    }

    fun determineScrollDirectionByBounds(
        bounds: List<Int>,
        viewportTop: Int,
        viewportBottom: Int
    ): ScrollDirection {
        if (bounds.size != 4) return ScrollDirection.UNKNOWN
        val (left, top, right, bottom) = bounds
        // For degenerate bounds, use centerY for direction
        val centerY = if (isSaneBounds(left, top, right, bottom)) {
            (top + bottom) / 2
        } else {
            (top + bottom) / 2
        }
        if (centerY <= viewportTop) return ScrollDirection.BACKWARD
        if (centerY >= viewportBottom) return ScrollDirection.FORWARD
        return ScrollDirection.UNKNOWN
    }
}

enum class ScrollDirection {
    FORWARD,
    BACKWARD,
    UNKNOWN
}
