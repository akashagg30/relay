// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import com.agent.accessibility.model.AccessibilityNodeData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-15 — raw-tree vs projection diff harness (the founder's goal: the
 * accessibility tree must give ALL the info, so a caller never needs vision).
 *
 * Every fixture under `src/test/resources/fixtures/` is a raw accessibility
 * tree in `get_screen_state` shape, captured from a screen where `observe`
 * used to lose information. The harness projects each fixture and asserts
 * that the projection is *lossless* for everything a caller could act on:
 *
 *  - every actionable raw node survives with its id, role and pixel rect;
 *  - every visible raw text is reachable through `find`'s matcher;
 *  - every retained element keeps the source node's viewId;
 *  - nothing is dropped without a record (`mergedFrom` or `treeIncomplete`);
 *  - every retained visible element has usable bounds;
 *  - the observe envelope keeps the 1.0.113 top-level contract.
 *
 * A regression in any of those fails the test with the offending node named.
 */
class TreeCompletenessTest {

    private val fixtures = listOf(
        "play_results_claude_search.json",
        "play_home.json",
        "wikipedia_article.json",
        "miui_settings.json"
    )

    private val canonicalKeys = setOf(
        "app", "screenTitle", "scrollable", "screenHash", "elements", "screenState"
    )

    private fun isActionable(node: AccessibilityNodeData): Boolean =
        node.clickable || node.scrollable || node.editable || node.longClickable

    private fun hasIdentity(node: AccessibilityNodeData): Boolean =
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
            !node.viewIdResourceName.isNullOrBlank()

    private fun hasSaneBounds(node: AccessibilityNodeData): Boolean =
        node.boundsInScreen.right > node.boundsInScreen.left &&
            node.boundsInScreen.bottom > node.boundsInScreen.top

    private fun project(name: String): Pair<RawTreeFixture.Loaded, SemanticScene> {
        val fixture = RawTreeFixture.load(name)
        val scene = SemanticProjector.project(
            fixture.tree, fixture.snapshotId, fixture.screenHeight, fixture.screenWidth
        )
        return fixture to scene
    }

    private fun retainedIds(scene: SemanticScene): Set<String> =
        scene.flatten().map { it.elementId }.toSet()

    private fun absorbedIds(scene: SemanticScene): Set<String> =
        scene.flatten().flatMap { it.mergedFrom }.toSet()

    private fun keySet(json: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys.add(it.next())
        return keys
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `every actionable raw node survives projection with id role and rect`() {
        for (name in fixtures) {
            val (fixture, scene) = project(name)
            val retained = retainedIds(scene)
            val absorbed = absorbedIds(scene)
            val elementsById = scene.flatten().associateBy { it.elementId }

            for (node in fixture.rawNodes()) {
                if (!isActionable(node)) continue
                if (!node.visibleToUser) continue
                if (!hasSaneBounds(node)) continue

                val id = fixture.elementIdOf(node.nodeId)
                val where = "$name node ${node.nodeId} (${node.className} text=${node.text})"

                assertTrue(
                    "actionable node $where disappeared from the projection " +
                        "and is not recorded in mergedFrom",
                    id in retained || id in absorbed
                )

                val el = elementsById[id] ?: continue // absorbed: recorded, not a target
                assertNotNull("element $id has no role", el.role)
                assertNotNull("element $id ($where) has no bounds", el.bounds)
                assertTrue(
                    "element $id ($where) has no usable pixel rect",
                    el.bounds!!.hasPixels
                )
                assertTrue(
                    "element $id ($where) is not interactive-ish",
                    el.role == SemanticRole.ACTION || el.role == SemanticRole.TOGGLE ||
                        el.role == SemanticRole.TAB || el.role == SemanticRole.INPUT ||
                        el.role == SemanticRole.LIST || el.role == SemanticRole.MENU ||
                        el.role == SemanticRole.PLAYER || el.scrollable
                )
            }
        }
    }

    @Test
    fun `every visible raw text is reachable through the find matcher`() {
        for (name in fixtures) {
            val (fixture, scene) = project(name)
            val elements = scene.flatten()
            for (node in fixture.rawNodes()) {
                val text = node.text
                if (text.isNullOrBlank()) continue
                if (!node.visibleToUser) continue
                if (!hasSaneBounds(node)) continue

                val hits = elements.filter { scoreElement(it, text, "") >= 0 }
                assertTrue(
                    "$name: raw text \"$text\" (node ${node.nodeId}) is not findable in the " +
                        "projection — this is the Play-Store row-title failure class",
                    hits.isNotEmpty()
                )
            }
        }
    }

    @Test
    fun `no node with identity or an action is dropped without a record`() {
        for (name in fixtures) {
            val (fixture, scene) = project(name)
            val accounted = retainedIds(scene) + absorbedIds(scene) +
                scene.treeIncomplete.mapNotNull { it.elementId }.toSet()

            for (node in fixture.rawNodes()) {
                if (!hasIdentity(node) && !isActionable(node)) continue
                val id = fixture.elementIdOf(node.nodeId)
                assertTrue(
                    "$name: node ${node.nodeId} (${node.className} text=${node.text} " +
                        "desc=${node.contentDescription} viewId=${node.viewIdResourceName} " +
                        "actionable=${isActionable(node)}) vanished with no record",
                    id in accounted
                )
            }
        }
    }

    @Test
    fun `every retained visible element has a usable pixel rect`() {
        for (name in fixtures) {
            val (_, scene) = project(name)
            for (el in scene.flatten()) {
                if (el.presentation != PresentationState.VISIBLE) continue
                assertNotNull("$name: element ${el.elementId} is visible but has no bounds", el.bounds)
                assertTrue(
                    "$name: element ${el.elementId} (${el.title}) has a degenerate rect",
                    el.bounds!!.hasPixels
                )
            }
        }
    }

    @Test
    fun `every retained element keeps the source node viewId`() {
        for (name in fixtures) {
            val (fixture, scene) = project(name)
            for (el in scene.flatten()) {
                val nodeId = el.elementId.substringAfter(':').toIntOrNull() ?: continue
                val node = fixture.nodeById(nodeId) ?: continue
                if (node.viewIdResourceName == null) continue
                assertEquals(
                    "$name: element ${el.elementId} dropped the raw viewId",
                    node.viewIdResourceName,
                    el.viewId
                )
            }
        }
    }

    @Test
    fun `play results expose the row titles with their own rects`() {
        val (_, scene) = project("play_results_claude_search.json")
        val elements = scene.flatten()

        val claude = elements.firstOrNull { it.title == "Claude by Anthropic" }
        assertNotNull(
            "Play results: 'Claude by Anthropic' is not a title in the projection " +
                "(titles=${elements.mapNotNull { it.title }})",
            claude
        )
        assertEquals("1:6", claude!!.elementId)
        assertTrue("row title lost its rect", claude.bounds!!.hasPixels)
        assertEquals(440, claude.bounds!!.top)
        assertEquals(510, claude.bounds!!.bottom)

        val kimi = elements.firstOrNull { it.title == "Kimi - Your AI Assistant" }
        assertNotNull("Play results: Kimi row title missing", kimi)
        assertEquals("1:13", kimi!!.elementId)
        assertTrue(kimi.bounds!!.hasPixels)

        // The two Install buttons and the More-options buttons are still there,
        // and the bottom-nav Search tab is addressable by name.
        assertEquals(
            "expected both Install buttons",
            2,
            elements.count { it.title == "Install" }
        )
        val searchTab = elements.firstOrNull { it.title == "Search" }
        assertNotNull("bottom-nav Search tab missing", searchTab)
        assertTrue(searchTab!!.bounds!!.hasPixels)
        // The bottom-nav Search tab must be tappable at its own centre — this
        // is the node the replay has to hit without vision.
        assertEquals(2340, (searchTab.bounds!!.top + searchTab.bounds!!.bottom) / 2)
    }

    @Test
    fun `play home degenerate host does not erase its subtree`() {
        val (fixture, scene) = project("play_home.json")
        val titles = scene.flatten().mapNotNull { it.title }

        assertTrue(
            "R-5 regression: the 0x0 host node took its whole subtree with it " +
                "(only ${titles.size} elements: $titles)",
            scene.flatten().size >= 10
        )
        for (expected in listOf("For you", "Top charts", "New & updated", "Featured app", "Editors' choice", "Search")) {
            assertTrue("play home lost \"$expected\" (got $titles)", expected in titles)
        }

        val hostGap = scene.treeIncomplete.firstOrNull { it.nodeId == 2 }
        assertNotNull("the 0x0 host node must be reported, not silently skipped", hostGap)
        assertEquals(TreeGap.DEGENERATE_BOUNDS, hostGap!!.reason)
    }

    @Test
    fun `treeIncomplete is typed bounded and never silent`() {
        val knownReasons = setOf(
            TreeGap.DEGENERATE_BOUNDS,
            TreeGap.ACTIONABLE_WITHOUT_IDENTITY,
            TreeGap.FOCUSABLE_WITHOUT_IDENTITY
        )
        for (name in fixtures) {
            val (_, scene) = project(name)
            assertTrue(
                "$name: treeIncomplete has more than ${SemanticProjector.MAX_GAPS} entries",
                scene.treeIncomplete.size <= SemanticProjector.MAX_GAPS
            )
            for (gap in scene.treeIncomplete) {
                assertTrue("$name: unknown gap reason ${gap.reason}", gap.reason in knownReasons)
                assertTrue("$name: gap without nodeId", gap.nodeId >= 0)
            }
            val json = observeEnvelope(scene, ScreenState("stable", false, false))
            if (scene.treeIncomplete.isEmpty()) {
                assertEquals("$name: a complete tree must keep the exact 1.0.113 keys", canonicalKeys, keySet(json))
            } else {
                assertEquals(
                    "$name: an incomplete tree must say so and add nothing else",
                    canonicalKeys + "treeIncomplete",
                    keySet(json)
                )
                assertTrue(json.getJSONArray("treeIncomplete").length() > 0)
            }
        }
    }

    @Test
    fun `every fixture produces a non-empty scene with the canonical envelope`() {
        for (name in fixtures) {
            val (_, scene) = project(name)
            assertTrue("$name: projection is empty", scene.flatten().isNotEmpty())
            val json = observeEnvelope(scene, ScreenState("stable", false, false))
            assertTrue(
                "$name: envelope must contain the canonical keys, got ${keySet(json)}",
                keySet(json).containsAll(canonicalKeys)
            )
            assertTrue(json.getJSONArray("elements").length() > 0)
        }
    }

    @Test
    fun `find searches viewId and element id not just title`() {
        val (_, scene) = project("miui_settings.json")
        val elements = scene.flatten()

        val byViewId = elements.filter { scoreElement(it, "switch_widget", "") >= 0 }
        assertTrue("find must match a viewId resource name", byViewId.isNotEmpty())
        assertTrue(
            "viewId match must resolve to the switch element",
            byViewId.any { it.viewId?.endsWith("switch_widget") == true }
        )

        val byElementId = elements.filter { scoreElement(it, "1:16", "") >= 0 }
        assertTrue("find must match an element id", byElementId.isNotEmpty())

        val byRole = elements.filter { scoreElement(it, "", "toggle") >= 0 }
        assertTrue("find must filter by role", byRole.isNotEmpty())
    }

    @Test
    fun `find prefers an exact title over a substring match elsewhere`() {
        val (_, scene) = project("play_results_claude_search.json")
        val elements = scene.flatten()
        val ranked = elements
            .map { it to scoreElement(it, "Install", "") }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
        assertTrue(ranked.isNotEmpty())
        assertEquals("Install", ranked.first().first.title)
    }
}
