// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.content.Context
import android.content.SharedPreferences
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-level access policy.
 *
 * Controls which apps the MCP agent may read from and act on. Blocked apps are
 * BOTH unreadable and untouchable — excluding an app but still letting the agent
 * screenshot and parse its screen would leak the same data that acting would.
 *
 * [isAllowed] is the single source of truth. Two sets are invariant and cannot
 * be edited by the user:
 *
 *  - [alwaysBlocked] — Relay's own package. If the agent could drive Relay's UI
 *    it could escalate privilege: disable the accessibility service, read the
 *    auth token off the debug screen, or tap through and rewrite this policy.
 *    That would make "the agent can never write policy" enforceable on the API
 *    surface but false in practice.
 *  - [alwaysAllowed] — SystemUI, needed for the notification shade.
 *
 * Policy is deliberately READ-ONLY over MCP. There is no set_app_policy tool.
 * Only the phone UI can change it.
 */
enum class PolicyMode { ALL, ALLOWLIST }

object AppPolicy {

    private const val PREFS = "mcp_policy"
    private const val KEY_MODE = "mode"
    private const val KEY_PROTECTED = "protected"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_SEEDED = "seeded"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- mode ---

    fun mode(context: Context): PolicyMode {
        val raw = prefs(context).getString(KEY_MODE, PolicyMode.ALL.name)
        return try {
            PolicyMode.valueOf(raw ?: PolicyMode.ALL.name)
        } catch (_: IllegalArgumentException) {
            PolicyMode.ALL
        }
    }

    fun setMode(context: Context, mode: PolicyMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    // --- user-editable sets ---

    /**
     * Packages the agent may never touch. Always enforced, in both modes.
     * Seeded on first read with [sensitiveDefaults].
     */
    fun protectedPackages(context: Context): Set<String> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SEEDED, false)) {
            // First run: seed the sensitive list and mark it done, so a user who
            // deliberately empties the list does not get it silently repopulated.
            p.edit()
                .putStringSet(KEY_PROTECTED, sensitiveDefaults())
                .putBoolean(KEY_SEEDED, true)
                .apply()
        }
        return p.getStringSet(KEY_PROTECTED, emptySet())?.toSet() ?: emptySet()
    }

    fun setProtectedPackages(context: Context, pkgs: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_PROTECTED, pkgs.toSet())
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    /** Only consulted when [mode] is [PolicyMode.ALLOWLIST]. */
    fun allowedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet() ?: emptySet()

    fun setAllowedPackages(context: Context, pkgs: Set<String>) {
        prefs(context).edit().putStringSet(KEY_ALLOWED, pkgs.toSet()).apply()
    }

    // --- invariant sets ---

    /**
     * Never actionable, regardless of user settings.
     *
     * Blocking Relay itself is what makes the read-only-policy guarantee real:
     * without it the agent could simply drive Relay's own settings UI and change
     * the policy by hand.
     */
    fun alwaysBlocked(context: Context): Set<String> = setOf(context.packageName)

    /** Can never be blocked — blocking SystemUI breaks the notification shade. */
    fun alwaysAllowed(context: Context): Set<String> = setOf("com.android.systemui")

    // --- the decision ---

    /**
     * Decision order matters:
     *  1. unknown package    -> allow  (a null foreground must not brick the tool)
     *  2. alwaysBlocked      -> DENY   (highest priority, wins over everything)
     *  3. alwaysAllowed      -> allow
     *  4. protected          -> deny
     *  5. ALLOWLIST mode     -> must be explicitly listed
     *  6. otherwise          -> allow
     */
    fun isAllowed(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg in alwaysBlocked(context)) return false
        if (pkg in alwaysAllowed(context)) return true
        if (pkg in protectedPackages(context)) return false
        if (mode(context) == PolicyMode.ALLOWLIST) return pkg in allowedPackages(context)
        return true
    }

    /**
     * Stable prefix the calling agent can pattern-match on, so it understands it
     * hit a user policy wall rather than a bug and stops retrying.
     */
    fun blockMessage(pkg: String?): String =
        "policy_blocked: ${pkg ?: "unknown"} is excluded by your app policy. " +
            "Change it in the Relay app under App Access."

    /**
     * Sensible privacy defaults, enabled on first run.
     *
     * Deliberately excludes com.google.android.gms — Play Services backs a large
     * number of unrelated apps, so blocking it would break far more than it protects.
     */
    fun sensitiveDefaults(): Set<String> = setOf(
        // Password managers
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.keepassdroid",
        // Authenticators / 2FA
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.microsoft.authenticator",
        "com.azure.authenticator",
        "org.fedorahosted.freeotp",
        "com.duosecurity.duomobile",
        // UPI / payments (India)
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.dreamplug.androidapp",
        "com.downy.app",
        // Banking (India)
        "com.sbi.lotusintouch",
        "com.snapwork.hdfc",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.idfcfirstbank.optimus",
        "com.bankofbaroda.mconnect"
    )
}

/**
 * Tools that require their target app to pass the policy check.
 *
 * back / home / current_app are deliberately NOT here. If the agent lands inside
 * a blocked app it must still be able to escape — otherwise the phone is trapped
 * and the user has to force-stop Relay by hand.
 */
internal val POLICY_GATED_TOOLS = setOf(
    "click_node",
    "swipe",
    "input_text",
    "press_key",
    "submit",
    "observe",
    "find",
    "scroll_until",
    "get_screen_state",
    "take_screenshot",
    "screenshot_with_overlay",
    "launch_app",
    "open_app"
)

/**
 * The package a gated tool would act on.
 *
 * launch_app / open_app name their target explicitly; every other gated tool acts
 * on whatever is currently in the foreground.
 *
 * Returns null when it cannot be determined — callers treat null as "allow", so an
 * unresolved target never bricks a tool.
 */
internal fun McpHandler.policyTargetPackage(toolName: String, args: JSONObject): String? {
    return when (toolName) {
        "launch_app" -> args.optString("packageName", "").ifBlank { null }

        "open_app" -> {
            val name = args.optString("name", "")
            if (name.isBlank()) {
                null
            } else {
                (appRegistry.findBestMatch(name) as? AppMatch.Single)?.app?.packageName
            }
        }

        else -> {
            val service = AgentAccessibilityService.instance ?: return null
            findForegroundRoot(service)?.packageName?.toString()
        }
    }
}

/**
 * Read-only view of the policy — deliberately the ONLY policy tool exposed over MCP.
 *
 * There is no set_app_policy, so neither an agent nor anyone holding the bearer
 * token can widen its own access. Changes happen only in the phone UI.
 */
internal fun McpHandler.getAppPolicy(id: String): String {
    return toolSuccessResponse(id, JSONObject().apply {
        put("mode", AppPolicy.mode(context).name)
        put("protected", JSONArray(AppPolicy.protectedPackages(context).sorted()))
        put("allowed", JSONArray(AppPolicy.allowedPackages(context).sorted()))
        put("alwaysAllowed", JSONArray(AppPolicy.alwaysAllowed(context).sorted()))
        put("alwaysBlocked", JSONArray(AppPolicy.alwaysBlocked(context).sorted()))
    }.toString())
}
