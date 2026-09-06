package com.agent.accessibility.mcp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screenshot tools: capturing the screen and overlaying UI element annotations.
 * Implements take_screenshot and screenshot_with_overlay.
 */

internal fun McpHandler.takeScreenshot(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val startTime = System.currentTimeMillis()

    // Try AccessibilityService.takeScreenshot() first (API 30+, requires flagRequestScreenshot)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val result = takeScreenshotViaAccessibility(service)
        if (result != null) {
            val duration = System.currentTimeMillis() - startTime
            auditLogger.log("take_screenshot", true, "accessibility_api", duration, null)
            Log.d(TAG, "Screenshot via AccessibilityService.takeScreenshot(): ${result.width}x${result.height} in ${duration}ms")
            return toolSuccessResponse(id, result.json.toString())
        }
        Log.w(TAG, "AccessibilityService.takeScreenshot() failed, falling back to screencap")
    }

    // Fallback: use screencap shell command
    val result = takeScreenshotViaShell()
    if (result != null) {
        val duration = System.currentTimeMillis() - startTime
        auditLogger.log("take_screenshot", true, "shell_screencap", duration, null)
        Log.d(TAG, "Screenshot via screencap: ${result.width}x${result.height} in ${duration}ms")
        return toolSuccessResponse(id, result.json.toString())
    }

    val duration = System.currentTimeMillis() - startTime
    auditLogger.log("take_screenshot", false, "all_methods_failed", duration, "All screenshot methods failed")
    return toolErrorResponse(id, "Screenshot failed: all methods returned null. " +
        "Ensure accessibility service is enabled and screencap is available.")
}

internal fun McpHandler.screenshotWithOverlay(id: String): String {
    val service = AgentAccessibilityService.instance
        ?: return toolErrorResponse(id, "Accessibility service not running. Force-stop Relay, re-open, and re-enable accessibility.")

    val startTime = System.currentTimeMillis()

    // Take a screenshot bitmap
    val screenshot = takeScreenshotBitmap(service)
        ?: return toolErrorResponse(id, "Screenshot capture failed. Ensure accessibility service is enabled.")

    // Draw overlays using raw accessibility tree (pixel-accurate bounds)
    val overlaid = drawOverlayFromTree(screenshot, service)

    if (overlaid == null) {
        screenshot.recycle()
        return toolErrorResponse(id, "Could not read accessibility tree for overlay.")
    }

    // Encode to base64
    val base64 = bitmapToBase64(overlaid)
    val duration = System.currentTimeMillis() - startTime

    // Count elements we annotated
    val elementCount = countAnnotatedElements(service)

    auditLogger.log("screenshot_with_overlay", true, "overlay", duration, null,
        "elementCount" to elementCount.toString())

    Log.d(TAG, "Screenshot with overlay: ${overlaid.width}x${overlaid.height}, " +
        "$elementCount elements in ${duration}ms")

    val result = JSONObject().apply {
        put("image", base64)
        put("width", overlaid.width)
        put("height", overlaid.height)
        put("format", "png")
        put("elementsAnnotated", elementCount)
    }

    // Clean up
    screenshot.recycle()
    if (overlaid !== screenshot) overlaid.recycle()

    return toolSuccessResponse(id, result.toString())
}

// ── Screenshot capture methods ──────────────────────────────────────────────

private data class ScreenshotResult(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int
) {
    val json: JSONObject get() = JSONObject().apply {
        put("image", encodeBitmapToBase64(bitmap))
        put("width", width)
        put("height", height)
        put("format", "png")
    }
}

/**
 * Take screenshot via AccessibilityService.takeScreenshot() (API 30+).
 * Returns null if unavailable or failed.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
private fun takeScreenshotViaAccessibility(service: AgentAccessibilityService): ScreenshotResult? {
    val latch = CountDownLatch(1)
    var resultBitmap: Bitmap? = null
    var resultWidth = 0
    var resultHeight = 0

    try {
        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            context.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace
                        )
                        if (bitmap != null) {
                            resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            resultWidth = bitmap.width
                            resultHeight = bitmap.height
                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to wrap screenshot result", e)
                    } finally {
                        result.hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed with errorCode=$errorCode")
                    latch.countDown()
                }
            }
        )

        // Wait up to 3 seconds for the screenshot
        val completed = latch.await(3, TimeUnit.SECONDS)
        if (!completed) {
            Log.w(TAG, "takeScreenshot timed out")
            return null
        }

        val bitmap = resultBitmap ?: return null
        return ScreenshotResult(bitmap, resultWidth, resultHeight)
    } catch (e: Exception) {
        Log.e(TAG, "takeScreenshot via AccessibilityService failed", e)
        return null
    }
}

/**
 * Take screenshot via `screencap` shell command (universal fallback).
 */
private fun takeScreenshotViaShell(): ScreenshotResult? {
    try {
        val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        val inputStream = process.inputStream
        val bytes = inputStream.readBytes()
        val exitCode = process.waitFor()

        if (exitCode != 0 || bytes.isEmpty()) {
            Log.e(TAG, "screencap failed: exitCode=$exitCode, bytes=${bytes.size}")
            return null
        }

        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null

        return ScreenshotResult(bitmap, bitmap.width, bitmap.height)
    } catch (e: Exception) {
        Log.e(TAG, "screencap shell command failed", e)
        return null
    }
}

/**
 * Get a screenshot bitmap using the best available method.
 */
private fun takeScreenshotBitmap(service: AgentAccessibilityService): Bitmap? {
    // Try AccessibilityService.takeScreenshot() first (API 30+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val result = takeScreenshotViaAccessibility(service)
        if (result != null) return result.bitmap
    }

    // Fallback to screencap
    return takeScreenshotViaShell()?.bitmap
}

// ── Overlay drawing ─────────────────────────────────────────────────────────

/**
 * Draw overlay annotations on a screenshot bitmap using raw accessibility node data.
 * Provides pixel-accurate bounding boxes, element IDs, roles, and clickable indicators.
 */
private fun drawOverlayFromTree(screenshot: Bitmap, service: AgentAccessibilityService): Bitmap? {
    val overlay = screenshot.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(overlay)

    // Paint for non-clickable elements
    val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.GREEN
        isAntiAlias = true
    }

    // Paint for clickable elements
    val clickablePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.CYAN
        isAntiAlias = true
    }

    // Label text paint
    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    // Label background paint
    val textBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Clickable indicator paint
    val clickableIndicatorPaint = Paint().apply {
        color = Color.CYAN
        textSize = 24f
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    val rootNode = findForegroundRoot(service) ?: return null

    try {
        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)
        val descriptors = NodeResolver.buildDescriptors(tree)
        val snapshotId = NodeResolver.snapshotManager.store(descriptors, tree.foregroundPackage)

        fun drawNode(node: com.agent.accessibility.model.AccessibilityNodeData, depth: Int) {
            val bounds = Rect(
                node.boundsInScreen.left,
                node.boundsInScreen.top,
                node.boundsInScreen.right,
                node.boundsInScreen.bottom
            )

            // Skip tiny or off-screen elements
            if (bounds.width() < 10 || bounds.height() < 10) {
                for (child in node.children) drawNode(child, depth + 1)
                return
            }

            // Skip invisible elements
            if (!node.visibleToUser) {
                for (child in node.children) drawNode(child, depth + 1)
                return
            }

            // Choose paint based on interactivity
            val paint = when {
                node.clickable -> clickablePaint
                else -> boxPaint
            }

            // Draw bounding box
            canvas.drawRect(bounds, paint)

            // Build label: element ID + short class name + text
            val elementId = ElementId(snapshotId, node.nodeId).encode()
            val shortClass = node.className?.substringAfterLast('.')?.take(12) ?: ""
            val text = node.text ?: node.contentDescription
            val label = buildString {
                append(elementId)
                if (shortClass.isNotEmpty()) append(" $shortClass")
                if (!text.isNullOrBlank()) append(" \"${text.take(18)}\"")
            }

            // Draw label background and text above the bounding box
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize
            val labelX = bounds.left.toFloat()
            val labelY = (bounds.top - 6f).coerceAtLeast(textHeight + 6f)

            canvas.drawRect(
                labelX - 2f,
                labelY - textHeight - 2f,
                labelX + textWidth + 6f,
                labelY + 4f,
                textBgPaint
            )
            canvas.drawText(label, labelX + 2f, labelY, textPaint)

            // Draw clickable indicator in top-right of the bounding box
            if (node.clickable) {
                val indicator = "●CLICK"
                val indicatorWidth = clickableIndicatorPaint.measureText(indicator)
                canvas.drawText(
                    indicator,
                    (bounds.right - indicatorWidth - 4f).coerceAtLeast(labelX),
                    labelY,
                    clickableIndicatorPaint
                )
            }

            // Recurse into children
            for (child in node.children) {
                drawNode(child, depth + 1)
            }
        }

        tree.root?.let { drawNode(it, 0) }
    } finally {
        @Suppress("DEPRECATION")
        rootNode.recycle()
    }

    return overlay
}

/**
 * Count total annotated elements in the current tree.
 */
private fun countAnnotatedElements(service: AgentAccessibilityService): Int {
    val rootNode = findForegroundRoot(service) ?: return 0
    try {
        val tree = com.agent.accessibility.service.AccessibilityTreeReader.readTree(rootNode)
        return tree.totalNodeCount
    } finally {
        @Suppress("DEPRECATION")
        rootNode.recycle()
    }
}

// ── Bitmap encoding ─────────────────────────────────────────────────────────

/**
 * Encode a Bitmap to base64-encoded PNG string.
 */
private fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    val bytes = outputStream.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal fun McpHandler.bitmapToBase64(bitmap: Bitmap): String {
    return encodeBitmapToBase64(bitmap)
}
