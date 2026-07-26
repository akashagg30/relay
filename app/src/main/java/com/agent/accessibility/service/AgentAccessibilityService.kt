package com.agent.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events can be processed here later for real-time monitoring
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun getTreeReader(): AccessibilityTreeReader = AccessibilityTreeReader

    fun readCurrentTree(): com.agent.accessibility.model.AccessibilityTreeData {
        val rootNode = rootInActiveWindow
        return AccessibilityTreeReader.readTree(rootNode)
    }
}
