// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData
import com.agent.accessibility.model.RectData
import org.json.JSONObject

/**
 * Loads a raw accessibility tree captured on-device via `get_screen_state`
 * from `src/test/resources/fixtures/`.
 *
 * The fixture format is exactly the `get_screen_state` response payload —
 * `{snapshotId, packageName, screenWidth, screenHeight, nodes:[{id, parentId?,
 * className, text?, contentDescription?, viewId?, bounds:[l,t,r,b],
 * clickable, longClickable, scrollable, editable, focusable, focused,
 * enabled, selected, checked, visibleToUser}]}` — so a fresh capture from
 * Executive can be committed verbatim without touching the harness. `id` and
 * `parentId` are element ids (`snapshotId:nodeId`); children keep file order.
 */
object RawTreeFixture {

    data class Loaded(
        val name: String,
        val tree: AccessibilityTreeData,
        val snapshotId: Long,
        val screenWidth: Int,
        val screenHeight: Int
    ) {
        fun rawNodes(): List<AccessibilityNodeData> = tree.root?.flatten() ?: emptyList()

        fun nodeById(nodeId: Int): AccessibilityNodeData? = tree.root?.findById(nodeId)

        /** Element id the projector is expected to use for a raw node. */
        fun elementIdOf(nodeId: Int): String = ElementId(snapshotId, nodeId).encode()
    }

    private class MutableNode(
        val nodeId: Int,
        val parentId: Int?,
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val viewId: String?,
        val bounds: RectData,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
        val selected: Boolean,
        val checked: Boolean,
        val visibleToUser: Boolean
    )

    fun load(name: String): Loaded {
        val stream = RawTreeFixture::class.java.classLoader
            ?.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found on test classpath: fixtures/$name")
        val text = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val snapshotId = json.optLong("snapshotId", 1L)
        val width = json.optInt("screenWidth", 1080)
        val height = json.optInt("screenHeight", 2400)

        val arr = json.getJSONArray("nodes")
        val byId = LinkedHashMap<Int, MutableNode>()
        val order = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val nodeId = o.getString("id").substringAfter(':').toInt()
            val parentId = if (o.has("parentId") && !o.isNull("parentId")) {
                o.getString("parentId").substringAfter(':').toInt()
            } else {
                null
            }
            val b = o.getJSONArray("bounds")
            byId[nodeId] = MutableNode(
                nodeId = nodeId,
                parentId = parentId,
                text = optStringOrNull(o, "text"),
                contentDescription = optStringOrNull(o, "contentDescription"),
                className = optStringOrNull(o, "className"),
                viewId = optStringOrNull(o, "viewId"),
                bounds = RectData(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)),
                clickable = o.optBoolean("clickable", false),
                longClickable = o.optBoolean("longClickable", false),
                scrollable = o.optBoolean("scrollable", false),
                editable = o.optBoolean("editable", false),
                focusable = o.optBoolean("focusable", false),
                focused = o.optBoolean("focused", false),
                enabled = o.optBoolean("enabled", true),
                selected = o.optBoolean("selected", false),
                checked = o.optBoolean("checked", false),
                visibleToUser = o.optBoolean("visibleToUser", true)
            )
            order.add(nodeId)
        }

        val childrenOf = HashMap<Int?, MutableList<Int>>()
        for (nodeId in order) {
            childrenOf.getOrPut(byId.getValue(nodeId).parentId) { mutableListOf() }.add(nodeId)
        }

        fun build(nodeId: Int): AccessibilityNodeData {
            val m = byId.getValue(nodeId)
            val children = (childrenOf[nodeId] ?: emptyList()).map { build(it) }
            return AccessibilityNodeData(
                nodeId = m.nodeId,
                text = m.text,
                contentDescription = m.contentDescription,
                className = m.className,
                packageName = json.optString("packageName", "unknown"),
                viewIdResourceName = m.viewId,
                boundsInScreen = m.bounds,
                clickable = m.clickable,
                longClickable = m.longClickable,
                scrollable = m.scrollable,
                editable = m.editable,
                focusable = m.focusable,
                focused = m.focused,
                enabled = m.enabled,
                selected = m.selected,
                checked = m.checked,
                visibleToUser = m.visibleToUser,
                childCount = children.size,
                children = children
            )
        }

        val roots = childrenOf[null] ?: error("fixture $name has no root node")
        val root: AccessibilityNodeData = when {
            roots.size == 1 -> build(roots[0])
            else -> AccessibilityNodeData(
                nodeId = -1,
                text = null,
                contentDescription = null,
                className = "android.widget.FrameLayout",
                packageName = json.optString("packageName", "unknown"),
                viewIdResourceName = null,
                boundsInScreen = RectData(0, 0, width, height),
                clickable = false,
                longClickable = false,
                scrollable = false,
                editable = false,
                focusable = false,
                focused = false,
                enabled = true,
                selected = false,
                checked = false,
                visibleToUser = true,
                childCount = roots.size,
                children = roots.map { build(it) }
            )
        }

        val tree = AccessibilityTreeData(
            foregroundPackage = json.optString("packageName", "unknown"),
            totalNodeCount = root.flatten().size,
            root = root,
            timestamp = 1793311583L
        )

        return Loaded(name, tree, snapshotId, width, height)
    }

    private fun optStringOrNull(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optString(key)
        return if (v.isEmpty()) null else v
    }
}
