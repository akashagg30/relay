package com.agent.accessibility.mcp

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.service.AgentAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * R-1 coordinate fix (card 1 of 3): the `click_node` coordinate fallback must
 * tap the MATCHED node's own rect centre — clamped to the clickable
 * ancestor's rect — and must not subtract the status-bar height.
 *
 * Rect note: android.graphics.Rect methods/ctors are stubs under JVM unit
 * tests, so rects are built by field assignment and assertions read fields
 * or the pure Pair<Int, Int> tap points, never Rect methods.
 */
class R1ClickFallbackTest {

    // Row from the on-device repro: x:44 y:1254 w:992 h:154 -> centre (540,1331),
    // the exact point swipe{540,1331 -> 544,1335} proved hits.
    private fun rowRect(): Rect = rect(44, 1254, 1036, 1408)

    // Clickable container around the row: centre (540,1068) — the OLD tap point.
    private fun containerRect(): Rect = rect(0, 269, 1080, 1867)

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect {
        val out = Rect()
        out.left = l
        out.top = t
        out.right = r
        out.bottom = b
        return out
    }

    private fun stubBounds(node: AccessibilityNodeInfo, l: Int, t: Int, r: Int, b: Int) {
        `when`(node.getBoundsInScreen(any(Rect::class.java))).thenAnswer { inv ->
            val out = inv.getArgument<Rect>(0)
            out.left = l
            out.top = t
            out.right = r
            out.bottom = b
            null
        }
    }

    @Test
    fun `fallback tap uses matched row centre, not clickable container centre`() {
        val tapRect = NodeResolver.resolveTapRect(rowRect(), containerRect())
        val (gx, gy) = gesturePointFromBounds(tapRect)
        // Row centre — the point swipe proved hits.
        assertEquals(540, gx)
        assertEquals(1331, gy)
        // The container centre (540,1068) is where the old code tapped.
        val containerCentre = Pair(
            (containerRect().left + containerRect().right) / 2,
            (containerRect().top + containerRect().bottom) / 2
        )
        assertEquals(Pair(540, 1068), containerCentre)
        assertFalse(
            "tap must not land on the container centre $containerCentre",
            gx == containerCentre.first && gy == containerCentre.second
        )
    }

    @Test
    fun `gesture conversion is screen-absolute, no status-bar subtraction`() {
        // 1080x2400 screen, row x:44 y:1254 w:992 h:154 -> exactly (540,1331).
        assertEquals(Pair(540, 1331), gesturePointFromBounds(rowRect()))
    }

    @Test
    fun `tap rect clamps a protruding row into the ancestor`() {
        val protruding = rect(44, 1800, 1036, 1950)
        val tapRect = NodeResolver.resolveTapRect(protruding, containerRect())
        // Intersection is (44,1800,1036,1867) -> centre (540,1833).
        assertEquals(Pair(540, 1833), gesturePointFromBounds(tapRect))
    }

    @Test
    fun `disjoint ancestor falls back to the matched row centre`() {
        val tapRect = NodeResolver.resolveTapRect(rowRect(), rect(0, 0, 100, 100))
        assertEquals(Pair(540, 1331), gesturePointFromBounds(tapRect))
    }

    @Test
    fun `null ancestor keeps the matched row rect`() {
        val tapRect = NodeResolver.resolveTapRect(rowRect(), null)
        assertEquals(44, tapRect.left)
        assertEquals(1254, tapRect.top)
        assertEquals(1036, tapRect.right)
        assertEquals(1408, tapRect.bottom)
        assertEquals(Pair(540, 1331), gesturePointFromBounds(tapRect))
    }

    @Test
    fun `resolveFresh exposes matched targetBounds alongside ancestor bounds`() {
        val pkg = "com.example.app"
        val root = mock(AccessibilityNodeInfo::class.java)
        val container = mock(AccessibilityNodeInfo::class.java)
        val row = mock(AccessibilityNodeInfo::class.java)

        `when`(root.childCount).thenReturn(1)
        `when`(root.getChild(0)).thenReturn(container)
        `when`(container.childCount).thenReturn(1)
        `when`(container.getChild(0)).thenReturn(row)
        `when`(row.childCount).thenReturn(0)
        `when`(row.getChild(0)).thenReturn(null)
        `when`(row.parent).thenReturn(container)
        `when`(container.parent).thenReturn(root)

        for (node in listOf(root, container, row)) {
            `when`(node.packageName).thenReturn(pkg)
            `when`(node.isVisibleToUser).thenReturn(true)
        }
        `when`(container.isClickable).thenReturn(true)
        `when`(row.isClickable).thenReturn(false)
        `when`(row.text).thenReturn("Open Experience")
        `when`(row.className).thenReturn("android.view.ViewGroup")

        stubBounds(container, 0, 269, 1080, 1867)
        stubBounds(row, 44, 1254, 1036, 1408)

        val service = mock(AgentAccessibilityService::class.java)
        `when`(service.rootInActiveWindow).thenReturn(root)

        val descriptor = NodeDescriptor(
            id = 7,
            parentId = null,
            packageName = pkg,
            viewIdResourceName = null,
            text = "Open Experience",
            contentDescription = null,
            className = "android.view.ViewGroup",
            bounds = listOf(44, 1254, 1036, 1408),
            clickable = false,
            scrollable = false
        )
        val snapshotId = NodeResolver.snapshotManager.store(mapOf(7 to descriptor), pkg)

        val result = NodeResolver.resolveFresh(service, snapshotId, 7)
        assertNotNull("resolveFresh must resolve the live row", result)
        result!!

        // Ancestor (container) bounds preserved for clamp/visibility use.
        assertEquals(0, result.bounds.left)
        assertEquals(269, result.bounds.top)
        assertEquals(1080, result.bounds.right)
        assertEquals(1867, result.bounds.bottom)

        // Matched node's own bounds carried separately.
        assertEquals(44, result.targetBounds.left)
        assertEquals(1254, result.targetBounds.top)
        assertEquals(1036, result.targetBounds.right)
        assertEquals(1408, result.targetBounds.bottom)

        // The fallback tap point is the ROW centre, not the container centre.
        val tap = NodeResolver.resolveTapRect(result.targetBounds, result.bounds)
        assertEquals(Pair(540, 1331), gesturePointFromBounds(tap))

        NodeResolver.recycleAll(result)
    }
}
