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

            val clickResult = actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val total = System.currentTimeMillis() - startTime
            Log.d(TAG, "[click_node] total=${total}ms (visible click), result=$clickResult")

            if (clickResult) {
                val total = System.currentTimeMillis() - startTime
                auditLogger.log("click_node", true, "accessibility_click", total, null,
                    "elementId" to elementId.encode(), "resolutionMethod" to traversal.method)

                // Post-click verification: compare package name to detect screen change
                Thread.sleep(300)
                val newRoot = findForegroundRoot(service)
                val oldPkg = traversal.matchedNode?.packageName?.toString()
                val newPkg = newRoot?.packageName?.toString()
                val screenChanged = newPkg != null && newPkg != oldPkg

                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "accessibility_click")
                    put("resolutionMethod", traversal.method)
                    put("screenChanged", screenChanged)
                }.toString())
            }

            val bounds = traversal.bounds
            if (NodeResolver.isSaneBounds(bounds)) {
                val screenW = getScreenWidth()
                val screenH = getScreenHeight()
                if (bounds.centerX() in 0..screenW && bounds.centerY() in 0..screenH) {
                    val tapResult = run { val (gx, gy) = boundsToGestureCoords(bounds); dispatchTap(gx, gy) }
                    if (tapResult) {
                        Log.d(TAG, "Coordinate fallback succeeded")
                        // Post-click verification
                        Thread.sleep(300)
                        val newRoot = findForegroundRoot(service)
                        val oldPkg = traversal.matchedNode?.packageName?.toString()
                        val newPkg = newRoot?.packageName?.toString()
                        val screenChanged = newPkg != null && newPkg != oldPkg
                        if (!screenChanged) {
                            Log.w(TAG, "Coordinate tap succeeded but screen didn't change")
                        }
                        return toolSuccessResponse(id, JSONObject().apply {
                            put("success", true)
                            put("method", "coordinate_fallback")
                            put("screenChanged", screenChanged)
                        }.toString())
                    }
                }
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

            val bounds = reTraversal.bounds
            if (NodeResolver.isSaneBounds(bounds)) {
                val tapResult = run { val (gx, gy) = boundsToGestureCoords(bounds); dispatchTap(gx, gy) }
                if (tapResult) {
                    return toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("method", "coordinate_fallback_scrolled")
                    }.toString())
                }
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

    // 3. Coordinate fallback — ONLY with sane, visible bounds from current target
    val bounds = getNodeBounds(target)
    if (bounds != null && NodeResolver.isSaneBounds(bounds)) {
        val screenW = getScreenWidth()
        val screenH = getScreenHeight()
        if (bounds.centerX() in 0..screenW && bounds.centerY() in 0..screenH) {
            val tapResult = run { val (gx, gy) = boundsToGestureCoords(bounds); dispatchTap(gx, gy) }
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
 * Get status bar height in pixels.
 */
private fun McpHandler.getStatusBarHeight(): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}

/**
 * Get navigation bar height in pixels.
 */
private fun McpHandler.getNavigationBarHeight(): Int {
    val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}

/**
 * Convert accessibility bounds center to gesture coordinates.
 * Accessibility bounds include status bar offset, gesture coordinates don't.
 */
private fun McpHandler.boundsToGestureCoords(bounds: Rect): Pair<Int, Int> {
    val statusBarHeight = getStatusBarHeight()
    val x = bounds.centerX()
    val y = bounds.centerY() - statusBarHeight
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

    // For enter key, try clicking the focused node
    if (key.lowercase() in listOf("enter", "return")) {
        val focusedWindow = service.rootInActiveWindow
        if (focusedWindow != null) {
            val focused = focusedWindow.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) {
                val clickResult = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Press key: $key (click on focused node: $clickResult)")
                if (clickResult) {
                    return toolSuccessResponse(id, JSONObject().apply {
                        put("success", true)
                        put("key", key)
                        put("method", "click_focused")
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

    // Find the focused node
    val focusedWindow = service.rootInActiveWindow
    if (focusedWindow == null) {
        return toolErrorResponse(id, "No active window")
    }

    val focused = focusedWindow.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    if (focused == null) {
        return toolErrorResponse(id, "No focused element found")
    }

    // Try clicking the focused node
    val clickResult = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    if (clickResult) {
        Log.d(TAG, "Submit: clicked focused node")
        return toolSuccessResponse(id, JSONObject().apply {
            put("success", true)
            put("method", "click_focused")
        }.toString())
    }

    // Try finding and clicking a submit/search/go button
    val rootNode = service.rootInActiveWindow
    if (rootNode != null) {
        // Look for buttons with submit/search/go text
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        findSubmitButtons(rootNode, buttons)
        
        for (button in buttons) {
            val result = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                Log.d(TAG, "Submit: clicked button '${button.text}'")
                return toolSuccessResponse(id, JSONObject().apply {
                    put("success", true)
                    put("method", "click_button")
                    put("button", button.text?.toString() ?: "")
                }.toString())
            }
        }
    }

    // Fallback: tap the search button area (top-right of URL bar)
    val bounds = Rect()
    focused.getBoundsInScreen(bounds)
    val searchX = bounds.width() - 50
    val searchY = bounds.centerY()
    val path = android.graphics.Path()
    path.moveTo(searchX.toFloat(), searchY.toFloat())
    val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
    gestureBuilder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
    val gestureResult = service.dispatchGesture(gestureBuilder.build(), null, null)
    Log.d(TAG, "Submit: tapped search area at $searchX,$searchY")
    return toolSuccessResponse(id, JSONObject().apply {
        put("success", gestureResult)
        put("method", "tap_search_area")
    }.toString())
}

private fun findSubmitButtons(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
    val text = node.text?.toString()?.lowercase() ?: ""
    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
    
    if (node.isClickable && (text in listOf("submit", "search", "go", "enter", "send", "ok", "done") ||
        desc in listOf("submit", "search", "go", "enter", "send", "ok", "done"))) {
        results.add(node)
    }
    
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        findSubmitButtons(child, results)
    }
}
