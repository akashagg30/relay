package com.agent.accessibility.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * R-4: one bounded retry + typed error envelope on `take_screenshot`.
 *
 * The retry core ([captureWithRetry]) and the failure envelope
 * ([screenshotFailedEnvelope]) are pure and driven here by fakes. The tool
 * itself is a thin composition over them (Android Bitmaps cannot exist on
 * the JVM), so these tests pin the exact behaviour the tool delegates to:
 * 2 attempts total, per-attempt audit signal, and a parseable typed
 * envelope on final failure.
 */
class ScreenshotRetryTest {

    @Test
    fun `retry delay is one bounded 400ms pause`() {
        assertEquals(400L, SCREENSHOT_RETRY_DELAY_MS)
    }

    @Test
    fun `fake fails once then succeeds on the second attempt`() {
        var calls = 0
        val seen = mutableListOf<Pair<Int, Boolean>>()
        val sleeps = mutableListOf<Long>()
        val (result, attempts) = captureWithRetry(
            capture = {
                calls++
                if (calls == 1) null else "image-bytes"
            },
            sleep = { sleeps.add(it) },
            onAttempt = { attempt, succeeded -> seen.add(attempt to succeeded) }
        )
        assertEquals("image-bytes", result)
        assertEquals(2, attempts)
        assertEquals(2, calls)
        assertEquals(listOf(1 to false, 2 to true), seen)
        assertEquals(listOf(SCREENSHOT_RETRY_DELAY_MS), sleeps)
    }

    @Test
    fun `fake always fails gives up after exactly two attempts`() {
        var calls = 0
        val seen = mutableListOf<Pair<Int, Boolean>>()
        val sleeps = mutableListOf<Long>()
        val (result, attempts) = captureWithRetry<String>(
            capture = {
                calls++
                null
            },
            sleep = { sleeps.add(it) },
            onAttempt = { attempt, succeeded -> seen.add(attempt to succeeded) }
        )
        assertNull(result)
        assertEquals(2, attempts)
        assertEquals(2, calls)
        assertEquals(listOf(1 to false, 2 to false), seen)
        assertEquals(listOf(SCREENSHOT_RETRY_DELAY_MS), sleeps)
    }

    @Test
    fun `failure envelope is a typed JSON object not prose`() {
        val text = screenshotFailedEnvelope(2, SCREENSHOT_METHODS, 1234L)
        // Bare-string regression guard: the payload MUST parse as JSON.
        val parsed = JSONObject(text)
        val err = parsed.getJSONObject("error")
        assertEquals("screenshot_failed", err.getString("code"))
        assertEquals(2, err.getInt("attempts"))
        assertEquals(true, err.getBoolean("retryable"))
        assertEquals(1234, err.getInt("elapsedMs"))
        val methods = err.getJSONArray("methods")
        assertEquals(2, methods.length())
        assertEquals("accessibility_api", methods.getString(0))
        assertEquals("shell_screencap", methods.getString(1))
    }
}
