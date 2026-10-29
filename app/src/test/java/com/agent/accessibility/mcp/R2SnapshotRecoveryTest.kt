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
 * R-2 (1.0.115, post-slice of R-1): the fresh traversal resolved the WINDOW
 * ROOT for Play Store bottom-nav Search, so 1.0.114 honestly refused every
 * tap (`degenerate_bounds`, zero gestures) and no Play Store flow worked.
 *
 * Two fixes, both covered here with a Play-Store-shaped fake tree (the root
 * carries text "Search" with full-screen bounds; the real nav node at
 * 432,2180 216x176 carries the same token):
 *
 *  1. RECOVERY — when the live rect is degenerate but the descriptor rect
 *     from the snapshot the id came from is sane, tap the descriptor rect
 *     (clamped to the screen) ONLY when the screen dimensions and the
 *     foreground package are unchanged since the snapshot. The tap reports
 *     `boundsSource: snapshot_rect`; delivery still needs the tap point
 *     inside the target rect plus a hash change.
 *  2. SCORING — `resolveFresh` must not pick a degenerate (screen-sized)
 *     node when a smaller node carries the same semantic tokens: the
 *     smallest same-score rect wins, traversal order breaking ties. This is
 *     what makes recovery eventually unnecessary.
 *
 * Rect note (same as R1TargetAnchorTest): Rect methods are stubs under JVM
 * unit tests — rects are built by field assignment, assertions read fields.
 */
class R2SnapshotRecoveryTest {

    private val screenW = 1080
    private val screenH = 2400
    private val pkg = "com.android.vending"

    // Play Store bottom-nav Search, as `observe` printed it: 432,2180 216x176.
    private fun navSearchRect(): Rect = rect(432, 2180, 648, 2356)

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

    private fun sameScreenContext(): SnapshotTapContext = SnapshotTapContext(
        snapshotScreenWidth = screenW,
        snapshotScreenHeight = screenH,
        snapshotPackage = pkg,
        currentPackage = pkg
    )

    /**
     * The exact on-device shape: `observe` minted the id on the 216x176 nav
     * element, but the fresh traversal scored the window root (full screen).
     * The row scores nothing, so the root wins alone — no tie involved.
     */
    private fun rootMatchTraversal(): Pair<FreshTraversalResult, NodeDescriptor> {
        val root = mock(AccessibilityNodeInfo::class.java)
        val row = mock(AccessibilityNodeInfo::class.java)

        `when`(root.childCount).thenReturn(1)
        `when`(root.getChild(0)).thenReturn(row)
        `when`(row.childCount).thenReturn(0)
        `when`(row.parent).thenReturn(root)
        `when`(root.parent).thenReturn(null)

        for (node in listOf(root, row)) {
            `when`(node.packageName).thenReturn(pkg)
            `when`(node.isVisibleToUser).thenReturn(true)
            `when`(node.isClickable).thenReturn(false)
        }
        `when`(root.text).thenReturn("Search")
        `when`(root.contentDescription).thenReturn("Search")
        `when`(root.className).thenReturn("android.widget.FrameLayout")

        stubBounds(root, 0, 0, screenW, screenH)
        stubBounds(row, 0, 0, screenW, screenH)

        val service = mock(AgentAccessibilityService::class.java)
        `when`(service.rootInActiveWindow).thenReturn(root)

        val descriptor = NodeDescriptor(
            id = 7,
            parentId = null,
            packageName = pkg,
            viewIdResourceName = null,
            text = "Search",
            contentDescription = null,
            className = "android.widget.FrameLayout",
            bounds = listOf(432, 2180, 648, 2356),
            clickable = true,
            scrollable = false
        )
        val snapshotId = NodeResolver.snapshotManager.store(mapOf(7 to descriptor), pkg, screenW, screenH)
        val result = NodeResolver.resolveFresh(service, snapshotId, 7)
        assertNotNull("resolveFresh must resolve the (wrong, full-screen) node", result)
        assertEquals(0, result!!.targetBounds.left)
        assertEquals(screenW, result.targetBounds.right)
        assertEquals(screenH, result.targetBounds.bottom)
        return Pair(result, descriptor)
    }

    /**
     * A tie tree: the root AND the 216x176 nav node carry the same token
     * ("Search", no view id, no class), so both score 40 and traversal order
     * alone used to hand the win to the root.
     */
    private fun tieTreeService(): AgentAccessibilityService {
        val root = mock(AccessibilityNodeInfo::class.java)
        val nav = mock(AccessibilityNodeInfo::class.java)

        `when`(root.childCount).thenReturn(1)
        `when`(root.getChild(0)).thenReturn(nav)
        `when`(nav.childCount).thenReturn(0)

        for (node in listOf(root, nav)) {
            `when`(node.packageName).thenReturn(pkg)
            `when`(node.isVisibleToUser).thenReturn(true)
        }
        `when`(root.isClickable).thenReturn(false)
        `when`(nav.isClickable).thenReturn(true)
        `when`(root.text).thenReturn("Search")
        `when`(nav.text).thenReturn("Search")
        `when`(root.className).thenReturn("android.widget.FrameLayout")
        `when`(nav.className).thenReturn("android.widget.Button")

        stubBounds(root, 0, 0, screenW, screenH)
        stubBounds(nav, 432, 2180, 648, 2356)

        val service = mock(AgentAccessibilityService::class.java)
        `when`(service.rootInActiveWindow).thenReturn(root)
        return service
    }

    private fun tieDescriptor(): NodeDescriptor = NodeDescriptor(
        id = 7,
        parentId = null,
        packageName = pkg,
        viewIdResourceName = null,
        text = "Search",
        contentDescription = null,
        className = null,
        bounds = listOf(432, 2180, 648, 2356),
        clickable = true,
        scrollable = false
    )

    // ---------------------------------------------------------------------
    // AC 1 — root match + sane descriptor + same screen/package ⇒ tap the
    // snapshot rect ONCE, inside 432,2180,648,2356, boundsSource snapshot_rect.
    // ---------------------------------------------------------------------

    @Test
    fun `degenerate live rect with an unchanged screen recovers a snapshot_rect tap inside the target`() {
        val (traversal, descriptor) = rootMatchTraversal()
        try {
            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = descriptorBoundsRect(descriptor),
                screenWidth = screenW,
                screenHeight = screenH,
                snapshotContext = sameScreenContext()
            )
            assertTrue("an unchanged screen must recover the tap, got ${plan::class.simpleName}", plan is TapPlan.Tap)
            plan as TapPlan.Tap
            assertEquals(BOUNDS_SOURCE_SNAPSHOT_RECT, plan.boundsSource)
            assertEquals("432,2180,648,2356", fallbackRectToString(plan.tapRect))
            assertEquals("432,2180,648,2356", fallbackRectToString(plan.targetBounds))
            // Centre of the 216x176 nav pill — the tap observe/find asked for.
            assertEquals(540, plan.x)
            assertEquals(2268, plan.y)
            assertTrue(plan.x in 432..648 && plan.y in 2180..2356)
            assertTrue("a snapshot_rect tap is inside its own target", plan.insideTarget)

            var launched = 0
            var lx = -1
            var ly = -1
            val dispatched = executeFallbackPlan(plan) { x, y ->
                launched++
                lx = x
                ly = y
                true
            }
            assertTrue(dispatched)
            assertEquals("exactly ONE gesture must be dispatched", 1, launched)
            assertEquals(540, lx)
            assertEquals(2268, ly)

            // Delivery still needs the hash to move — inside + changed verifies.
            assertEquals(
                TapOutcome.VERIFIED,
                classifyAnchoredTapOutcome("57ef089f235be651", "aa11bb22cc33dd44", false, plan.insideTarget)
            )
            // ...and an unchanged hash stays unverified even for a good tap.
            assertEquals(
                TapOutcome.UNVERIFIED,
                classifyAnchoredTapOutcome("57ef089f235be651", "57ef089f235be651", false, plan.insideTarget)
            )
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    // ---------------------------------------------------------------------
    // AC 2 — package changed ⇒ degenerate_bounds, ZERO gestures.
    // ---------------------------------------------------------------------

    @Test
    fun `degenerate live rect with a changed package refuses and dispatches no gesture`() {
        val (traversal, descriptor) = rootMatchTraversal()
        try {
            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = descriptorBoundsRect(descriptor),
                screenWidth = screenW,
                screenHeight = screenH,
                snapshotContext = sameScreenContext().copy(currentPackage = "com.google.android.gms")
            )
            assertTrue("an app change must refuse, got $plan", plan is TapPlan.Refused)
            plan as TapPlan.Refused
            assertEquals(FALLBACK_REASON_DEGENERATE_BOUNDS, plan.reason)
            assertEquals("0,0,1080,2400", plan.liveBounds)
            assertEquals("432,2180,648,2356", plan.targetBounds)

            var launched = 0
            val dispatched = executeFallbackPlan(plan) { _, _ ->
                launched++
                true
            }
            assertFalse(dispatched)
            assertEquals("zero gestures on a package change", 0, launched)
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    // ---------------------------------------------------------------------
    // AC 3 — screen size changed (rotation) ⇒ degenerate_bounds, ZERO gestures.
    // ---------------------------------------------------------------------

    @Test
    fun `degenerate live rect with changed screen dimensions refuses and dispatches no gesture`() {
        val (traversal, descriptor) = rootMatchTraversal()
        try {
            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = descriptorBoundsRect(descriptor),
                screenWidth = screenW,
                screenHeight = screenH,
                // The snapshot was minted on a smaller screen (rotation/resize).
                snapshotContext = sameScreenContext().copy(snapshotScreenWidth = 720, snapshotScreenHeight = 1600)
            )
            assertTrue("a size change must refuse, got $plan", plan is TapPlan.Refused)
            plan as TapPlan.Refused
            assertEquals(FALLBACK_REASON_DEGENERATE_BOUNDS, plan.reason)
            var launched = 0
            executeFallbackPlan(plan) { _, _ ->
                launched++
                true
            }
            assertEquals("zero gestures on a size change", 0, launched)
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    @Test
    fun `degenerate live rect with an unprovable screen refuses`() {
        val (traversal, descriptor) = rootMatchTraversal()
        try {
            val anchor = descriptorBoundsRect(descriptor)
            // No context at all (e.g. the post-scroll path): refuse.
            assertTrue(
                planFallbackTap(
                    liveBounds = traversal.targetBounds,
                    ancestorBounds = traversal.bounds,
                    anchorBounds = anchor,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    snapshotContext = null
                ) is TapPlan.Refused
            )
            // Snapshot predates dimension recording (-1, -1): unprovable, refuse.
            val unprovable = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = anchor,
                screenWidth = screenW,
                screenHeight = screenH,
                snapshotContext = SnapshotTapContext(-1, -1, pkg, pkg)
            )
            assertTrue(unprovable is TapPlan.Refused)
            assertEquals(FALLBACK_REASON_DEGENERATE_BOUNDS, (unprovable as TapPlan.Refused).reason)
            // Null current package: the app is unprovable, refuse.
            val nullPkg = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = anchor,
                screenWidth = screenW,
                screenHeight = screenH,
                snapshotContext = sameScreenContext().copy(currentPackage = null)
            )
            assertTrue(nullPkg is TapPlan.Refused)
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    // ---------------------------------------------------------------------
    // AC 4 — degenerate descriptor rect ⇒ degenerate_bounds, zero gestures.
    // ---------------------------------------------------------------------

    @Test
    fun `degenerate live rect with a degenerate descriptor rect refuses and dispatches no gesture`() {
        val (traversal, _) = rootMatchTraversal()
        try {
            // The id itself was minted on a screen-sized container: every
            // point inside it is a guess, even on an unchanged screen.
            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = rect(0, 0, screenW, screenH),
                screenWidth = screenW,
                screenHeight = screenH,
                snapshotContext = sameScreenContext()
            )
            assertTrue(plan is TapPlan.Refused)
            assertEquals(FALLBACK_REASON_DEGENERATE_BOUNDS, (plan as TapPlan.Refused).reason)
            var launched = 0
            executeFallbackPlan(plan) { _, _ ->
                launched++
                true
            }
            assertEquals(0, launched)
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    // ---------------------------------------------------------------------
    // Clamp — a descriptor rect hanging off the screen edge still taps
    // inside the target, at screen-absolute coords.
    // ---------------------------------------------------------------------

    @Test
    fun `snapshot_rect recovery clamps an off-screen descriptor rect to the screen`() {
        val (traversal, _) = rootMatchTraversal()
        try {
            val hanging = rect(900, 2300, 1200, 2500)
            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = hanging,
                screenWidth = screenW,
                screenHeight = screenH,
                snapshotContext = sameScreenContext()
            )
            assertTrue("a clampable rect must recover, got ${plan::class.simpleName}", plan is TapPlan.Tap)
            plan as TapPlan.Tap
            assertEquals(BOUNDS_SOURCE_SNAPSHOT_RECT, plan.boundsSource)
            assertEquals("900,2300,1080,2400", fallbackRectToString(plan.tapRect))
            assertEquals(990, plan.x)
            assertEquals(2350, plan.y)
            assertTrue("the clamped tap stays inside the descriptor rect", plan.insideTarget)
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    // ---------------------------------------------------------------------
    // AC 5 — resolveFresh prefers the 216x176 nav node over the full-screen
    // root for the same token set; legacy order is kept otherwise.
    // ---------------------------------------------------------------------

    @Test
    fun `resolveFresh prefers the smallest same-score rect over the degenerate root`() {
        val service = tieTreeService()
        val snapshotId = NodeResolver.snapshotManager.store(mapOf(7 to tieDescriptor()), pkg, screenW, screenH)

        val withDims = NodeResolver.resolveFresh(service, snapshotId, 7, screenW, screenH)
        assertNotNull(withDims)
        try {
            assertEquals(432, withDims!!.targetBounds.left)
            assertEquals(2180, withDims.targetBounds.top)
            assertEquals(648, withDims.targetBounds.right)
            assertEquals(2356, withDims.targetBounds.bottom)
        } finally {
            NodeResolver.recycleAll(withDims!!)
        }

        // Without screen dimensions the legacy winner (traversal order) is
        // kept: the root. This documents the tie-break the fix preserves.
        val legacy = NodeResolver.resolveFresh(service, snapshotId, 7)
        assertNotNull(legacy)
        try {
            assertEquals(0, legacy!!.targetBounds.left)
            assertEquals(screenW, legacy.targetBounds.right)
            assertEquals(screenH, legacy.targetBounds.bottom)
        } finally {
            NodeResolver.recycleAll(legacy!!)
        }
    }

    @Test
    fun `preferSmallestFreshMatch keeps a non-degenerate winner untouched`() {
        // Two sane same-score nodes: the first (traversal order) still wins —
        // the fix only intervenes for a degenerate winner.
        val candidates = listOf(
            FreshMatchCandidate(0, 66, 104, 870, 236),
            FreshMatchCandidate(1, 66, 300, 870, 432)
        )
        assertEquals(0, preferSmallestFreshMatch(candidates, screenW, screenH))
        // Equal areas keep traversal order even under a degenerate winner.
        val equalDegenerate = listOf(
            FreshMatchCandidate(0, 0, 0, screenW, screenH),
            FreshMatchCandidate(1, 0, 0, screenW, screenH)
        )
        assertEquals(0, preferSmallestFreshMatch(equalDegenerate, screenW, screenH))
        // Unknown screen size keeps the legacy winner.
        val degenerateFirst = listOf(
            FreshMatchCandidate(0, 0, 0, screenW, screenH),
            FreshMatchCandidate(1, 432, 2180, 648, 2356)
        )
        assertEquals(0, preferSmallestFreshMatch(degenerateFirst, 0, 0))
        // ...and with dimensions the smallest same-score rect wins.
        assertEquals(1, preferSmallestFreshMatch(degenerateFirst, screenW, screenH))
        // An insane candidate can never win.
        val insaneSmall = listOf(
            FreshMatchCandidate(0, 0, 0, screenW, screenH),
            FreshMatchCandidate(1, 5, 5, 5, 5)
        )
        assertEquals(0, preferSmallestFreshMatch(insaneSmall, screenW, screenH))
    }

    // ---------------------------------------------------------------------
    // SnapshotManager round-trips the dimensions the recovery proof needs.
    // ---------------------------------------------------------------------

    @Test
    fun `snapshotManager stores and returns snapshot screen dimensions`() {
        val manager = SnapshotManager()
        val descriptor = tieDescriptor()
        val id = manager.store(mapOf(7 to descriptor), pkg, screenW, screenH)
        assertEquals(Pair(screenW, screenH), manager.getSnapshotScreenSize(id))
        assertEquals(pkg, manager.getSnapshotPackage(id))
        // Pre-dimension snapshots read back as (-1, -1): unprovable.
        val legacyId = manager.store(mapOf(7 to descriptor), pkg)
        assertEquals(Pair(-1, -1), manager.getSnapshotScreenSize(legacyId))
        assertNull(manager.getSnapshotScreenSize(999999L))
    }
}
