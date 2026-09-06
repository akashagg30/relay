package com.agent.accessibility.mcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Checks MIUI-specific permissions that affect app launching.
 * MIUI blocks background activity starts unless the user explicitly grants permission.
 */
object MiuiPermissionHelper {

    private const val TAG = "MiuiPermission"

    /**
     * Check if the device is running MIUI.
     */
    fun isMiui(context: Context): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val miuiVersion = get.invoke(null, "ro.miui.ui.version.name", "") as String
            miuiVersion.isNotEmpty()
        } catch (e: Exception) {
            // Fallback: check for MIUI properties
            try {
                val prop = Class.forName("android.os.SystemProperties")
                val get = prop.getMethod("get", String::class.java)
                val versionCode = get.invoke(null, "ro.miui.ui.version.code") as? String
                versionCode?.isNotEmpty() == true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Check if "Start in Background" permission is likely enabled.
     * We can't directly read the permission, but we can test by trying to start an activity.
     * This returns true if the app CAN start activities (or if it's not MIUI).
     */
    fun canStartActivities(context: Context): Boolean {
        if (!isMiui(context)) return true
        // On MIUI, we assume permission is needed unless proven otherwise
        // The actual check happens when launch_app fails
        return false
    }

    /**
     * Open MIUI permission settings for this app.
     * Returns true if the settings page was opened.
     */
    fun openPermissionSettings(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened MIUI permission settings")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open MIUI permission settings", e)
            // Try alternative path
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                    )
                    putExtra("extra_pkgname", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d(TAG, "Opened MIUI permission settings (alternative)")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open MIUI permission settings (alternative)", e2)
                false
            }
        }
    }

    /**
     * Get a user-friendly message about the MIUI permission issue.
     */
    fun getPermissionMessage(): String {
        return "MIUI detected: Enable 'Start in background' permission for Relay.\n" +
                "Go to Settings → Apps → Relay → App permissions → Enable 'Start in background'"
    }
}
