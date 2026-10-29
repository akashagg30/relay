// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.graphics.Rect

/**
 * R-1 REMAINING (1.0.114): the coordinate fallback must tap the TARGET's own
 * rect and must report delivery against that rect.
 *
 * On-device evidence (Play Store, 1.0.113) — every `click_node` that fell back
 * reported the SAME tap point and the SAME rect:
 *
 * ```
 * {"method":"coordinate_fallback","tapX":540,"tapY":1200,
 *  "bounds":"0,0,1080,2400","boundsSource":"matched_node"}
 * ```
 *
 * `0,0,1080,2400` is the SCREEN, so the tap was its centre (540,1200): one
 * invocation opened a carpool detail, another opened the rating dialog, and
 * both claimed `delivered:true`. The fresh traversal's "matched node" was the
 * window/container root, not the semantic element the id was minted from.
 *
 * This module is the pure, JVM-testable decision layer for that path:
 *
 *  - [planFallbackTap] turns (fresh live rect, clickable-ancestor rect,
 *    descriptor rect from the snapshot the id came from) into either a
 *    [TapPlan.Tap] or a [TapPlan.Refused] — a degenerate (screen-sized) rect
 *    REFUSES, so no gesture is dispatched at dead centre.
 *  - [classifyAnchoredTapOutcome] only credits a tap as delivered when the tap
 *    point lies inside the REQUESTED target's rect, so a wrong-target tap that
 *    happened to change the screen can no longer pass as success.
 *
 * Rect note (same convention as R1ClickFallbackTest): android.graphics.Rect
 * methods are stubs under JVM unit tests, so every helper here reads FIELDS
 * only (left/top/right/bottom) and never calls width()/height()/contains().
 */

/** Failure reason: the resolved rect covers the screen, so a tap would be a dead-centre guess. */
internal const val FALLBACK_REASON_DEGENERATE_BOUNDS = "degenerate_bounds"

/** Failure reason: the tap was dispatched but its point lies outside the requested target's rect. */
internal const val FALLBACK_REASON_TAP_OUTSIDE_TARGET = "tap_outside_target"

/** Failure reason: the tap was dispatched inside the target rect but the screen did not change. */
internal const val FALLBACK_REASON_TAP_UNVERIFIED = "tap_unverified"

/** Where the tapped rect came from, for the audit trail. */
internal const val BOUNDS_SOURCE_MATCHED_NODE = "matched_node"
internal const val BOUNDS_SOURCE_CLICKABLE_ANCESTOR = "clickable_ancestor"
/**
 * R-2: the fresh traversal resolved a degenerate (screen-sized) rect, so the
 * tap fell back to the descriptor rect from the snapshot the id came from —
 * only when the screen was provably unchanged (see [SnapshotTapContext]).
 */
internal const val BOUNDS_SOURCE_SNAPSHOT_RECT = "snapshot_rect"

/** A degenerate rect must cover at least this percentage of BOTH screen axes. */
internal const val DEGENERATE_BOUNDS_PERCENT = 95

internal sealed class TapPlan {
    /** A point to tap, plus the rect it was derived from and the target it is credited against. */
    data class Tap(
        val x: Int,
        val y: Int,
        val tapRect: Rect,
        val targetBounds: Rect?,
        val boundsSource: String,
        /** True only when (x, y) lies inside [targetBounds] (the rect the id came from). */
        val insideTarget: Boolean
    ) : TapPlan()

    /** No gesture may be dispatched. [reason] is one of the FALLBACK_REASON_* constants. */
    data class Refused(
        val reason: String,
        val liveBounds: String,
        val targetBounds: String
    ) : TapPlan()
}

internal fun fallbackRectSane(rect: Rect?): Boolean {
    return rect != null && rect.right > rect.left && rect.bottom > rect.top
}

/**
 * The rect an element id was minted with, as `observe`/`find` printed it.
 * Returns null only when the descriptor carries no bounds at all.
 */
internal fun descriptorBoundsRect(descriptor: NodeDescriptor?): Rect? {
    val raw = descriptor?.bounds ?: return null
    if (raw.size < 4) return null
    val out = Rect()
    out.left = raw[0]
    out.top = raw[1]
    out.right = raw[2]
    out.bottom = raw[3]
    return out
}

internal fun fallbackRectToString(rect: Rect?): String {
    if (rect == null) return ""
    return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
}

/**
 * A rect is degenerate when it spans (very nearly) the whole screen in BOTH
 * axes: the centroid of such a rect IS the screen centre, which is precisely
 * the wrong-target tap observed on-device. A tall-but-narrow container, by
 * contrast, is not degenerate — its centre is a real screen point.
 *
 * Field arithmetic only, so this stays JVM-testable.
 */
internal fun isDegenerateFallbackRect(rect: Rect?, screenWidth: Int, screenHeight: Int): Boolean {
    if (!fallbackRectSane(rect)) return false
    if (screenWidth <= 0 || screenHeight <= 0) return false
    val width = rect!!.right - rect.left
    val height = rect.bottom - rect.top
    val minWidth = screenWidth * DEGENERATE_BOUNDS_PERCENT / 100
    val minHeight = screenHeight * DEGENERATE_BOUNDS_PERCENT / 100
    return width >= minWidth && height >= minHeight
}

/** Inclusive test — a tap on the last row/column of a 1px element must still count. */
internal fun fallbackPointInRect(x: Int, y: Int, rect: Rect?): Boolean {
    if (!fallbackRectSane(rect)) return false
    return x >= rect!!.left && x <= rect.right && y >= rect.top && y <= rect.bottom
}

internal fun fallbackIntersect(a: Rect, b: Rect): Rect? {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return null
    val out = Rect()
    out.left = left
    out.top = top
    out.right = right
    out.bottom = bottom
    return out
}

/**
 * R-2: what the coordinate fallback must prove before it may tap the
 * snapshot's descriptor rect after the fresh traversal resolved a
 * degenerate (screen-sized) rect.
 *
 * The tap is only safe when the screen is provably the same one the id was
 * minted on: identical dimensions (rules out rotation/resize) AND the same
 * foreground package (rules out an app change underneath the id). Either
 * fact missing or changed means REFUSE — a screen-sized resolved rect plus
 * a stale descriptor rect is exactly the wrong-target tap 1.0.114 stopped.
 */
internal data class SnapshotTapContext(
    val snapshotScreenWidth: Int,
    val snapshotScreenHeight: Int,
    val snapshotPackage: String?,
    val currentPackage: String?
)

/**
 * R-2 recovery: the live rect is degenerate, but the descriptor rect from
 * the snapshot the id came from may still be a safe tap — iff the context
 * proves the screen is unchanged. Returns the tap, or null when recovery is
 * not provably safe (caller refuses with degenerate_bounds).
 *
 * The tap point is the centre of the descriptor rect clamped to the screen;
 * the clamped rect is inside the descriptor rect, so the tap stays credited
 * against the target the caller asked for.
 */
internal fun planSnapshotRectTap(
    anchorBounds: Rect?,
    screenWidth: Int,
    screenHeight: Int,
    snapshotContext: SnapshotTapContext?
): TapPlan.Tap? {
    val ctx = snapshotContext ?: return null
    if (ctx.snapshotScreenWidth <= 0 || ctx.snapshotScreenHeight <= 0) return null
    if (ctx.snapshotScreenWidth != screenWidth || ctx.snapshotScreenHeight != screenHeight) return null
    if (ctx.snapshotPackage == null || ctx.snapshotPackage != ctx.currentPackage) return null
    if (!fallbackRectSane(anchorBounds)) return null
    if (isDegenerateFallbackRect(anchorBounds, screenWidth, screenHeight)) return null
    val screen = Rect()
    screen.left = 0
    screen.top = 0
    screen.right = screenWidth
    screen.bottom = screenHeight
    val clamped = fallbackIntersect(anchorBounds!!, screen) ?: return null
    val point = gesturePointFromBounds(clamped)
    val inside = fallbackPointInRect(point.first, point.second, anchorBounds)
    return TapPlan.Tap(
        x = point.first,
        y = point.second,
        tapRect = clamped,
        targetBounds = anchorBounds,
        boundsSource = BOUNDS_SOURCE_SNAPSHOT_RECT,
        insideTarget = inside
    )
}

/**
 * Decide where (or whether) the coordinate fallback may tap.
 *
 * @param liveBounds     the FRESH traversal's matched-node rect (the target's own rect now).
 * @param ancestorBounds the clickable ancestor rect — used only as a clamp, never as the tap point.
 * @param anchorBounds   the rect delivery is credited against AND the rect the tap is clamped into.
 *                       The visible path passes the descriptor rect from the snapshot the element
 *                       id came from — the rect `observe`/`find` printed, which is what the caller
 *                       asked to click. The post-scroll path passes the fresh traversal rect,
 *                       because a scroll legitimately moves the element and the pre-scroll
 *                       descriptor no longer describes where it is. `null` means "no anchor":
 *                       the tap may still be dispatched, but delivery can never be credited.
 * @param screenWidth    real screen width in px.
 * @param screenHeight   real screen height in px.
 *
 * Refusals (no gesture at all):
 *  - live rect insane or degenerate (screen-sized) — the traversal resolved the root, not the target;
 *    a degenerate live rect still recovers via [planSnapshotRectTap] when the snapshot proves the
 *    screen is unchanged, and refuses otherwise;
 *  - the anchor rect is degenerate — the id points at a container, so any point is a guess.
 *
 * Otherwise the tap point is the centre of `live ∩ anchor` when those agree (a legitimately moved
 * element still gets an inside-the-target tap), or the clamped live-rect centre when they do not
 * (the fresh rect is the better guess, and the delivery check reports the mismatch honestly
 * instead of claiming success).
 */
internal fun planFallbackTap(
    liveBounds: Rect?,
    ancestorBounds: Rect?,
    anchorBounds: Rect?,
    screenWidth: Int,
    screenHeight: Int,
    /**
     * Whether the fresh traversal found a CLICKABLE ANCESTOR. Passed in rather
     * than inferred from `ancestorBounds != null`: `FreshTraversalResult.bounds`
     * is always non-null, so inferring made every fallback report
     * `boundsSource: clickable_ancestor` and hid the matched-node case that the
     * device audit needs to see.
     */
    hasClickableAncestor: Boolean = false,
    /**
     * R-2 recovery context: when the live rect is degenerate, the planner may
     * still tap the snapshot's descriptor rect iff this proves the screen is
     * unchanged (same dimensions, same foreground package). Null (the
     * default, e.g. the post-scroll path whose anchor is a fresh rect, not a
     * snapshot rect) keeps the 1.0.114 behaviour: refuse.
     */
    snapshotContext: SnapshotTapContext? = null
): TapPlan {
    val liveStr = fallbackRectToString(liveBounds)
    val targetStr = fallbackRectToString(anchorBounds)

    if (!fallbackRectSane(liveBounds)) {
        // No usable rect: never tap the screen centre on the strength of a missing rect.
        return TapPlan.Refused(FALLBACK_REASON_DEGENERATE_BOUNDS, liveStr, targetStr)
    }
    if (isDegenerateFallbackRect(liveBounds, screenWidth, screenHeight)) {
        // R-2: the traversal resolved the root — recover the tap from the
        // snapshot rect when provably safe, refuse (zero gestures) otherwise.
        planSnapshotRectTap(anchorBounds, screenWidth, screenHeight, snapshotContext)?.let { return it }
        return TapPlan.Refused(FALLBACK_REASON_DEGENERATE_BOUNDS, liveStr, targetStr)
    }
    if (isDegenerateFallbackRect(anchorBounds, screenWidth, screenHeight)) {
        return TapPlan.Refused(FALLBACK_REASON_DEGENERATE_BOUNDS, liveStr, targetStr)
    }

    val clampedLive = NodeResolver.resolveTapRect(liveBounds!!, ancestorBounds)
    val anchor = anchorBounds
    val tapRect = if (fallbackRectSane(anchor)) {
        fallbackIntersect(clampedLive, anchor!!) ?: clampedLive
    } else {
        clampedLive
    }

    val point = gesturePointFromBounds(tapRect)
    val inside = fallbackPointInRect(point.first, point.second, anchor)
    val source = if (hasClickableAncestor) BOUNDS_SOURCE_CLICKABLE_ANCESTOR else BOUNDS_SOURCE_MATCHED_NODE

    return TapPlan.Tap(
        x = point.first,
        y = point.second,
        tapRect = tapRect,
        targetBounds = anchor,
        boundsSource = source,
        insideTarget = inside
    )
}

/**
 * The single seam between the plan and the gesture: production passes
 * `::dispatchTap`, tests pass a counting fake and assert ZERO calls for a
 * refused plan. Returns true only when a gesture was actually dispatched.
 */
internal fun executeFallbackPlan(plan: TapPlan, launch: (Int, Int) -> Boolean): Boolean {
    return when (plan) {
        is TapPlan.Refused -> false
        is TapPlan.Tap -> launch(plan.x, plan.y)
    }
}

/**
 * Target-anchored delivery: a screen change only counts when the tap landed
 * INSIDE the requested target's rect. A wrong-target tap that happens to
 * change the screen (carpool detail, rating dialog) is reported as NOT
 * delivered.
 */
internal fun classifyAnchoredTapOutcome(
    hashBefore: String?,
    hashAfter: String?,
    isError: Boolean,
    insideTarget: Boolean
): TapOutcome {
    if (!insideTarget) return TapOutcome.UNVERIFIED
    return classifyTapOutcome(hashBefore, hashAfter, isError)
}

/**
 * Failure reason for an unverified tap: "outside the target" is a different
 * fact from "inside the target but the screen stayed identical", and the
 * caller needs to tell them apart.
 */
internal fun fallbackFailureReason(insideTarget: Boolean): String {
    return if (insideTarget) FALLBACK_REASON_TAP_UNVERIFIED else FALLBACK_REASON_TAP_OUTSIDE_TARGET
}
