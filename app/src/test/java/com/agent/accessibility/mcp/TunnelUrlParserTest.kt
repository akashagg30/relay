// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelUrlParserTest {

    private fun fixtureLines(name: String = "cf_stderr_2026-10-28.log"): List<String> {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("cloudflared/$name")
            ?: throw IllegalStateException("fixture missing from test classpath: $name")
        return stream.bufferedReader().use { it.readLines() }
    }

    @Test
    fun `extracts exact URL from real fixture box-border line`() {
        val lines = fixtureLines()
        val urlLine = lines.first { "patch-cassette-golden-carb" in it }
        assertEquals(
            "https://patch-cassette-golden-carb.trycloudflare.com",
            TunnelUrlParser.extractUrl(urlLine)
        )
    }

    @Test
    fun `fixture requesting-line carries no URL (old substring matcher was wrong)`() {
        val lines = fixtureLines()
        val requesting = lines.first { "Requesting new quick Tunnel on trycloudflare.com" in it }
        assertNull(TunnelUrlParser.extractUrl(requesting))
    }

    @Test
    fun `extracts URL from synthetic stdout line`() {
        assertEquals(
            "https://stdout-sample.trycloudflare.com",
            TunnelUrlParser.extractUrl("2026-10-28T17:26:40Z INF https://stdout-sample.trycloudflare.com")
        )
    }

    @Test
    fun `padded bordered line ends exactly at dot-com`() {
        val line = "2026-10-28T17:26:40Z INF |  " +
            "https://patch-cassette-golden-carb.trycloudflare.com" +
            "                                      |"
        val url = TunnelUrlParser.extractUrl(line)
        assertEquals("https://patch-cassette-golden-carb.trycloudflare.com", url)
        assertTrue(url != null && !url.contains(" ") && !url.contains("|"))
    }

    @Test
    fun `non-matching INF log line returns null`() {
        assertNull(
            TunnelUrlParser.extractUrl(
                "2026-10-28T17:26:40Z INF Starting metrics server on 127.0.0.1:20241/metrics"
            )
        )
    }

    @Test
    fun `empty line returns null`() {
        assertNull(TunnelUrlParser.extractUrl(""))
    }

    @Test
    fun `whole fixture yields exactly one non-null match`() {
        val lines = fixtureLines()
        val hits = lines.mapNotNull { TunnelUrlParser.extractUrl(it) }
        assertEquals(1, hits.size)
        assertEquals(
            "https://patch-cassette-golden-carb.trycloudflare.com",
            hits.single()
        )
    }

    // --- 2026-10-29 regression: the Android DNS failure line must NOT look like a URL ---

    @Test
    fun `captured dns-failure line yields no url`() {
        val captured = "D CloudflareTunnel: TunnelProcess: " +
            "failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\":"
        assertNull(TunnelUrlParser.extractUrl(captured))
    }

    @Test
    fun `captured android fixture yields zero urls`() {
        val hits = fixtureLines("cf_stderr_2026-10-29.log")
            .mapNotNull { TunnelUrlParser.extractUrl(it) }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `reserved first labels are rejected`() {
        for (label in listOf("api", "dash", "developers", "www", "cloudflare")) {
            assertNull(
                "reserved label '$label' must not parse as a quick Tunnel",
                TunnelUrlParser.extractUrl("https://$label.trycloudflare.com/tunnel")
            )
        }
    }

    @Test
    fun `hyphen-free label is rejected`() {
        assertNull(TunnelUrlParser.extractUrl("https://abc.trycloudflare.com"))
    }

    @Test
    fun `trailing junk after a real url is not part of the match`() {
        val line = "D CloudflareTunnel: TunnelProcess: public URL: " +
            "https://patch-cassette-golden-carb.trycloudflare.com\":"
        assertEquals(
            "https://patch-cassette-golden-carb.trycloudflare.com",
            TunnelUrlParser.extractUrl(line)
        )
    }

    @Test
    fun `first valid url wins over an earlier reserved label`() {
        val line = "Post \"https://api.trycloudflare.com/tunnel\": then " +
            "https://patch-cassette-golden-carb.trycloudflare.com"
        assertEquals(
            "https://patch-cassette-golden-carb.trycloudflare.com",
            TunnelUrlParser.extractUrl(line)
        )
    }
}
