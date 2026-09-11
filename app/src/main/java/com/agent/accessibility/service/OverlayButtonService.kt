// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import com.agent.accessibility.MainActivity
import com.agent.accessibility.mcp.McpServerService

/**
 * Floating overlay button to quickly toggle the MCP server on/off.
 * Shows a draggable circle that reflects server state (green = running, red = stopped).
 */
class OverlayButtonService : android.app.Service() {

    companion object {
        private const val BUTTON_SIZE_DP = 56
        private const val MARGIN_DP = 16

        var isRunning = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createOverlay()
    }

    override fun onDestroy() {
        isRunning = false
        removeOverlay()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val buttonSize = (BUTTON_SIZE_DP * density).toInt()
        val margin = (MARGIN_DP * density).toInt()

        // Create the floating button view
        val buttonView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
        }

        // Create the circle background
        val circle = android.widget.ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
            setImageDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (McpServerService.isRunning) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            })
        }
        buttonView.addView(circle)

        // Add "MCP" text on the button
        val textView = android.widget.TextView(this).apply {
            text = "MCP"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        buttonView.addView(textView)

        overlayView = buttonView

        // Window layout params
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            y = margin
        }

        // Touch handling for drag and tap
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        buttonView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    layoutParams!!.x = initialX + dx.toInt()
                    layoutParams!!.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleMcpServer(circle, textView)
                    }
                    true
                }
                else -> false
            }
        }

        // Add to window
        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
        }
    }

    private fun toggleMcpServer(circle: android.widget.ImageView, textView: android.widget.TextView) {
        val intent = Intent(this, McpServerService::class.java)
        if (McpServerService.isRunning) {
            intent.action = "STOP"
            startService(intent)
            circle.setImageDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF44336.toInt())
            })
            textView.text = "MCP"
        } else {
            intent.action = "START"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            circle.setImageDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4CAF50.toInt())
            })
            textView.text = "MCP"
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
    }
}
