// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * take_screenshot, at the tool boundary (R-4 + R-14 regression pair).
 *
 * An Android Bitmap cannot exist on the JVM, so these tests drive the two
 * pure pieces the tool composes: the payload builder and the shared JSON-RPC
 * framing. That is enough to pin what the client actually receives — a
 * parseable `{image,width,height,format}` document on success and a typed
 * `screenshot_failed` envelope, never prose, on failure.
 */
class TakeScreenshotToolTest {

    @Test
    fun `success payload carries the image, dimensions and format`() {
        val payload = screenshotSuccessPayload(1080, 2400, "iVBORw0KGgo=")
        assertEquals("iVBORw0KGgo=", payload.getString("image"))
        assertEquals(1080, payload.getInt("width"))
        assertEquals(2400, payload.getInt("height"))
        assertEquals("png", payload.getString("format"))
    }

    @Test
    fun `success is delivered as content text, not an error`() {
        val payload = screenshotSuccessPayload(512, 512, "AAA=")
        val frame = JSONObject(toolSuccessJson("9", payload.toString()))

        assertEquals("2.0", frame.getString("jsonrpc"))
        assertEquals("9", frame.getString("id"))
        val result = frame.getJSONObject("result")
        assertFalse("success must not set isError", result.optBoolean("isError", false))

        val inner = JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(512, inner.getInt("width"))
        assertEquals("AAA=", inner.getString("image"))
    }

    @Test
    fun `a transient failure still succeeds on the retry and reports two attempts`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val (result, attempts) = captureWithRetry(
            capture = { if (++calls == 1) null else "image-bytes" },
            sleep = { sleeps.add(it) }
        )
        assertEquals("image-bytes", result)
        assertEquals(2, attempts)
        assertEquals(listOf(SCREENSHOT_RETRY_DELAY_MS), sleeps)
    }

    @Test
    fun `total capture failure is a typed envelope after exactly two attempts`() {
        var calls = 0
        val (result, attempts) = captureWithRetry<String>(
            capture = { calls++; null },
            sleep = { }
        )
        assertEquals(2, calls)
        assertEquals(2, attempts)
        assertNull("a total failure yields no payload", result)

        val envelope = JSONObject(screenshotFailedEnvelope(attempts, SCREENSHOT_METHODS, 42L))
        val error = envelope.getJSONObject("error")
        assertEquals("screenshot_failed", error.getString("code"))
        assertEquals(2, error.getInt("attempts"))
        assertTrue(error.getBoolean("retryable"))
        assertEquals(42, error.getInt("elapsedMs"))

        // ...and that envelope reaches the client inside the isError frame.
        val frame = JSONObject(toolErrorJson("11", envelope.toString()))
        val result2 = frame.getJSONObject("result")
        assertTrue(result2.getBoolean("isError"))
        val inner = JSONObject(result2.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("screenshot_failed", inner.getJSONObject("error").getString("code"))
    }
}
