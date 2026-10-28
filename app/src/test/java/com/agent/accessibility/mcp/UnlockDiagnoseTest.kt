package com.agent.accessibility.mcp

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM tests for the unlock_diagnose instrument (relay UNLOCK 2/6).
 * No Android types: digit matching, node counting, and the diagnose payload
 * key set are all exercised through JVM-safe factored functions. org.json is
 * Android-only (see SemanticProjectorTest `scene JSON serialization`), so the
 * payload is asserted as the Map unlockDiagnose wraps in a JSONObject on device.
 */
class UnlockDiagnoseTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        viewId: String? = null
    ) = NodeDigitFields(text, desc, viewId)

    @Test
    fun `full PIN pad via contentDescription counts 10 nodes and all digits`() {
        val pad = (0..9).map { node(desc = it.toString()) }
        val result = countDigitNodes(pad)
        assertEquals(10, result.count)
        assertEquals("0,1,2,3,4,5,6,7,8,9", result.digitsSeen)
    }

    @Test
    fun `digits arriving via text only are matched`() {
        assertEquals('4', digitFromNodeFields("4", null, null))
        assertEquals('0', digitFromNodeFields("  0  ", null, null))
        val result = countDigitNodes(listOf(node(text = "4"), node(text = "9")))
        assertEquals(2, result.count)
        assertEquals("4,9", result.digitsSeen)
    }

    @Test
    fun `digits arriving via viewIdResourceName only are matched`() {
        assertEquals('3', digitFromNodeFields(null, null, "com.android.systemui:id/key3"))
        assertEquals('9', digitFromNodeFields(null, null, "com.android.systemui:id/digit_9"))
        assertEquals('7', digitFromNodeFields(null, null, "com.example:id/key7"))
        val result = countDigitNodes(listOf(node(viewId = "com.android.systemui:id/key3")))
        assertEquals(1, result.count)
        assertEquals("3", result.digitsSeen)
    }

    @Test
    fun `TalkBack style contentDescription carries the leading digit`() {
        assertEquals('1', digitFromNodeFields(null, "1, one", null))
        assertEquals('9', digitFromNodeFields(null, "9,nine", null))
    }

    @Test
    fun `node with digits in both text and contentDescription counts once`() {
        val result = countDigitNodes(listOf(node(text = "5", desc = "5")))
        assertEquals(1, result.count)
        assertEquals("5", result.digitsSeen)
    }

    @Test
    fun `non-digit nodes are ignored`() {
        val nodes = listOf(
            node(desc = "Emergency call"),
            node(text = "PIN required"),
            node(desc = "Cancel"),
            node(desc = "Backspace")
        )
        val result = countDigitNodes(nodes)
        assertEquals(0, result.count)
        assertEquals("", result.digitsSeen)
    }

    @Test
    fun `empty node list counts zero with empty digitsSeen`() {
        val result = countDigitNodes(emptyList())
        assertEquals(0, result.count)
        assertEquals("", result.digitsSeen)
    }

    @Test
    fun `digit matcher rejects non-digits and nulls`() {
        assertNull(digitFromNodeFields(null, null, null))
        assertNull(digitFromNodeFields("", null, null))
        assertNull(digitFromNodeFields("12", null, null))
        assertNull(digitFromNodeFields("a", null, null))
        assertNull(digitFromNodeFields(null, "Emergency call", null))
        assertNull(digitFromNodeFields(null, null, "com.example:id/keypad"))
        assertNull(digitFromNodeFields(null, null, "com.example:id/key"))
        assertNull(digitFromNodeFields(null, null, "com.example:id/digit_"))
    }

    @Test
    fun `diagnose payload carries exactly the brief keys plus device identity`() {
        val payload = buildUnlockDiagnoseMap(
            isDeviceLocked = true,
            isSecure = true,
            canPerformGestures = true,
            rootPresent = true,
            rootPackage = "com.android.systemui",
            digitNodesFound = 10,
            rootLeft = 0,
            rootTop = 0,
            rootRight = 1080,
            rootBottom = 2400,
            deviceModel = "Pixel 6",
            deviceManufacturer = "Google",
            androidRelease = "14",
            sdkInt = 34,
            displayWidthPixels = 1080,
            displayHeightPixels = 2400,
            rootSource = "activeWindow",
            digitsSeen = "0,1,2,3,4,5,6,7,8,9"
        )
        // Gate B quotes these 7 brief keys; the rest make a zero result
        // self-contained in one quote. The set must match exactly -- no more,
        // no fewer.
        val briefKeys = setOf(
            "isDeviceLocked",
            "isSecure",
            "canPerformGestures",
            "rootPresent",
            "rootPackage",
            "digitNodesFound",
            "rootBounds"
        )
        assertTrue(payload.keys.containsAll(briefKeys))
        assertEquals(UNLOCK_DIAGNOSE_KEYS, payload.keys)
        assertEquals(7 + 8, payload.size)
        @Suppress("UNCHECKED_CAST")
        val bounds = payload["rootBounds"] as Map<String, Any?>
        assertEquals(UNLOCK_DIAGNOSE_ROOT_BOUNDS_KEYS, bounds.keys)
        // Spot-check value pass-through so the assertion pins behaviour, not shape.
        assertEquals(true, payload["isDeviceLocked"])
        assertEquals(true, payload["isSecure"])
        assertEquals(true, payload["canPerformGestures"])
        assertEquals(true, payload["rootPresent"])
        assertEquals("com.android.systemui", payload["rootPackage"])
        assertEquals(10, payload["digitNodesFound"])
        assertEquals(0, bounds["left"])
        assertEquals(2400, bounds["bottom"])
        assertEquals("activeWindow", payload["rootSource"])
        assertEquals("0,1,2,3,4,5,6,7,8,9", payload["digitsSeen"])
        assertEquals("Pixel 6", payload["deviceModel"])
        assertEquals("Google", payload["deviceManufacturer"])
        assertEquals("14", payload["androidRelease"])
        assertEquals(34, payload["sdkInt"])
        assertEquals(1080, payload["displayWidthPixels"])
        assertEquals(2400, payload["displayHeightPixels"])
    }

    @Test
    fun `diagnose payload zero case is honest`() {
        val payload = buildUnlockDiagnoseMap(
            isDeviceLocked = true,
            isSecure = true,
            canPerformGestures = false,
            rootPresent = false,
            rootPackage = "",
            digitNodesFound = 0,
            rootLeft = 0,
            rootTop = 0,
            rootRight = 0,
            rootBottom = 0,
            deviceModel = "Pixel 6",
            deviceManufacturer = "Google",
            androidRelease = "14",
            sdkInt = 34,
            displayWidthPixels = 1080,
            displayHeightPixels = 2400,
            rootSource = "none",
            digitsSeen = ""
        )
        assertEquals(UNLOCK_DIAGNOSE_KEYS, payload.keys)
        assertEquals(0, payload["digitNodesFound"])
        assertEquals("", payload["digitsSeen"])
        assertEquals("none", payload["rootSource"])
        assertEquals(false, payload["rootPresent"])
    }
}
