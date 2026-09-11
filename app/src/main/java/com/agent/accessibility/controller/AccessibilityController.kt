// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.SnapshotSource
import com.agent.accessibility.model.TreeSnapshot
import com.agent.accessibility.service.AgentAccessibilityService

class AccessibilityController(private val context: Context) {

    fun isServiceEnabled(): Boolean {
        val serviceName = "${context.packageName}/${context.packageName}.service.AgentAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun getService(): AgentAccessibilityService? {
        return AgentAccessibilityService.instance
    }

    fun clickNode(nodeId: Int): Boolean {
        val service = getService() ?: return false
        val rootNode = service.rootInActiveWindow ?: return false

        val node = findNodeById(rootNode, nodeId)
        if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) return true

            // Try clicking parent if node is not clickable
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val parentResult = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    return parentResult
                }
                val temp = parent.parent
                parent.recycle()
                parent = temp
            }
        }

        rootNode.recycle()
        return false
    }

    fun tap(x: Int, y: Int): Boolean {
        val service = getService() ?: return false

        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

        return service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        val service = getService() ?: return false

        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

        return service.dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun back(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun home(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun inputText(nodeId: Int, text: String): Boolean {
        val service = getService() ?: return false
        val rootNode = service.rootInActiveWindow ?: return false

        val node = findNodeById(rootNode, nodeId)
        if (node != null && node.isEditable) {
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            node.recycle()
            rootNode.recycle()
            return result
        }

        rootNode.recycle()
        return false
    }

    fun captureSnapshot(source: SnapshotSource = SnapshotSource.MANUAL): TreeSnapshot? {
        return getService()?.captureSnapshot(source)
    }

    fun getSnapshotCount(): Int {
        return getService()?.snapshots?.size ?: 0
    }

    fun clearSnapshots() {
        getService()?.clearSnapshots()
    }

    fun getLatestExternalSnapshot(): TreeSnapshot? {
        return getService()?.getLatestExternalSnapshot()
    }

    private fun findNodeById(node: AccessibilityNodeInfo, targetId: Int, currentId: Int = 0): AccessibilityNodeInfo? {
        if (currentId == targetId) {
            return node
        }

        var childId = currentId + 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (childId == targetId) {
                return child
            }
            val result = findNodeById(child, targetId, childId)
            if (result != null) return result
            childId += countDescendants(child) + 1
            child.recycle()
        }

        return null
    }

    private fun countDescendants(node: AccessibilityNodeInfo): Int {
        var count = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += 1 + countDescendants(child)
            child.recycle()
        }
        return count
    }
}
