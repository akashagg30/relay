// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * /mcp/status + auth policy — the diagnostic surface that turns an outage
 * into a 10-second read instead of a guessing game.
 *
 * Everything here is pure: the service collects a [ServerStatusSnapshot] and
 * this file renders it. JVM tests pin the JSON shape, the never-leak-the-token
 * rule, and the auth policy that GET /mcp follows the same rule as POST /mcp.
 */

internal const val UNAUTHORIZED_BODY = """{"error":"Unauthorized"}"""

/** Preferred MCP protocol revision this server speaks. */
internal const val PREFERRED_PROTOCOL_VERSION = "2025-06-18"

internal enum class AuthRequirement { REQUIRED, PUBLIC }

/**
 * R-11: auth policy for every route, in one place.
 *
 * GET /mcp used to answer unauthenticated (200 + an SSE endpoint event) while
 * POST /mcp demanded the bearer token. Same data, same endpoint, one of them
 * open. Both now REQUIRE the token. /health and /mcp/status stay public so a
 * client that is failing auth can still find out WHY.
 */
internal fun authRequirementFor(method: String, path: String): AuthRequirement {
    val normalized = path.substringBefore('?').substringBefore('#').trimEnd('/').ifEmpty { "/" }
    val verb = method.uppercase()
    return when {
        normalized == "/mcp" && (verb == "GET" || verb == "POST") -> AuthRequirement.REQUIRED
        else -> AuthRequirement.PUBLIC
    }
}

/**
 * Non-reversible 12-hex identifier for a token: sha256 hex truncated.
 *
 * Safe to serve unauthenticated — a client hashes the token it holds and
 * compares fingerprints. Same value means "your token is current"; a
 * different value means the phone rotated it. The token itself never leaves
 * the device through this path.
 */
internal fun tokenFingerprint(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(12)
}

internal data class ServerStatusSnapshot(
    val version: String,
    val uptimeMs: Long,
    val port: Int,
    val serverRunning: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityRunning: Boolean,
    val tokenProvided: Boolean,
    val authenticated: Boolean,
    val tokenFingerprint: String,
    val tokenRotationCount: Int,
    val tokenRotatedAtMs: Long,
    val rateLimitPerSecond: Int,
    val rateLimitedClients: Int,
    val approvedClients: Int,
    val tunnelRunning: Boolean,
    val tunnelUrl: String?
)

/**
 * Machine-readable diagnosis codes, in priority order. A client reads
 * `causes` and knows which one to act on without asking a human.
 */
internal fun statusCauses(s: ServerStatusSnapshot): List<String> {
    val causes = mutableListOf<String>()
    if (!s.serverRunning) causes += "server_stopped"
    if (!s.accessibilityEnabled || !s.accessibilityRunning) causes += "accessibility_not_running"
    if (!s.tokenProvided) causes += "token_not_provided"
    if (s.tokenProvided && !s.authenticated) causes += "token_mismatch_or_rotated"
    if (s.rateLimitedClients > 0) causes += "rate_limited"
    return causes
}

internal fun statusHint(causes: List<String>, s: ServerStatusSnapshot): String {
    val hints = causes.map {
        when (it) {
            "server_stopped" -> "the MCP server is not running; open Relay and start it"
            "accessibility_not_running" ->
                "accessibility is off; force-stop Relay, re-open, and re-enable accessibility"
            "token_not_provided" ->
                "send 'Authorization: Bearer <token>'; the token is shown in the Relay app"
            "token_mismatch_or_rotated" ->
                "hash the token you hold and compare with auth.tokenFingerprint; a different " +
                    "value means the phone rotated it (rotationCount=" +
                    "${s.tokenRotationCount}) — re-read it from the Relay app"
            "rate_limited" ->
                "${s.rateLimitedClients} client(s) are at the ${s.rateLimitPerSecond}/s limit; retry after 1s"
            else -> it
        }
    }
    return hints.joinToString(" | ")
}

/** Render /mcp/status. 200 for every caller; the body never contains the token. */
internal fun buildStatusJson(s: ServerStatusSnapshot): String {
    val causes = statusCauses(s)
    return JSONObject().apply {
        put("status", if (causes.isEmpty()) "ok" else "degraded")
        put("version", s.version)
        put("protocolVersion", PREFERRED_PROTOCOL_VERSION)
        put("uptimeSec", s.uptimeMs / 1000)
        put("server", JSONObject().apply {
            put("running", s.serverRunning)
            put("port", s.port)
        })
        put("accessibility", JSONObject().apply {
            put("enabled", s.accessibilityEnabled)
            put("running", s.accessibilityRunning)
        })
        put("auth", JSONObject().apply {
            put("required", true)
            put("tokenProvided", s.tokenProvided)
            put("authenticated", s.authenticated)
            put("tokenFingerprint", s.tokenFingerprint)
            put("rotationCount", s.tokenRotationCount)
            put(
                "lastRotatedAt",
                if (s.tokenRotatedAtMs > 0L) s.tokenRotatedAtMs else JSONObject.NULL
            )
        })
        put("rateLimit", JSONObject().apply {
            put("requestsPerSecond", s.rateLimitPerSecond)
            put("blockedClients", s.rateLimitedClients)
        })
        put("clients", JSONObject().apply {
            put("approved", s.approvedClients)
        })
        // There is no server-side session to be stuck on: every request is
        // authenticated on its own, so a stale session can never be the cause.
        put("session", JSONObject().apply {
            put("mode", "stateless")
            put("lock", "none")
        })
        put("tunnel", JSONObject().apply {
            put("running", s.tunnelRunning)
            put("url", s.tunnelUrl ?: JSONObject.NULL)
        })
        put("causes", JSONArray(causes))
        put("hint", statusHint(causes, s))
    }.toString()
}
