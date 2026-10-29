package com.agent.accessibility.mcp

import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData
import com.agent.accessibility.model.RectData
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * R-13: one `observe` contract. Every path emits exactly the canonical
 * top-level keys — app, screenTitle, scrollable, screenHash, elements,
 * screenState — and never the legacy raw-dump keys.
 */
class ObserveEnvelopeTest {

    private val canonicalKeys = setOf(
        "app", "screenTitle", "scrollable", "screenHash", "elements", "screenState"
    )
    private val legacyKeys = listOf(
        "packageName", "nodes", "screenWidth", "screenHeight", "timestamp", "snapshotId"
    )

    private fun makeNode(
        id: Int,
        text: String? = null,
        desc: String? = null,
        className: String? = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        checked: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 50,
        children: List<AccessibilityNodeData> = emptyList()
    ): AccessibilityNodeData {
        return AccessibilityNodeData(
            nodeId = id,
            text = text,
            contentDescription = desc,
            className = className,
            packageName = "com.test.app",
            viewIdResourceName = null,
            boundsInScreen = RectData(left, top, right, bottom),
            clickable = clickable,
            longClickable = false,
            scrollable = scrollable,
            editable = editable,
            focusable = true,
            focused = false,
            enabled = true,
            selected = selected,
            checked = checked,
            visibleToUser = true,
            childCount = children.size,
            children = children
        )
    }

    private fun makeTree(root: AccessibilityNodeData): AccessibilityTreeData {
        return AccessibilityTreeData(
            foregroundPackage = "com.test.app",
            totalNodeCount = 1 + root.children.size,
            root = root,
            timestamp = 12345L
        )
    }

    private fun keySet(json: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys.add(it.next())
        return keys
    }

    private fun titledScene(): SemanticScene {
        val root = makeNode(
            0,
            className = "android.widget.FrameLayout",
            children = listOf(
                makeNode(1, text = "Settings", className = "android.widget.Toolbar"),
                makeNode(2, text = "Wi-Fi", clickable = true)
            )
        )
        return SemanticProjector.project(makeTree(root), 100L, 1920)
    }

    @Test
    fun `envelope has exactly the canonical top-level keys`() {
        val json = observeEnvelope(titledScene(), ScreenState("stable", false, false))
        assertEquals(canonicalKeys, keySet(json))
        for (legacy in legacyKeys) {
            assertFalse("legacy key $legacy must never appear", json.has(legacy))
        }
        assertEquals(
            setOf("state", "keyboardVisible", "dialogVisible"),
            keySet(json.getJSONObject("screenState"))
        )
    }

    @Test
    fun `five consecutive envelope builds are identical`() {
        val scene = titledScene()
        val state = ScreenState("stable", keyboardVisible = false, dialogVisible = false)
        val firstJson = observeEnvelope(scene, state).toString()
        val firstKeys = keySet(observeEnvelope(scene, state))
        repeat(4) {
            val next = observeEnvelope(scene, state)
            assertEquals(firstKeys, keySet(next))
            assertEquals(firstJson, next.toString())
        }
    }

    @Test
    fun `sparse path produces the same key set as the normal path`() {
        // Degenerate bounds: strict projection drops this node (visible but
        // insane bounds), so the strict scene is empty while raw nodes exist.
        val root = makeNode(
            0,
            className = "android.widget.FrameLayout",
            children = listOf(
                makeNode(
                    1, text = "OK", clickable = true,
                    className = "android.widget.Button",
                    left = 0, top = 0, right = 0, bottom = 0
                )
            )
        )
        val tree = makeTree(root)
        val strict = SemanticProjector.project(tree, 7L, 1920)
        assertEquals(0, strict.totalDescendantCount())

        val sparse = SemanticProjector.projectSparse(tree, 7L, 1920)
        assertTrue("sparse scene must be non-empty", sparse.elements.isNotEmpty())

        val normalKeys = keySet(observeEnvelope(strict, ScreenState("stable", false, false)))
        val sparseKeys = keySet(observeEnvelope(sparse, ScreenState("stable", false, true)))
        assertEquals(canonicalKeys, normalKeys)
        assertEquals(normalKeys, sparseKeys)
        val sparseJson = observeEnvelope(sparse, ScreenState("stable", false, true))
        for (legacy in legacyKeys) {
            assertFalse("legacy key $legacy must never appear", sparseJson.has(legacy))
        }
        assertTrue(sparseJson.getJSONArray("elements").length() > 0)
    }

    @Test
    fun `titled and untitled scenes differ only in screenTitle value`() {
        val titled = titledScene()
        val untitledRoot = makeNode(
            0,
            className = "android.widget.FrameLayout",
            children = listOf(
                makeNode(1, text = "Fullscreen canvas", className = "android.view.SurfaceView")
            )
        )
        val untitled = SemanticProjector.project(makeTree(untitledRoot), 100L, 1920)
        assertEquals("Unknown", untitled.screenTitle)

        val a = observeEnvelope(titled, ScreenState("stable", false, false))
        val b = observeEnvelope(untitled, ScreenState("stable", false, false))
        assertEquals(keySet(a), keySet(b))
        assertNotEquals(a.getString("screenTitle"), b.getString("screenTitle"))
        assertEquals("Unknown", b.getString("screenTitle"))
    }
}
