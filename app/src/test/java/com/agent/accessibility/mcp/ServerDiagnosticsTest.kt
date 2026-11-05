// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production hygiene for the HTTP surface:
 *
 *  - R-11: GET /mcp follows the same auth rule as POST /mcp (it used to answer
 *    unauthenticated while its POST sibling demanded a token).
 *  - /mcp/status is the 10-second diagnosis: it must say WHY a client is
 *    failing (token rotated / rate limited / accessibility down) without ever
 *    disclosing the token.
 *  - Token rotation must actually take effect (the old lazy-cached token kept
 *    validating after a rotation).
 */
class ServerDiagnosticsTest {

    private fun snapshot(
        tokenProvided: Boolean = true,
        authenticated: Boolean = true,
        accessibilityEnabled: Boolean = true,
        accessibilityRunning: Boolean = true,
        rateLimitedClients: Int = 0,
        rotationCount: Int = 0
    ) = ServerStatusSnapshot(
        version = "1.0.117",
        uptimeMs = 90_000,
        port = 8765,
        serverRunning = true,
        accessibilityEnabled = accessibilityEnabled,
        accessibilityRunning = accessibilityRunning,
        tokenProvided = tokenProvided,
        authenticated = authenticated,
        tokenFingerprint = tokenFingerprint("secret-token-value"),
        tokenRotationCount = rotationCount,
        tokenRotatedAtMs = if (rotationCount > 0) 1_700_000_000_000L else 0L,
        rateLimitPerSecond = 30,
        rateLimitedClients = rateLimitedClients,
        approvedClients = 2,
        tunnelRunning = false,
        tunnelUrl = null
    )

    // ---- R-11 auth policy ----

    @Test
    fun `GET and POST mcp require the token`() {
        assertEquals(AuthRequirement.REQUIRED, authRequirementFor("GET", "/mcp"))
        assertEquals(AuthRequirement.REQUIRED, authRequirementFor("POST", "/mcp"))
        assertEquals(AuthRequirement.REQUIRED, authRequirementFor("GET", "/mcp/"))
        assertEquals(AuthRequirement.REQUIRED, authRequirementFor("POST", "/mcp?trace=1"))
    }

    @Test
    fun `diagnostics stay public so a failing client can be diagnosed`() {
        assertEquals(AuthRequirement.PUBLIC, authRequirementFor("GET", "/health"))
        assertEquals(AuthRequirement.PUBLIC, authRequirementFor("GET", "/mcp/status"))
        assertEquals(AuthRequirement.PUBLIC, authRequirementFor("GET", "/"))
    }

    // ---- status document ----

    @Test
    fun `healthy status reports ok and names nothing as a cause`() {
        val body = JSONObject(buildStatusJson(snapshot()))
        assertEquals("ok", body.getString("status"))
        assertEquals(0, body.getJSONArray("causes").length())
        assertEquals("1.0.117", body.getString("version"))
        assertEquals(8765, body.getJSONObject("server").getInt("port"))
        assertEquals(90, body.getInt("uptimeSec"))
        // No server-side session can be stale: say so explicitly.
        assertEquals("stateless", body.getJSONObject("session").getString("mode"))
        assertEquals("none", body.getJSONObject("session").getString("lock"))
        assertTrue(body.getJSONObject("auth").getBoolean("authenticated"))
    }

    @Test
    fun `a wrong token is diagnosed as rotation, not as a mystery 401`() {
        val body = JSONObject(buildStatusJson(snapshot(authenticated = false, rotationCount = 3)))
        assertEquals("degraded", body.getString("status"))
        val causes = body.getJSONArray("causes").toList()
        assertTrue(causes.contains("token_mismatch_or_rotated"))
        val hint = body.getString("hint")
        assertTrue("hint names the fingerprint: $hint", hint.contains("tokenFingerprint"))
        assertTrue("hint carries the rotation count: $hint", hint.contains("rotationCount=3"))
        assertEquals(3, body.getJSONObject("auth").getInt("rotationCount"))
        assertNotEquals(JSONObject.NULL, body.getJSONObject("auth").get("lastRotatedAt"))
    }

    @Test
    fun `no token header is called out separately from a wrong token`() {
        val causes = JSONObject(
            buildStatusJson(snapshot(tokenProvided = false, authenticated = false))
        ).getJSONArray("causes").toList()
        assertTrue(causes.contains("token_not_provided"))
        assertFalse(causes.contains("token_mismatch_or_rotated"))
    }

    @Test
    fun `rate limiting and accessibility are named as causes with an action`() {
        val limited = JSONObject(buildStatusJson(snapshot(rateLimitedClients = 2)))
        assertTrue(limited.getJSONArray("causes").toList().contains("rate_limited"))
        assertTrue(limited.getString("hint").contains("retry after 1s"))

        val a11y = JSONObject(
            buildStatusJson(snapshot(accessibilityEnabled = false, accessibilityRunning = false))
        )
        assertTrue(a11y.getJSONArray("causes").toList().contains("accessibility_not_running"))
        assertTrue(a11y.getString("hint").contains("force-stop Relay"))
    }

    @Test
    fun `status never leaks the token, only a stable fingerprint`() {
        val token = "0123456789abcdef0123456789abcdef"
        val body = buildStatusJson(
            snapshot().copy(tokenFingerprint = tokenFingerprint(token))
        )
        assertFalse("status must not contain the raw token", body.contains(token))
        val printed = JSONObject(body).getJSONObject("auth").getString("tokenFingerprint")
        assertEquals(12, printed.length)
        assertEquals(tokenFingerprint(token), printed)

        // Fingerprint is deterministic and token-specific.
        assertEquals(tokenFingerprint(token), tokenFingerprint(token))
        assertNotEquals(tokenFingerprint(token), tokenFingerprint(token + "x"))
    }

    // ---- token rotation ----

    @Test
    fun `rotation invalidates the old token immediately and records the rotation`() {
        val state = TokenRotationState(
            initialToken = "old-token",
            generate = { "new-token" },
            clock = { 1_700_000_000_000L }
        )
        assertTrue(state.validate("Bearer old-token"))
        assertFalse(state.validate("Bearer nope"))

        assertEquals("new-token", state.rotate())
        assertFalse("the old token must stop working at once", state.validate("Bearer old-token"))
        assertTrue(state.validate("Bearer new-token"))
        assertEquals(1, state.rotationCount)
        assertEquals(1_700_000_000_000L, state.lastRotatedAtMs)
        assertEquals(1_700_000_000_000L, state.lastRotatedAt)
    }

    @Test
    fun `bearer scheme is matched case-insensitively and blank headers are rejected`() {
        val state = TokenRotationState(initialToken = "abc123")
        assertTrue(state.validate("Bearer abc123"))
        assertTrue(state.validate("bearer abc123"))
        assertTrue(state.validate("  Bearer   abc123  "))
        assertFalse(state.validate(null))
        assertFalse(state.validate(""))
        assertFalse(state.validate("abc123"))
    }

    @Test
    fun `generated tokens are 32 hex characters and never repeat`() {
        val first = randomToken()
        val second = randomToken()
        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals(first, second)
    }
}

private fun org.json.JSONArray.toList(): List<String> =
    (0 until length()).map { getString(it) }
