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
 * R-1 REMAINING (1.0.114): the coordinate fallback must tap the TARGET's own
 * rect and must credit delivery only when the tap point is inside it.
 *
 * On-device evidence being locked down here (Play Store, 1.0.113):
 *
 * ```
 * {"method":"coordinate_fallback","tapX":540,"tapY":1200,
 *  "bounds":"0,0,1080,2400","boundsSource":"matched_node",
 *  "delivered":<true|false>}
 * ```
 *
 * — `0,0,1080,2400` is the SCREEN, so every fallback tapped dead centre
 * (540,1200). One such tap opened a carpool detail and another opened the
 * rating dialog, and both reported `delivered:true`.
 *
 * Rect note: android.graphics.Rect methods are stubs under JVM unit tests, so
 * rects are built by field assignment and assertions read fields.
 */
class R1TargetAnchorTest {

    private val screenW = 1080
    private val screenH = 2400

    // Play Store bottom-nav Search, as `observe` printed it: 432,2180 216x176.
    private fun navSearchRect(): Rect = rect(432, 2180, 648, 2356)

    // The search bar node as `observe` printed it: 66,104 804x132.
    private fun searchBarRect(): Rect = rect(66, 104, 870, 236)

    // The full-screen window root — what the fresh traversal matched on-device.
    private fun screenRect(): Rect = rect(0, 0, screenW, screenH)

    // A tall list container: NOT degenerate (its centre is a real control).
    private fun listContainerRect(): Rect = rect(0, 269, 1080, 1867)

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

    /**
     * A fake two-level traversal (root -> row) whose MATCHED node reports the
     * FULL SCREEN rect — the exact on-device shape: `observe` minted the id on
     * a 216x176 nav element, but the fresh traversal scored the window root.
     */
    private fun fullScreenTraversal(): Pair<FreshTraversalResult, NodeDescriptor> {
        val pkg = "com.android.vending"
        val root = mock(AccessibilityNodeInfo::class.java)
        val row = mock(AccessibilityNodeInfo::class.java)

        `when`(root.childCount).thenReturn(1)
        `when`(root.getChild(0)).thenReturn(row)
        `when`(row.childCount).thenReturn(0)
        `when`(row.getChild(0)).thenReturn(null)
        `when`(row.parent).thenReturn(root)
        `when`(root.parent).thenReturn(null)

        for (node in listOf(root, row)) {
            `when`(node.packageName).thenReturn(pkg)
            `when`(node.isVisibleToUser).thenReturn(true)
            `when`(node.isClickable).thenReturn(false)
        }
        // The root carries the semantic label, so descriptor.score() picks it —
        // this is exactly how the on-device traversal ended up on the window root.
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
            // What observe/find printed for the nav element.
            bounds = listOf(432, 2180, 648, 2356),
            clickable = true,
            scrollable = false
        )
        val snapshotId = NodeResolver.snapshotManager.store(mapOf(7 to descriptor), pkg)
        val result = NodeResolver.resolveFresh(service, snapshotId, 7)
        assertNotNull("resolveFresh must resolve the (wrong, full-screen) node", result)
        return Pair(result!!, descriptor)
    }

    // ---------------------------------------------------------------------
    // AC 1 — full-screen resolved rect ⇒ degenerate_bounds, ZERO gestures.
    // ---------------------------------------------------------------------

    @Test
    fun `full-screen matched node plans a degenerate_bounds refusal and dispatches no gesture`() {
        val (traversal, descriptor) = fullScreenTraversal()
        try {
            // The fresh traversal really did land on the screen-sized node.
            assertEquals(0, traversal.targetBounds.left)
            assertEquals(0, traversal.targetBounds.top)
            assertEquals(screenW, traversal.targetBounds.right)
            assertEquals(screenH, traversal.targetBounds.bottom)

            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = descriptorBoundsRect(descriptor),
                screenWidth = screenW,
                screenHeight = screenH
            )

            assertTrue(
                "a screen-sized resolved rect must refuse to tap, got $plan",
                plan is TapPlan.Refused
            )
            plan as TapPlan.Refused
            assertEquals(FALLBACK_REASON_DEGENERATE_BOUNDS, plan.reason)
            assertEquals("0,0,1080,2400", plan.liveBounds)
            assertEquals("432,2180,648,2356", plan.targetBounds)

            // The launcher seam: production calls executeFallbackPlan with
            // dispatchTap; a refused plan must never reach it.
            var launched = 0
            val dispatched = executeFallbackPlan(plan) { _, _ ->
                launched++
                true
            }
            assertFalse("a refused plan must not dispatch", dispatched)
            assertEquals("zero gestures must be dispatched for degenerate bounds", 0, launched)

            // And the envelope says so, with no tap coordinates invented.
            val json = buildRefusedFallbackJson(
                "coordinate_fallback", plan.reason, "9:7",
                plan.liveBounds, plan.targetBounds
            )
            assertEquals(
                "{\"success\":false,\"delivered\":false," +
                    "\"method\":\"coordinate_fallback\",\"reason\":\"degenerate_bounds\"," +
                    "\"elementId\":\"9:7\",\"bounds\":\"0,0,1080,2400\"," +
                    "\"boundsSource\":\"matched_node\"," +
                    "\"targetBounds\":\"432,2180,648,2356\"," +
                    "\"gestureDispatched\":false}",
                json
            )
            assertFalse("the refused envelope must not claim a tap", json.contains("tapX"))
        } finally {
            NodeResolver.recycleAll(traversal)
        }
    }

    @Test
    fun `degenerate check is two-dimensional — a tall narrow container is not degenerate`() {
        assertTrue(isDegenerateFallbackRect(screenRect(), screenW, screenH))
        assertFalse(isDegenerateFallbackRect(listContainerRect(), screenW, screenH))
        assertFalse("a zero-area rect is insane, not degenerate", isDegenerateFallbackRect(rect(5, 5, 5, 5), screenW, screenH))
        assertFalse(isDegenerateFallbackRect(null, screenW, screenH))
    }

    @Test
    fun `an id minted on a screen-sized container also refuses`() {
        // The descriptor itself is the window root: every point inside it is a guess.
        val plan = planFallbackTap(
            liveBounds = navSearchRect(),
            ancestorBounds = navSearchRect(),
            anchorBounds = screenRect(),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertTrue(plan is TapPlan.Refused)
        assertEquals(FALLBACK_REASON_DEGENERATE_BOUNDS, (plan as TapPlan.Refused).reason)
        var launched = 0
        executeFallbackPlan(plan) { _, _ -> launched++; true }
        assertEquals(0, launched)
    }

    @Test
    fun `a missing rect never becomes a centre tap`() {
        val plan = planFallbackTap(
            liveBounds = rect(10, 10, 10, 10),
            ancestorBounds = null,
            anchorBounds = navSearchRect(),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertTrue(plan is TapPlan.Refused)
        var launched = 0
        executeFallbackPlan(plan) { _, _ -> launched++; true }
        assertEquals(0, launched)
    }

    // ---------------------------------------------------------------------
    // AC 2 — a tap outside the requested target's rect is a FAILURE.
    // ---------------------------------------------------------------------

    @Test
    fun `tap outside the target rect reports tap_outside_target with tapX tapY and target rect`() {
        // Fresh traversal matched a tall list container (not degenerate), the id
        // came from the 216x176 nav element further down the screen.
        val plan = planFallbackTap(
            liveBounds = listContainerRect(),
            ancestorBounds = listContainerRect(),
            anchorBounds = navSearchRect(),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertTrue(plan is TapPlan.Tap)
        plan as TapPlan.Tap
        // No overlap between 0,269..1867 and 2180..2356 -> the live centre is tapped...
        assertEquals(540, plan.x)
        assertEquals(1068, plan.y)
        // ...and the point is NOT inside the requested target.
        assertFalse(plan.insideTarget)
        assertEquals("432,2180,648,2356", fallbackRectToString(plan.targetBounds))

        // A hash change is NOT enough: the tap missed the requested element.
        assertEquals(
            TapOutcome.UNVERIFIED,
            classifyAnchoredTapOutcome("57ef089f235be651", "c59636ea73b44ae7", false, plan.insideTarget)
        )

        val report = FallbackReport(
            delivered = false,
            screenHashBefore = "57ef089f235be651",
            screenHashAfter = "c59636ea73b44ae7",
            screenChanged = true,
            appChanged = false,
            tapX = plan.x,
            tapY = plan.y,
            bounds = fallbackRectToString(plan.tapRect),
            boundsSource = plan.boundsSource,
            targetBounds = fallbackRectToString(plan.targetBounds),
            tapInsideTarget = plan.insideTarget
        )
        val json = buildFallbackResultJson("coordinate_fallback", report)
        // The on-device carpool/rating-dialog case, verbatim shape.
        assertTrue("changed hash must not rescue a wrong-target tap: $json", json.contains("\"success\":false"))
        assertTrue(json.contains("\"reason\":\"tap_outside_target\""))
        assertTrue(json.contains("\"tapX\":540"))
        assertTrue(json.contains("\"tapY\":1068"))
        assertTrue("the mismatch must expose the target rect: $json", json.contains("\"targetBounds\":\"432,2180,648,2356\""))
        assertTrue(json.contains("\"tapInsideTarget\":false"))
        assertTrue("the screen DID change, and we still report it", json.contains("\"screenChanged\":true"))
    }

    // ---------------------------------------------------------------------
    // AC 3 — inside the rect + hash change is a real success.
    // ---------------------------------------------------------------------

    @Test
    fun `tap inside the target rect with a hash change is verified success`() {
        // Element unmoved: fresh rect == rect observe printed (216x176 nav pill).
        val plan = planFallbackTap(
            liveBounds = navSearchRect(),
            ancestorBounds = navSearchRect(),
            anchorBounds = navSearchRect(),
            screenWidth = screenW,
            screenHeight = screenH
        )
        assertTrue(plan is TapPlan.Tap)
        plan as TapPlan.Tap
        assertEquals(540, plan.x)
        assertEquals(2268, plan.y)
        assertTrue("the tap point must be inside the requested rect", plan.insideTarget)
        // resolveFresh always supplies bounds (ancestor-or-matched rect), so the
        // production source label for a row with a clickable ancestor is this one.
        assertEquals(BOUNDS_SOURCE_CLICKABLE_ANCESTOR, plan.boundsSource)

        assertEquals(
            TapOutcome.VERIFIED,
            classifyAnchoredTapOutcome("57ef089f235be651", "aa11bb22cc33dd44", false, plan.insideTarget)
        )
        val json = buildFallbackResultJson(
            "coordinate_fallback",
            FallbackReport(
                delivered = true,
                screenHashBefore = "57ef089f235be651",
                screenHashAfter = "aa11bb22cc33dd44",
                screenChanged = true,
                appChanged = false,
                tapX = plan.x,
                tapY = plan.y,
                bounds = fallbackRectToString(plan.tapRect),
                boundsSource = plan.boundsSource,
                targetBounds = fallbackRectToString(plan.targetBounds),
                tapInsideTarget = plan.insideTarget
            )
        )
        assertTrue(json.contains("\"success\":true"))
        assertTrue(json.contains("\"delivered\":true"))
        assertFalse("a verified tap carries no reason", json.contains("\"reason\""))
        assertTrue(json.contains("\"tapInsideTarget\":true"))
        assertTrue(json.contains("\"tapX\":540,\"tapY\":2268"))
    }

    @Test
    fun `inside the rect but an unchanged hash stays tap_unverified`() {
        val plan = planFallbackTap(
            liveBounds = searchBarRect(),
            ancestorBounds = searchBarRect(),
            anchorBounds = searchBarRect(),
            screenWidth = screenW,
            screenHeight = screenH
        )
        plan as TapPlan.Tap
        assertTrue(plan.insideTarget)
        assertEquals(468, plan.x)
        assertEquals(170, plan.y)

        assertEquals(
            TapOutcome.UNVERIFIED,
            classifyAnchoredTapOutcome("57ef089f235be651", "57ef089f235be651", false, plan.insideTarget)
        )
        val json = buildFallbackResultJson(
            "coordinate_fallback",
            FallbackReport(
                delivered = false,
                screenHashBefore = "57ef089f235be651",
                screenHashAfter = "57ef089f235be651",
                screenChanged = false,
                appChanged = false,
                tapX = plan.x,
                tapY = plan.y,
                bounds = fallbackRectToString(plan.tapRect),
                boundsSource = plan.boundsSource,
                targetBounds = fallbackRectToString(plan.targetBounds),
                tapInsideTarget = plan.insideTarget
            )
        )
        assertTrue(json.contains("\"reason\":\"tap_unverified\""))
        assertTrue(json.contains("\"tapInsideTarget\":true"))
    }

    @Test
    fun `a moved element still taps inside its own rect`() {
        // observe printed the bar at y=104..236; it is now at y=150..282.
        val movedLive = rect(66, 150, 870, 282)
        val plan = planFallbackTap(
            liveBounds = movedLive,
            ancestorBounds = movedLive,
            anchorBounds = searchBarRect(),
            screenWidth = screenW,
            screenHeight = screenH
        )
        plan as TapPlan.Tap
        // Clamped into the overlap (y 150..236) so the tap stays inside the target.
        assertEquals(468, plan.x)
        assertEquals(193, plan.y)
        assertTrue("a shifted-but-overlapping element still taps inside", plan.insideTarget)
    }

    @Test
    fun `reason selection separates outside-target from inert`() {
        assertEquals(FALLBACK_REASON_TAP_OUTSIDE_TARGET, fallbackFailureReason(false))
        assertEquals(FALLBACK_REASON_TAP_UNVERIFIED, fallbackFailureReason(true))
        assertEquals(
            TapOutcome.UNVERIFIED,
            classifyAnchoredTapOutcome("aa", "bb", false, false)
        )
    }
}
