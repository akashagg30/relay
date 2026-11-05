// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Token lifecycle, device-free on purpose.
 *
 * The previous implementation cached the token in a `by lazy` property, so
 * `regenerateToken()` wrote the new value to prefs while `validate()` kept
 * comparing against the old one until the process restarted. That made token
 * rotation look like "the client suddenly can't authenticate" with no way to
 * tell it apart from a session or rate-limit problem. Mutating state in one
 * place fixes it and lets JVM tests drive the whole lifecycle.
 */
internal class TokenRotationState(
    initialToken: String,
    initialRotationCount: Int = 0,
    initialRotatedAtMs: Long = 0L,
    private val generate: () -> String = { randomToken() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    var token: String = initialToken
        private set

    @Volatile
    var rotationCount: Int = initialRotationCount
        private set

    @Volatile
    var lastRotatedAtMs: Long = initialRotatedAtMs
        private set

    val lastRotatedAt: Long?
        get() = lastRotatedAtMs.takeIf { it > 0L }

    fun rotate(): String {
        val next = generate()
        token = next
        rotationCount += 1
        lastRotatedAtMs = clock()
        return next
    }

    fun validate(authHeader: String?): Boolean {
        if (authHeader.isNullOrBlank()) return false
        // RFC 6750: the credentials MUST carry the scheme. A bare token in the
        // header is rejected rather than silently accepted, so a client that
        // configured the wrong header format gets a 401 it can diagnose via
        // /mcp/status instead of a working-but-undefined path.
        val bearer = Regex("^Bearer\\s+(\\S+)$", RegexOption.IGNORE_CASE)
            .find(authHeader.trim()) ?: return false
        return bearer.groupValues[1] == token
    }
}

/** 128-bit hex token, the format the Relay UI shows. */
internal fun randomToken(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

class AuthManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE)

    private val state: TokenRotationState = TokenRotationState(
        initialToken = prefs.getString("auth_token", null) ?: generateAndStoreToken(),
        initialRotationCount = prefs.getInt("auth_rotation_count", 0),
        initialRotatedAtMs = prefs.getLong("auth_rotated_at", 0L)
    )

    val token: String
        get() = state.token

    /** How many times the token has been rotated (persisted across restarts). */
    val rotationCount: Int
        get() = state.rotationCount

    /** Epoch millis of the last rotation, or 0 when it has never rotated. */
    val lastRotatedAtMs: Long
        get() = state.lastRotatedAtMs

    /**
     * Stable, non-reversible 12-hex identifier for the CURRENT token. A client
     * can hash the token it holds and compare: same fingerprint = token is
     * current; different = the phone rotated it. Safe to serve in /mcp/status.
     */
    fun fingerprint(): String = tokenFingerprint(state.token)

    private fun generateAndStoreToken(): String {
        val newToken = randomToken()
        prefs.edit().putString("auth_token", newToken).apply()
        return newToken
    }

    fun regenerateToken(): String {
        val newToken = state.rotate()
        prefs.edit()
            .putString("auth_token", newToken)
            .putInt("auth_rotation_count", state.rotationCount)
            .putLong("auth_rotated_at", state.lastRotatedAtMs)
            .apply()
        return newToken
    }

    fun validate(authHeader: String?): Boolean = state.validate(authHeader)
}
