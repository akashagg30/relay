// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

/**
 * Single source of the Cloudflare quick-Tunnel public-URL pattern for the
 * whole app.
 *
 * cloudflared prints the URL on stderr inside a box-drawing border with
 * trailing padding, e.g.:
 * `2026-10-28T17:26:40Z INF |  https://patch-cassette-golden-carb.trycloudflare.com                                      |`
 *
 * The regex below extracts exactly the URL (no border, no padding). A bare
 * substring check for "trycloudflare.com" is NOT sufficient: cloudflared also
 * logs lines such as "Requesting new quick Tunnel on trycloudflare.com..."
 * which carry no URL.
 */
object TunnelUrlParser {

    private val URL_REGEX = Regex("""https://[a-zA-Z0-9-]+\.trycloudflare\.com[^\s]*""")

    /**
     * Returns the first quick-Tunnel URL contained in [line], or null when the
     * line carries no URL.
     */
    fun extractUrl(line: String): String? = URL_REGEX.find(line)?.value
}
