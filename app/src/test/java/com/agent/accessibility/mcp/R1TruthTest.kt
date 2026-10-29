package com.agent.accessibility.mcp

import org.junit.Assert.*
import org.junit.Test

/**
 * R-1 TRUTH (card 2 of 3): `click_node` coordinate_fallback must stop
 * reporting success:true unverified.
 *
 * org.json is Android-only (android.jar stubs throw under JVM unit
 * tests), so the fallback result JSON is built by
 * buildFallbackResultJson with fixed key order and asserted here
 * character for character.
 */
class R1TruthTest {

    @Test
    fun `classifier reports UNVERIFIED when the content hash is unchanged`() {
        assertEquals(
            TapOutcome.UNVERIFIED,
            classifyTapOutcome("96a3fc93c8c30b99", "96a3fc93c8c30b99", false)
        )
    }

    @Test
    fun `classifier reports VERIFIED when the content hash differs`() {
        assertEquals(
            TapOutcome.VERIFIED,
            classifyTapOutcome("e85472ae9a8a42f4", "44986b0ccc1c5869", false)
        )
    }

    @Test
    fun `classifier never verifies an errored or unobserved tap`() {
        assertEquals(
            TapOutcome.UNVERIFIED,
            classifyTapOutcome("e85472ae9a8a42f4", "44986b0ccc1c5869", true)
        )
        assertEquals(TapOutcome.UNVERIFIED, classifyTapOutcome(null, "44986b0ccc1c5869", false))
        assertEquals(TapOutcome.UNVERIFIED, classifyTapOutcome("e85472ae9a8a42f4", null, false))
    }

    @Test
    fun `unchanged hash assembles tap_unverified failure result`() {
        val report = FallbackReport(
            delivered = false,
            screenHashBefore = "96a3fc93c8c30b99",
            screenHashAfter = "96a3fc93c8c30b99",
            screenChanged = false,
            appChanged = false,
            tapX = 540,
            tapY = 1331,
            bounds = "44,1254,1036,1408",
            boundsSource = "matched_node",
            targetBounds = "44,1254,1036,1408",
            tapInsideTarget = true
        )
        val expected = "{\"success\":false,\"delivered\":false," +
            "\"method\":\"coordinate_fallback\",\"reason\":\"tap_unverified\"," +
            "\"screenHashBefore\":\"96a3fc93c8c30b99\"," +
            "\"screenHashAfter\":\"96a3fc93c8c30b99\"," +
            "\"screenChanged\":false,\"appChanged\":false," +
            "\"tapX\":540,\"tapY\":1331," +
            "\"bounds\":\"44,1254,1036,1408\",\"boundsSource\":\"matched_node\"," +
            "\"targetBounds\":\"44,1254,1036,1408\",\"tapInsideTarget\":true}"
        assertEquals(expected, buildFallbackResultJson("coordinate_fallback", report))
    }

    @Test
    fun `changed hash assembles verified success result with no reason`() {
        val report = FallbackReport(
            delivered = true,
            screenHashBefore = "e85472ae9a8a42f4",
            screenHashAfter = "44986b0ccc1c5869",
            screenChanged = true,
            appChanged = false,
            tapX = 540,
            tapY = 1331,
            bounds = "44,1254,1036,1408",
            boundsSource = "matched_node",
            targetBounds = "44,1254,1036,1408",
            tapInsideTarget = true
        )
        val expected = "{\"success\":true,\"delivered\":true," +
            "\"method\":\"coordinate_fallback\"," +
            "\"screenHashBefore\":\"e85472ae9a8a42f4\"," +
            "\"screenHashAfter\":\"44986b0ccc1c5869\"," +
            "\"screenChanged\":true,\"appChanged\":false," +
            "\"tapX\":540,\"tapY\":1331," +
            "\"bounds\":\"44,1254,1036,1408\",\"boundsSource\":\"matched_node\"," +
            "\"targetBounds\":\"44,1254,1036,1408\",\"tapInsideTarget\":true}"
        assertEquals(expected, buildFallbackResultJson("coordinate_fallback", report))
    }

    @Test
    fun `fallback audit entry carries the seven D4 context keys`() {
        val keys = fallbackAuditPairs(
            "565:32", "matched_node", "44,1254,1036,1408",
            540, 1331, 0,
            "96a3fc93c8c30b99", "96a3fc93c8c30b99", false
        ).map { it.first }.toSet()
        val expected = setOf(
            "boundsSource", "bounds", "tapX", "tapY",
            "statusBarHeight", "screenHashBefore", "screenHashAfter"
        )
        assertTrue("audit keys $keys must contain $expected", keys.containsAll(expected))
    }

    @Test
    fun `screen change is hash-based and app change is package-based`() {
        assertTrue(computeContentChanged("aa", "bb"))
        assertFalse(computeContentChanged("aa", "aa"))
        assertFalse(computeContentChanged(null, "bb"))
        assertTrue(computeAppChanged("com.a", "com.b"))
        assertFalse(computeAppChanged("com.a", "com.a"))
        assertFalse(computeAppChanged(null, "com.b"))
    }
}
