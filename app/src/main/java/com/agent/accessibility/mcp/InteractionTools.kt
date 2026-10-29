// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONObject

/**
 * Interaction tools: clicking, gestures, text input and global actions.
 * Implements click_node, tap, swipe, input_text, back and home, plus the
 * scroll-into-view / retry machinery shared by element interactions.
 */

internal fun McpHandler.clickNode(id: String, args: JSONObject): String {
    val startTime = System.currentTimeMillis()
    val deadline = startTime + CLICK_DEADLINE_MS

    val (elementId, error) = parseElementId(args, id, this)
    if (elementId == null) return error!!

    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val firstResult = attemptClick(id, elementId, service, deadline, startTime)
    if (!isErrorResponse(firstResult)) {
        return firstResult
    }

    Log.d(TAG, "[click_node] first attempt failed, retrying with fresh snapshot")
    val retryResult = retryClickWithFreshSnapshot(id, elementId, service, deadline, startTime)
    if (retryResult != null && !isErrorResponse(retryResult)) {
        return retryResult
    }

    return firstResult
}

private fun isErrorResponse(result: String): Boolean {
    return result.startsWith("""{"error":""") || result.contains("\"error\"")
}

private fun McpHandler.attemptClick(
    id: String,
    elementId: ElementId,
    service: AgentAccessibilityService,
    deadline: Long,
    startTime: Long
): String {
    val t0 = System.currentTimeMillis()
    val descriptor = NodeResolver.snapshotManager.getDescriptor(elementId.snapshotId, elementId.nodeId)
        ?: return toolErrorResponse(id, "element_expired: the element's snapshot is no longer cached. " +
            "Call get_screen_state to get fresh elements.")

    val t1 = System.currentTimeMillis()
    Log.d(TAG, "[click_node] resolve: ${t1 - t0}ms")

    val traversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
        ?: return toolErrorResponse(id, "element_not_found: element ${elementId.encode()} could not be resolved " +
            "against the current screen. Call get_screen_state to refresh.")

    val t2 = System.currentTimeMillis()
    Log.d(TAG, "[click_node] resolveFresh: ${t2 - t1}ms, visible=${traversal.isVisible}, " +
        "method=${traversal.method}, bounds=[${traversal.bounds.left},${traversal.bounds.top}," +
        "${traversal.bounds.right},${traversal.bounds.bottom}]")

    try {
        if (traversal.isVisible) {
            val actionTarget = traversal.clickableAncestor ?: traversal.matchedNode

            // Validate element state before clicking
            if (!actionTarget.isEnabled) {
                Log.w(TAG, "[click_node] element is disabled")
                return toolErrorResponse(id, "element_disabled: the element is not enabled. Check if a prerequisite action is needed first.")
            }

            // R-1 card 2 (D3): content baseline BEFORE the action. The package
            // comparison below can no longer serve as screenChanged.
            val (hashBefore, pkgBefore) = currentScreenObservation(service)

            val clickResult = actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val total = System.currentTimeMillis() - startTime
            Log.d(TAG, "[click_node] total=${total}ms (visible click), result=$clickResult")

            if (clickResult) {
                val total = System.currentTimeMillis() - startTime
                Thread.sleep(300)
                val (hashAfter, pkgAfter) = currentScreenObservation(service)
                val screenChanged = computeContentChanged(hashBefore, hashAfter)
                val appChanged = computeAppChanged(pkgBefore, pkgAfter)
                auditLogger.log("click_node", true, "accessibility_click", total, null,
                    "elementId" to elementId.encode(), "resolutionMethod" to traversal.method,
                    "screenHashBefore" to (hashBefore ?: "unknown"),
                    "screenHashAfter" to (hashAfter ?: "unknown"),
                    "delivered" to true)

                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "accessibility_click")
                    put("resolutionMethod", traversal.method)
                    put("screenHashBefore", hashBefore ?: "unknown")
                    put("screenHashAfter", hashAfter ?: "unknown")
                    put("screenChanged", screenChanged)
                    put("appChanged", appChanged)
                }.toString())
            }

            // 1.0.114 (R-1 remaining): the coordinate fallback is decided by
            // planFallbackTap, which REFUSES a degenerate (screen-sized) rect
            // instead of tapping its centre, and anchors the tap inside the
            // element's own rect from the snapshot the id came from.
            val screenW = getScreenWidth()
            val screenH = getScreenHeight()
            val plan = planFallbackTap(
                liveBounds = traversal.targetBounds,
                ancestorBounds = traversal.bounds,
                anchorBounds = descriptorBoundsRect(descriptor),
                screenWidth = screenW,
                screenHeight = screenH,
                hasClickableAncestor = traversal.clickableAncestor != null
            )
            if (plan is TapPlan.Refused) {
                return refuseFallback(
                    id, startTime,
                    "coordinate_fallback", "coordinate_fallback",
                    elementId.encode(), plan
                )
            }
            plan as TapPlan.Tap
            val tapResult = executeFallbackPlan(plan) { x, y -> dispatchTap(x, y) }
            if (tapResult) {
                Log.d(TAG, "Coordinate fallback dispatched at (${plan.x},${plan.y})")
                // R-1 card 2 (D1/D2/D4/D5) + 1.0.114: delivery is verified by a
                // post-tap content observation AND by the tap point being inside
                // the requested element's rect.
                return assembleVerifiedFallback(
                    id, service, startTime,
                    "coordinate_fallback", "coordinate_fallback",
                    elementId.encode(), plan,
                    hashBefore, pkgBefore
                )
            }
            return toolErrorResponse(id, "action_failed: node resolved but click/ancestor actions " +
                "failed. resolutionMethod=${traversal.method}")
        }

        if (System.currentTimeMillis() >= deadline) {
            Log.w(TAG, "[click_node] deadline reached before scroll")
            return toolErrorResponse(id, "target_offscreen_unreachable: action_deadline_exceeded before scroll")
        }

        val scrollResult = bringIntoViewFresh(id, elementId, traversal, deadline)
        if (scrollResult != null) {
            return toolErrorResponse(id, scrollResult)
        }

        if (System.currentTimeMillis() >= deadline) {
            return toolErrorResponse(id, "target_offscreen_unreachable: action_deadline_exceeded after scroll")
        }

        val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
            ?: return toolErrorResponse(id, "target_offscreen_unreachable: could not re-resolve after scroll")

        try {
            val actionTarget = reTraversal.clickableAncestor ?: reTraversal.matchedNode
            val clickResult = actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clickResult) {
                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "accessibility_click_scrolled")
                }.toString())
            }

            // 1.0.114: same planner as the visible path. The pre-scroll
            // descriptor rect is stale after a scroll, so the FRESH traversal
            // rect is the delivery anchor here — a scroll legitimately moves
            // the element, and the degenerate check still refuses a
            // screen-sized (root) match.
            val screenW = getScreenWidth()
            val screenH = getScreenHeight()
            val scrollPlan = planFallbackTap(
                liveBounds = reTraversal.targetBounds,
                ancestorBounds = reTraversal.bounds,
                anchorBounds = reTraversal.targetBounds,
                screenWidth = screenW,
                screenHeight = screenH,
                hasClickableAncestor = reTraversal.clickableAncestor != null
            )
            if (scrollPlan is TapPlan.Refused) {
                return refuseFallback(
                    id, startTime,
                    "coordinate_fallback", "coordinate_fallback",
                    elementId.encode(), scrollPlan
                )
            }
            scrollPlan as TapPlan.Tap
            // Fresh baseline AFTER the scroll (R-1 card 2): the pre-scroll
            // screen no longer describes what this tap will act on.
            val (scrollHashBefore, scrollPkgBefore) = currentScreenObservation(service)
            val tapResult = executeFallbackPlan(scrollPlan) { x, y -> dispatchTap(x, y) }
            if (tapResult) {
                // Same honest method name as the visible fallback (D2/D4):
                // scrolled or not, an unverified tap reports tap_unverified.
                return assembleVerifiedFallback(
                    id, service, startTime,
                    "coordinate_fallback", "coordinate_fallback",
                    elementId.encode(), scrollPlan,
                    scrollHashBefore, scrollPkgBefore
                )
            }
            return toolErrorResponse(id, "target_offscreen_unreachable: could not bring target into view")
        } finally {
            NodeResolver.recycleAll(reTraversal)
        }
    } finally {
        NodeResolver.recycleAll(traversal)
    }
}

private fun McpHandler.retryClickWithFreshSnapshot(
    id: String,
    oldElementId: ElementId,
    service: AgentAccessibilityService,
    deadline: Long,
    startTime: Long
): String? {
    val oldDescriptor = NodeResolver.snapshotManager.getDescriptor(
        oldElementId.snapshotId, oldElementId.nodeId
    ) ?: return null

    val rootNode = findForegroundRoot(service) ?: return null
    val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)
    val newDescriptors = NodeResolver.buildDescriptors(tree)
    val newSnapshotId = NodeResolver.snapshotManager.store(newDescriptors, tree.foregroundPackage)

    var bestNewNodeId = -1
    var bestScore = 0
    for ((nodeId, newDescriptor) in newDescriptors) {
        val score = scoreDescriptorVsDescriptor(oldDescriptor, newDescriptor)
        if (score > bestScore) {
            bestScore = score
            bestNewNodeId = nodeId
        }
    }

    if (bestNewNodeId < 0 || bestScore < 10) {
        Log.d(TAG, "[click_node] retry: no match found in fresh snapshot (bestScore=$bestScore)")
        return null
    }

    val newElementId = ElementId(newSnapshotId, bestNewNodeId)
    Log.d(TAG, "[click_node] retry: matched new element ${newElementId.encode()} with score $bestScore")
    return attemptClick(id, newElementId, service, deadline, startTime)
}

private fun scoreDescriptorVsDescriptor(
    old: NodeDescriptor,
    new: NodeDescriptor
): Int {
    var score = 0
    if (old.viewIdResourceName != null && old.viewIdResourceName == new.viewIdResourceName) score += 50
    if (old.text != null && old.text == new.text) score += 40
    if (old.contentDescription != null && old.contentDescription == new.contentDescription) score += 40
    if (old.className != null && old.className == new.className) score += 5
    return score
}

/**
 * R-1 card 2 (honest delivery reporting): outcome of a coordinate tap.
 *
 * VERIFIED means the post-tap observation's tree-derived content hash
 * differs from the pre-tap hash (the tap demonstrably changed the
 * screen). Anything else — same hash, missing observation, or a dispatch
 * error — is UNVERIFIED and must be reported as success:false.
 */
internal enum class TapOutcome {
    VERIFIED,
    UNVERIFIED
}

/**
 * Pure delivery classifier (no Android framework types in the signature,
 * so it is unit-testable on the JVM).
 */
internal fun classifyTapOutcome(hashBefore: String?, hashAfter: String?, isError: Boolean): TapOutcome {
    if (isError) return TapOutcome.UNVERIFIED
    if (hashBefore == null || hashAfter == null) return TapOutcome.UNVERIFIED
    return if (hashBefore != hashAfter) TapOutcome.VERIFIED else TapOutcome.UNVERIFIED
}

/**
 * Content comparison backing `screenChanged` (D3): tree-derived hashes
 * differ. Null-safe — a missing observation never claims a change.
 */
internal fun computeContentChanged(hashBefore: String?, hashAfter: String?): Boolean {
    return hashBefore != null && hashAfter != null && hashBefore != hashAfter
}

/**
 * Package comparison backing `appChanged` (D3): keeps the old
 * package-name signal expressible for the R-1 adjacent-control check
 * without overloading it as a screen-change detector.
 */
internal fun computeAppChanged(pkgBefore: String?, pkgAfter: String?): Boolean {
    return pkgBefore != null && pkgAfter != null && pkgBefore != pkgAfter
}

/**
 * Report assembled for a dispatched coordinate tap. `delivered` drives
 * `success`; when false the result carries reason "tap_unverified" (D2) or
 * "tap_outside_target" (1.0.114: the tap point was not inside the requested
 * target's rect, so a screen change cannot be credited to this target).
 *
 * `bounds` is the rect the tap was derived from; `targetBounds` is the rect
 * the element id came from (what `observe`/`find` printed) and therefore the
 * rect delivery is judged against.
 */
internal data class FallbackReport(
    val delivered: Boolean,
    val screenHashBefore: String,
    val screenHashAfter: String,
    val screenChanged: Boolean,
    val appChanged: Boolean,
    val tapX: Int,
    val tapY: Int,
    val bounds: String,
    val boundsSource: String,
    val targetBounds: String = "",
    val tapInsideTarget: Boolean = true
)

private fun jsonQuoted(raw: String): String {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Deterministic result-object JSON for the coordinate-fallback path.
 * Hand-rolled with fixed key order because org.json is Android-only and
 * unavailable under JVM unit tests — the R-1 truth tests assert this
 * string character for character.
 */
internal fun buildFallbackResultJson(method: String, report: FallbackReport): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"success\":").append(if (report.delivered) "true" else "false")
    sb.append(",\"delivered\":").append(if (report.delivered) "true" else "false")
    sb.append(",\"method\":").append(jsonQuoted(method))
    if (!report.delivered) {
        sb.append(",\"reason\":").append(jsonQuoted(fallbackFailureReason(report.tapInsideTarget)))
    }
    sb.append(",\"screenHashBefore\":").append(jsonQuoted(report.screenHashBefore))
    sb.append(",\"screenHashAfter\":").append(jsonQuoted(report.screenHashAfter))
    sb.append(",\"screenChanged\":").append(if (report.screenChanged) "true" else "false")
    sb.append(",\"appChanged\":").append(if (report.appChanged) "true" else "false")
    sb.append(",\"tapX\":").append(report.tapX)
    sb.append(",\"tapY\":").append(report.tapY)
    sb.append(",\"bounds\":").append(jsonQuoted(report.bounds))
    sb.append(",\"boundsSource\":").append(jsonQuoted(report.boundsSource))
    sb.append(",\"targetBounds\":").append(jsonQuoted(report.targetBounds))
    sb.append(",\"tapInsideTarget\":").append(if (report.tapInsideTarget) "true" else "false")
    sb.append("}")
    return sb.toString()
}

/**
 * Result for a REFUSED coordinate tap (1.0.114): no gesture was dispatched, so
 * there is no tapX/tapY to report — only the rects that produced the refusal.
 * `success:false, delivered:false, reason:"degenerate_bounds"` is the contract
 * Executive's device run asserted against.
 */
internal fun buildRefusedFallbackJson(
    method: String,
    reason: String,
    elementId: String,
    liveBounds: String,
    targetBounds: String
): String {
    val sb = StringBuilder()
    sb.append("{\"success\":false")
    sb.append(",\"delivered\":false")
    sb.append(",\"method\":").append(jsonQuoted(method))
    sb.append(",\"reason\":").append(jsonQuoted(reason))
    sb.append(",\"elementId\":").append(jsonQuoted(elementId))
    sb.append(",\"bounds\":").append(jsonQuoted(liveBounds))
    sb.append(",\"boundsSource\":").append(jsonQuoted(BOUNDS_SOURCE_MATCHED_NODE))
    sb.append(",\"targetBounds\":").append(jsonQuoted(targetBounds))
    sb.append(",\"gestureDispatched\":false")
    sb.append("}")
    return sb.toString()
}

/**
 * Audit context for the coordinate-fallback branch (D4). Pure so the key
 * set is unit-testable; the caller splats it into
 * auditLogger.log("click_node", delivered, method, ms, null, *pairs).
 * statusBarHeight is reported (0: gesture coords are screen-absolute per
 * card 1) — never subtracted.
 */
internal fun fallbackAuditPairs(
    elementId: String,
    boundsSource: String,
    bounds: String,
    tapX: Int,
    tapY: Int,
    statusBarHeight: Int,
    screenHashBefore: String,
    screenHashAfter: String,
    delivered: Boolean,
    targetBounds: String = "",
    tapInsideTarget: Boolean = true,
    gestureDispatched: Boolean = true
): Array<Pair<String, Any>> {
    return arrayOf(
        "elementId" to elementId,
        "boundsSource" to boundsSource,
        "bounds" to bounds,
        "tapX" to tapX,
        "tapY" to tapY,
        "statusBarHeight" to statusBarHeight,
        "screenHashBefore" to screenHashBefore,
        "screenHashAfter" to screenHashAfter,
        "delivered" to delivered,
        // 1.0.114: the target-anchored facts. `delivered` is only true when
        // tapInsideTarget AND the hash moved; both are recorded so a device
        // audit can tell a wrong-target tap from an inert one.
        "targetBounds" to targetBounds,
        "tapInsideTarget" to tapInsideTarget,
        "gestureDispatched" to gestureDispatched
    )
}

/**
 * One content observation: tree-derived screen hash (element ids are
 * excluded by SemanticScene.computeScreenHash) plus the foreground
 * package. Never throws; returns (null, null) when no window is readable.
 */
internal fun McpHandler.currentScreenObservation(service: AgentAccessibilityService): Pair<String?, String?> {
    val root = try {
        findForegroundRoot(service)
    } catch (_: Exception) {
        null
    } ?: return Pair(null, null)
    return try {
        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(root)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val scene = SemanticProjector.project(tree, -1L, metrics.heightPixels, metrics.widthPixels)
        Pair(scene.computeScreenHash(), scene.app)
    } catch (_: Exception) {
        Pair(null, null)
    } finally {
        try {
            root.recycle()
        } catch (_: Exception) {
            // Best effort: the observation must never break the click path.
        }
    }
}

/**
 * Post-tap verification window (D5): hash 300ms after the tap (the
 * pre-existing sleep); if the hash is unchanged, hash once more after
 * 800ms before the caller declares tap_unverified. Returns the final
 * (hash, package) pair.
 */
internal fun McpHandler.observePostTap(
    service: AgentAccessibilityService,
    hashBefore: String?
): Pair<String?, String?> {
    Thread.sleep(300)
    var observed = currentScreenObservation(service)
    if (hashBefore != null && observed.first == hashBefore) {
        Thread.sleep(800)
        observed = currentScreenObservation(service)
    }
    return observed
}

/**
 * Shared tail of both coordinate-fallback sites (visible + scrolled):
 * D5 verification window, D2 verified/unverified result assembly, D4
 * audit entry. Call only after the tap was dispatched (tapResult true).
 *
 * 1.0.114: delivery is TARGET-ANCHORED — the screen-hash change only counts
 * when the tap point lies inside the requested element's own rect, so a
 * wrong-target tap (carpool detail, rating dialog) is reported as a failure
 * instead of `delivered:true`.
 */
private fun McpHandler.assembleVerifiedFallback(
    id: String,
    service: AgentAccessibilityService,
    startTime: Long,
    resultMethod: String,
    auditMethod: String,
    elementIdStr: String,
    plan: TapPlan.Tap,
    hashBefore: String?,
    pkgBefore: String?
): String {
    val (hashAfter, pkgAfter) = observePostTap(service, hashBefore)
    val screenChanged = computeContentChanged(hashBefore, hashAfter)
    val appChanged = computeAppChanged(pkgBefore, pkgAfter)
    val delivered = classifyAnchoredTapOutcome(
        hashBefore, hashAfter, false, plan.insideTarget
    ) == TapOutcome.VERIFIED
    if (!delivered) {
        if (plan.insideTarget) {
            Log.w(TAG, "[click_node] coordinate tap unverified: screen hash unchanged")
        } else {
            Log.w(
                TAG,
                "[click_node] coordinate tap outside target rect: tap=(${plan.x},${plan.y}) " +
                    "targetBounds=${fallbackRectToString(plan.targetBounds)}"
            )
        }
    }
    val total = System.currentTimeMillis() - startTime
    val boundsStr = fallbackRectToString(plan.tapRect)
    val targetStr = fallbackRectToString(plan.targetBounds)
    auditLogger.log(
        "click_node", delivered, auditMethod, total, null,
        *fallbackAuditPairs(
            elementIdStr, plan.boundsSource, boundsStr,
            plan.x, plan.y, 0,
            hashBefore ?: "unknown", hashAfter ?: "unknown", delivered,
            targetStr, plan.insideTarget, true
        )
    )
    val report = FallbackReport(
        delivered = delivered,
        screenHashBefore = hashBefore ?: "unknown",
        screenHashAfter = hashAfter ?: "unknown",
        screenChanged = screenChanged,
        appChanged = appChanged,
        tapX = plan.x,
        tapY = plan.y,
        bounds = boundsStr,
        boundsSource = plan.boundsSource,
        targetBounds = targetStr,
        tapInsideTarget = plan.insideTarget
    )
    return toolSuccessResponse(id, buildFallbackResultJson(resultMethod, report))
}

/**
 * 1.0.114 refusal tail: a plan that is [TapPlan.Refused] must NOT dispatch a
 * gesture. Returns the deterministic failure envelope and logs the refusal so
 * the device audit trail shows a refused tap as a refusal, not a silent no-op.
 */
private fun McpHandler.refuseFallback(
    id: String,
    startTime: Long,
    resultMethod: String,
    auditMethod: String,
    elementIdStr: String,
    refusal: TapPlan.Refused
): String {
    val total = System.currentTimeMillis() - startTime
    Log.w(
        TAG,
        "[click_node] coordinate fallback REFUSED (${refusal.reason}): " +
            "liveBounds=${refusal.liveBounds} targetBounds=${refusal.targetBounds} " +
            "— no gesture dispatched"
    )
    auditLogger.log(
        "click_node", false, auditMethod, total, refusal.reason,
        *fallbackAuditPairs(
            elementIdStr, BOUNDS_SOURCE_MATCHED_NODE, refusal.liveBounds,
            0, 0, 0,
            "unknown", "unknown", false,
            refusal.targetBounds, false, false
        )
    )
    return toolSuccessResponse(
        id,
        buildRefusedFallbackJson(
            resultMethod, refusal.reason, elementIdStr,
            refusal.liveBounds, refusal.targetBounds
        )
    )
}

internal fun McpHandler.performClick(
    id: String,
    target: AccessibilityNodeInfo,
    method: String
): String {
    // 1. Direct click on the actionable target
    if (target.isClickable) {
        val clickResult = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clickResult) {
            Log.d(TAG, "Direct click succeeded ($method)")
            return toolSuccessResponse(id, JSONObject().apply {
                put("success", true)
                put("method", "accessibility_click")
                put("resolutionMethod", method)
            }.toString())
        }
    }

    // 2. Clickable ancestor of the target (target might itself be a child)
    val ancestor = NodeResolver.findClickableAncestor(target)
    if (ancestor != null) {
        val ancestorResult = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ancestor.recycle()
        if (ancestorResult) {
            Log.d(TAG, "Clickable ancestor click succeeded ($method)")
            return toolSuccessResponse(id, JSONObject().apply {
                put("success", true)
                put("method", "clickable_ancestor")
                put("resolutionMethod", method)
            }.toString())
        }
    }

    // 3. Coordinate fallback — ONLY with sane, visible bounds from current target.
    // R-1/D2: bounds here are already the target's own rect; convert with NO
    // status-bar subtraction (gesture coords are screen-absolute).
    val bounds = getNodeBounds(target)
    if (bounds != null && NodeResolver.isSaneBounds(bounds)) {
        val screenW = getScreenWidth()
        val screenH = getScreenHeight()
        if (bounds.centerX() in 0..screenW && bounds.centerY() in 0..screenH) {
            val tapResult = run { val (gx, gy) = gesturePointFromBounds(bounds); dispatchTap(gx, gy) }
            if (tapResult) {
                Log.d(TAG, "Coordinate fallback succeeded ($method)")
                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "coordinate_fallback")
                    put("resolutionMethod", method)
                }.toString())
            }
        }
    }

    return toolErrorResponse(id, "action_failed: node resolved but click/ancestor actions " +
        "failed. resolutionMethod=$method")
}

private fun McpHandler.bringIntoViewFresh(
    id: String,
    elementId: ElementId,
    traversal: FreshTraversalResult,
    deadline: Long
): String? {
    val methodStart = System.currentTimeMillis()
    var previousBounds: List<Int>? = null
    var noProgressCount = 0

    val service = AgentAccessibilityService.instance
        ?: return "accessibility_service_not_running"

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    val viewportBottom = metrics.heightPixels

    val node = traversal.matchedNode
    val logical = NodeResolver.getLogicalActionableTarget(node,
        NodeResolver.snapshotManager.getDescriptor(elementId.snapshotId, elementId.nodeId)
            ?: return "element_expired")
    val direction = NodeResolver.determineScrollDirection(node, logical.clickableAncestor, 0, viewportBottom)

    if (direction == ScrollDirection.UNKNOWN) {
        return "target_offscreen_unreachable: cannot determine scroll direction"
    }

    val scrollable = NodeResolver.findScrollableAncestor(node)
    if (scrollable != null) {
        scrollable.recycle()
    }

    for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
        if (System.currentTimeMillis() >= deadline) {
            break
        }

        val screenH = metrics.heightPixels
        val screenW = metrics.widthPixels
        val centerX = screenW / 2
        val gestureResult = performScroll(this, direction, centerX, screenH / 2, screenH)

        Thread.sleep(SCROLL_DELAY_MS)

        val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
        if (reTraversal != null) {
            try {
                if (reTraversal.isVisible) {
                    Log.d(TAG, "[bringIntoViewFresh] scroll succeeded at attempt $attempt")
                    return null
                }

                val currentBounds = reTraversal.bounds
                val currentBoundsList = listOf(currentBounds.left, currentBounds.top, currentBounds.right, currentBounds.bottom)
                if (currentBoundsList == previousBounds) {
                    noProgressCount++
                    if (noProgressCount >= 2) {
                        return "target_offscreen_unreachable: no_scroll_progress"
                    }
                } else {
                    noProgressCount = 0
                    previousBounds = currentBoundsList
                }
            } finally {
                NodeResolver.recycleAll(reTraversal)
            }
        }
    }

    return "target_offscreen_unreachable: target could not be brought into view"
}

internal fun McpHandler.bringIntoView(
    id: String,
    elementId: ElementId,
    node: AccessibilityNodeInfo,
    logical: LogicallyResolved,
    deadline: Long
): String? {
    val methodStart = System.currentTimeMillis()
    var previousBounds: List<Int>? = null
    var noProgressCount = 0

    // Phase 1: ACTION_SHOW_ON_SCREEN (try once, short wait)
    try {
        val actionTarget = logical.node
        if ((actionTarget.actions and ACTION_SHOW_ON_SCREEN) != 0) {
            val showResult = actionTarget.performAction(ACTION_SHOW_ON_SCREEN)
            Log.d(TAG, "[bringIntoView] SHOW_ON_SCREEN: performAction=$showResult")

            Thread.sleep(SHOW_ON_SCREEN_DELAY_MS)

            val service = AgentAccessibilityService.instance
            if (service != null) {
                val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
                if (reTraversal != null) {
                    try {
                        if (reTraversal.isVisible) {
                            Log.d(TAG, "[bringIntoView] SHOW_ON_SCREEN succeeded")
                            return null
                        }
                        if (NodeResolver.isSaneBounds(reTraversal.bounds)) {
                            previousBounds = listOf(reTraversal.bounds.left, reTraversal.bounds.top,
                                reTraversal.bounds.right, reTraversal.bounds.bottom)
                        }
                    } finally {
                        NodeResolver.recycleAll(reTraversal)
                    }
                }
            }
        } else {
            Log.d(TAG, "[bringIntoView] SHOW_ON_SCREEN not supported on target")
        }
    } catch (e: Exception) {
        Log.w(TAG, "[bringIntoView] SHOW_ON_SCREEN failed", e)
    }

    if (System.currentTimeMillis() >= deadline) {
        Log.w(TAG, "[bringIntoView] deadline reached after SHOW_ON_SCREEN")
        return "target_offscreen_unreachable: action_deadline_exceeded after show_on_screen"
    }

    // Phase 2: Directional scrolling with progress detection
    val scrollable = NodeResolver.findScrollableAncestor(node)
    if (scrollable == null) {
        Log.d(TAG, "[bringIntoView] no scrollable ancestor found")
        return "target_offscreen_unreachable: no scrollable ancestor"
    }

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    val viewportTop = 0
    val viewportBottom = metrics.heightPixels

    val direction = NodeResolver.determineScrollDirection(node, logical.clickableAncestor, viewportTop, viewportBottom)
    val targetBounds = Rect()
    (logical.clickableAncestor ?: node).getBoundsInScreen(targetBounds)
    Log.d(TAG, "[bringIntoView] scroll direction=$direction, viewport=[$viewportTop,$viewportBottom], " +
        "targetBounds=[${targetBounds.left},${targetBounds.top},${targetBounds.right},${targetBounds.bottom}]")

    if (direction == ScrollDirection.UNKNOWN) {
        scrollable.recycle()
        Log.d(TAG, "[bringIntoView] UNKNOWN direction")
        return "target_offscreen_unreachable: cannot determine scroll direction"
    }

    Log.d(TAG, "[bringIntoView] scrollable: class=${scrollable.className}, " +
        "scrollable=${scrollable.isScrollable}, childCount=${scrollable.childCount}")

    for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
        if (System.currentTimeMillis() >= deadline) {
            Log.w(TAG, "[bringIntoView] deadline reached at attempt $attempt")
            break
        }

        val scrollStart = System.currentTimeMillis()
        val screenH = metrics.heightPixels
        val screenW = metrics.widthPixels
        val centerX = screenW / 2
        val gestureResult = performScroll(this, direction, centerX, screenH / 2, screenH)

        Thread.sleep(SCROLL_DELAY_MS)

        val service = AgentAccessibilityService.instance
        if (service != null) {
            val reTraversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
            if (reTraversal != null) {
                try {
                    if (reTraversal.isVisible) {
                        scrollable.recycle()
                        Log.d(TAG, "[bringIntoView] scroll succeeded at attempt $attempt")
                        return null
                    }

                    val currentBoundsList = listOf(reTraversal.bounds.left, reTraversal.bounds.top,
                        reTraversal.bounds.right, reTraversal.bounds.bottom)
                    Log.d(TAG, "[bringIntoView] attempt $attempt: gestureResult=$gestureResult, bounds=$currentBoundsList, visible=false")
                    if (currentBoundsList == previousBounds) {
                        noProgressCount++
                        Log.d(TAG, "[bringIntoView] no progress at attempt $attempt, noProgressCount=$noProgressCount")
                        if (noProgressCount >= 2) {
                            scrollable.recycle()
                            Log.d(TAG, "[bringIntoView] stopping: no scroll progress")
                            return "target_offscreen_unreachable: no_scroll_progress"
                        }
                    } else {
                        noProgressCount = 0
                        previousBounds = currentBoundsList
                    }
                } finally {
                    NodeResolver.recycleAll(reTraversal)
                }
            }
        }

        val scrollDuration = System.currentTimeMillis() - scrollStart
        Log.d(TAG, "[bringIntoView] scroll attempt $attempt: ${scrollDuration}ms")
    }

    scrollable.recycle()
    Log.d(TAG, "[bringIntoView] exhausted")
    return "target_offscreen_unreachable: target could not be brought into view"
}

private fun getNodeBounds(node: AccessibilityNodeInfo): android.graphics.Rect? {
    return try {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!NodeResolver.isSaneBounds(rect)) null else rect
    } catch (e: Exception) {
        null
    }
}

internal fun McpHandler.getScreenWidth(): Int {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    return metrics.widthPixels
}

internal fun McpHandler.getScreenHeight(): Int {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    return metrics.heightPixels
}

/**
 * Get navigation bar height in pixels.
 */
private fun McpHandler.getNavigationBarHeight(): Int {
    val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}

/**
 * Convert a bounds rect to screen-absolute gesture coordinates.
 *
 * R-1/D2: NO status-bar subtraction. getBoundsInScreen() is screen-absolute
 * and dispatchGesture() takes screen-absolute coords — proven by swipe
 * parity on-device (same point, one minute apart, same screenHash
 * 96a3fc93c8c30b99): click_node{id 565:32} via coordinate_fallback missed
 * (screenHash unchanged) while swipe{540,1331 -> 544,1335} launched the
 * experience (screenHash c59636ea73b44ae7, "Loading..."). The old
 * centerY-minus-status-bar term shifted every fallback tap up by the
 * status-bar height and is deleted, not kept.
 *
 * Pure (no McpHandler receiver) so it is unit-testable on the JVM. Uses
 * field access only: android.graphics.Rect methods are stubs under unit
 * tests; for screen coords (left+right) shr 1 == centerX().
 */
internal fun gesturePointFromBounds(rect: Rect): Pair<Int, Int> {
    val x = (rect.left + rect.right) shr 1
    val y = (rect.top + rect.bottom) shr 1
    return Pair(x, y.coerceAtLeast(0))
}

internal fun McpHandler.dispatchTap(x: Int, y: Int): Boolean {
    val service = AgentAccessibilityService.instance ?: return false
    val path = Path()
    path.moveTo(x.toFloat(), y.toFloat())
    val gestureBuilder = GestureDescription.Builder()
    gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
    return service.dispatchGesture(gestureBuilder.build(), null, null)
}

internal fun McpHandler.dispatchScrollGesture(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
    val service = AgentAccessibilityService.instance ?: return false
    val path = Path()
    path.moveTo(startX.toFloat(), startY.toFloat())
    path.lineTo(endX.toFloat(), endY.toFloat())
    val gestureBuilder = GestureDescription.Builder()
    gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
    return service.dispatchGesture(gestureBuilder.build(), null, null)
}

internal fun McpHandler.tap(id: String, args: JSONObject): String {
    val x = args.optInt("x", -1)
    val y = args.optInt("y", -1)
    if (x < 0 || y < 0) return toolErrorResponse(id, "Invalid coordinates")

    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val path = Path()
    path.moveTo(x.toFloat(), y.toFloat())

    val gestureBuilder = GestureDescription.Builder()
    gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

    val startTime = System.currentTimeMillis()
    val result = service.dispatchGesture(gestureBuilder.build(), null, null)
    val duration = System.currentTimeMillis() - startTime

    auditLogger.log("tap", result, "gesture", duration, null,
        "x" to x, "y" to y)

    Log.d(TAG, "Tap at ($x, $y): $result")
    return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
}

internal fun McpHandler.swipe(id: String, args: JSONObject): String {
    val startX = args.optInt("startX", -1)
    val startY = args.optInt("startY", -1)
    val endX = args.optInt("endX", -1)
    val endY = args.optInt("endY", -1)
    val durationMs = args.optLong("durationMs", 500)

    if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
        return toolErrorResponse(id, "Invalid coordinates")
    }

    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val path = Path()
    path.moveTo(startX.toFloat(), startY.toFloat())
    path.lineTo(endX.toFloat(), endY.toFloat())

    val gestureBuilder = GestureDescription.Builder()
    gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

    val result = service.dispatchGesture(gestureBuilder.build(), null, null)
    Log.d(TAG, "Swipe ($startX,$startY) -> ($endX,$endY): $result")
    return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
}

internal fun McpHandler.inputText(id: String, args: JSONObject): String {
    val (elementId, error) = parseElementId(args, id, this)
    if (elementId == null) return error!!

    val text = args.optString("text", "")
    if (text.isEmpty()) return toolErrorResponse(id, "Empty text")

    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val traversal = NodeResolver.resolveFresh(service, elementId.snapshotId, elementId.nodeId)
        ?: return toolErrorResponse(id, "element_not_found: element $elementId could not be resolved")

    return try {
        val node = traversal.matchedNode
        if (!node.isEditable) {
            toolErrorResponse(id, "Element $elementId is not editable")
        } else {
            val bundle = android.os.Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val setResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            Log.d(TAG, "Input text to element $elementId: $setResult")
            toolSuccessResponse(id, JSONObject().apply { put("success", setResult) }.toString())
        }
    } finally {
        NodeResolver.recycleAll(traversal)
    }
}

internal fun McpHandler.back(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    Log.d(TAG, "Back: $result")
    return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
}

internal fun McpHandler.home(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    Log.d(TAG, "Home: $result")
    return toolSuccessResponse(id, JSONObject().apply { put("success", result) }.toString())
}

internal fun McpHandler.pressKey(id: String, args: JSONObject): String {
    val key = args.optString("key", "")
    if (key.isEmpty()) return toolErrorResponse(id, "Empty key")

    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    // Map common key names to keycodes
    val keyCode = when (key.lowercase()) {
        "enter", "return" -> android.view.KeyEvent.KEYCODE_ENTER
        "back" -> android.view.KeyEvent.KEYCODE_BACK
        "home" -> android.view.KeyEvent.KEYCODE_HOME
        "tab" -> android.view.KeyEvent.KEYCODE_TAB
        "delete", "del" -> android.view.KeyEvent.KEYCODE_DEL
        "space" -> android.view.KeyEvent.KEYCODE_SPACE
        "escape", "esc" -> android.view.KeyEvent.KEYCODE_ESCAPE
        "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
        "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        else -> {
            try {
                val field = android.view.KeyEvent::class.java.getField("KEYCODE_${key.uppercase()}")
                field.getInt(null)
            } catch (e: Exception) {
                return toolErrorResponse(id, "Unknown key: $key")
            }
        }
    }

    // For enter/return, prefer the IME enter action over a raw key event.
    //
    // A global `input keyevent KEYCODE_ENTER` is injected into the input pipeline
    // and frequently never reaches the view holding IME focus — the field shows
    // focused, the key is reported sent, and nothing is committed. ACTION_IME_ENTER
    // is what tapping the keyboard's Go/Enter key does, so it is the correct
    // semantic action. This mirrors the submit tool.
    if (key.lowercase() in listOf("enter", "return") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val focusedWindow = service.rootInActiveWindow
        if (focusedWindow != null) {
            val focused = focusedWindow.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: focusedWindow.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) {
                val imeResult = focused.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                Log.d(TAG, "Press key: $key (ACTION_IME_ENTER on focused node: $imeResult)")
                if (imeResult) {
                    return toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("key", key)
                        put("method", "ime_enter")
                    }.toString())
                }
            }
        }
    }

    // Fallback: use shell command to send key event
    try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keyCode"))
        process.waitFor()
        Log.d(TAG, "Press key: $key (keyCode=$keyCode) via shell")
        return toolSuccessResponse(id, JSONObject().apply {
            put("success", true)
            put("key", key)
            put("method", "shell")
        }.toString())
    } catch (e: Exception) {
        Log.e(TAG, "Failed to press key: $key", e)
        return toolErrorResponse(id, "Failed to press key: ${e.message}")
    }
}

internal fun McpHandler.submit(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val root = service.rootInActiveWindow
        ?: return toolErrorResponse(id, "No active window")

    // Locate the text-entry target. FOCUS_INPUT is the correct focus type for
    // editable fields; fall back to accessibility focus, then a tree scan.
    val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        ?: findFirstEditable(root)

    // Attempt 1 — IME enter action (API 30+). This is the general, app-agnostic
    // path: it performs exactly what tapping the keyboard's "Go"/"Enter" key does,
    // so it works for apps that ignore synthetic key events (e.g. Chrome).
    if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            Log.d(TAG, "Submit: via ACTION_IME_ENTER")
            return toolSuccessResponse(id, JSONObject().apply {
                put("success", true)
                put("method", "ime_enter")
            }.toString())
        }
    }

    // Attempt 2 — any node on screen advertising an IME enter action, or a
    // clickable node whose label reads as a submit control.
    val buttons = mutableListOf<AccessibilityNodeInfo>()
    findSubmitButtons(root, buttons)
    for (button in buttons) {
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Submit: clicked button '${button.text ?: button.contentDescription}'")
            return toolSuccessResponse(id, JSONObject().apply {
                put("success", true)
                put("method", "click_button")
                put("label", (button.text ?: button.contentDescription)?.toString() ?: "")
            }.toString())
        }
    }

    // Attempt 3 — click the input node itself (some apps submit on click).
    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        Log.d(TAG, "Submit: clicked focused node")
        return toolSuccessResponse(id, JSONObject().apply {
            put("success", true)
            put("method", "click_input")
        }.toString())
    }

    // Attempt 4 — tap the trailing edge of the input's own bounds. Many apps draw
    // the submit/search affordance inside the right end of the field.
    if (target != null) {
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val tapX = bounds.right - (bounds.width() * 0.08f).coerceAtLeast(24f)
            val tapY = bounds.exactCenterY().toFloat()
            val path = android.graphics.Path().apply { moveTo(tapX, tapY) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            val ok = service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "Submit: tapped field trailing edge at $tapX,$tapY -> $ok")
            if (ok) {
                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "tap_field_edge")
                }.toString())
            }
        }
    }

    return toolErrorResponse(id, "No submit affordance found. Try press_key enter, or click the search suggestion directly.")
}

private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isEditable && node.isEnabled) return node
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val found = findFirstEditable(child)
        if (found != null) return found
    }
    return null
}

private fun findSubmitButtons(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
    // Strong, language-agnostic signal: a node that advertises an IME enter
    // action is by definition the submit target. No label matching required.
    val advertisesImeEnter = node.actionList.any {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
    }
    if (advertisesImeEnter && node.isEnabled) {
        results.add(node)
    }

    val text = node.text?.toString()?.lowercase() ?: ""
    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
    val label = if (text.isNotEmpty()) text else desc

    // Secondary signal: clickable control with a submit-ish label. Kept short —
    // label matching is locale-dependent, so it is a fallback, not the primary path.
    val submitWords = setOf(
        "submit", "search", "go", "send", "ok", "done", "apply", "confirm", "enter",
        "find", "lookup", "continue", "next"
    )
    if (node.isClickable && node.isEnabled && label in submitWords) {
        results.add(node)
    }

    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        findSubmitButtons(child, results)
    }
}
