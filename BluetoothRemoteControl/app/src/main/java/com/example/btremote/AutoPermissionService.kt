package com.example.btremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoPermissionService : AccessibilityService() {
    private val TAG = "AutoPermissionService"

    companion object {
        // Specific permission dialog package names
        private val PERMISSION_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.permissioncontroller"
        )
        
        // Keywords that indicate permission dialogs
        private val PERMISSION_KEYWORDS = setOf(
            "allow", "permit", "grant"
        )
        
        // Keywords to avoid (not permission dialogs)
        private val AVOID_KEYWORDS = setOf(
            "cancel", "deny", "don't allow", "reject"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
        
        val info = AccessibilityServiceInfo().apply {
            // Only listen to permission controller packages
            packageNames = PERMISSION_PACKAGES.toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val packageName = event.packageName?.toString() ?: return
            
            // ONLY process events from permission controller packages
            if (!PERMISSION_PACKAGES.contains(packageName)) {
                return
            }
            
            val rootNode = rootInActiveWindow ?: return
            
            // Double-check this is a permission dialog
            if (isPermissionDialog(rootNode)) {
                Log.i(TAG, "Permission dialog detected for BTRemote, auto-clicking Allow...")
                clickAllowButton(rootNode)
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event", e)
        }
    }

    private fun isPermissionDialog(node: AccessibilityNodeInfo): Boolean {
        val nodeText = getNodeText(node).lowercase()
        
        // Must contain permission-related keywords
        val hasPermissionKeyword = PERMISSION_KEYWORDS.any { nodeText.contains(it) }
        
        // Must NOT contain avoid keywords
        val hasAvoidKeyword = AVOID_KEYWORDS.any { nodeText.contains(it) }
        
        // Must mention our app name or package
        val mentionsOurApp = nodeText.contains("btremote") || 
                            nodeText.contains("com.example.btremote")
        
        return hasPermissionKeyword && !hasAvoidKeyword && mentionsOurApp
    }

    private fun clickAllowButton(node: AccessibilityNodeInfo) {
        // Try to find "Allow" button specifically
        val allowButton = findButtonByText(node, listOf("allow", "permit", "grant"))
        
        if (allowButton != null) {
            val clicked = allowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Clicked Allow button: $clicked")
            allowButton.recycle()
            return
        }
        
        // Fallback: try by resource ID
        val buttonIds = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.google.android.permissioncontroller:id/permission_allow_button"
        )
        
        for (id in buttonIds) {
            val button = findNodeById(node, id)
            if (button != null) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Clicked button by ID: $id")
                button.recycle()
                return
            }
        }
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        // Only look for Button or clickable elements
        if ((node.className == "android.widget.Button" || node.isClickable) && node.isEnabled) {
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            if (keywords.any { nodeText.contains(it) }) {
                return node
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findButtonByText(child, keywords)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        
        return null
    }

    private fun findNodeById(node: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == resourceId) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeById(child, resourceId)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        
        return null
    }

    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val text = StringBuilder()
        
        node.text?.let { text.append(it).append(" ") }
        node.contentDescription?.let { text.append(it).append(" ") }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            text.append(getNodeText(child))
            child.recycle()
        }
        
        return text.toString()
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed")
    }
}
