package com.agent.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.AccessibilityTreeData
import com.agent.accessibility.model.SnapshotSource
import com.agent.accessibility.model.TreeSnapshot
import java.util.concurrent.CopyOnWriteArrayList

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
        private const val OUR_PACKAGE = "com.agent.accessibility"

        var instance: AgentAccessibilityService? = null
            private set
    }

    private val _snapshots = CopyOnWriteArrayList<TreeSnapshot>()
    val snapshots: List<TreeSnapshot> get() = _snapshots.toList()

    private var lastExternalSnapshot: TreeSnapshot? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (packageName == OUR_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                captureSnapshot(SnapshotSource.WINDOW_CHANGED)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService destroyed")
    }

    fun captureSnapshot(source: SnapshotSource = SnapshotSource.MANUAL): TreeSnapshot? {
        val rootNode = rootInActiveWindow ?: return null

        val tree = AccessibilityTreeReader.readTree(rootNode)
        val snapshot = TreeSnapshot(tree = tree, source = source)

        _snapshots.add(snapshot)

        if (tree.foregroundPackage != OUR_PACKAGE) {
            lastExternalSnapshot = snapshot
            Log.d(TAG, "Captured external snapshot: ${tree.foregroundPackage} (${tree.totalNodeCount} nodes)")
        }

        return snapshot
    }

    fun getLatestExternalSnapshot(): TreeSnapshot? = lastExternalSnapshot

    fun getLatestSnapshot(): TreeSnapshot? = _snapshots.lastOrNull()

    fun clearSnapshots() {
        _snapshots.clear()
        lastExternalSnapshot = null
    }

    fun readCurrentTree(): AccessibilityTreeData {
        val rootNode = rootInActiveWindow
        return AccessibilityTreeReader.readTree(rootNode)
    }
}
