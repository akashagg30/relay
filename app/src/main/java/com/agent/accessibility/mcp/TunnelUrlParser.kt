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
 * logs lines such as "Requesting new quick Tunnel on trycloudflare.com..." and
 * mentions its own API host `https://api.trycloudflare.com/tunnel`, neither of
 * which is a public URL.
 *
 * Hardening (2026-10-29). The captured Android failure line
 *
 * `TunnelProcess: public URL: https://api.trycloudflare.com/tunnel":`
 *
 * used to be reported as a live public URL, which masked the tunnel failure.
 * Three independent rules now prevent that:
 *
 * 1. the match is BOUNDED at `.trycloudflare.com` (no `[^\s]*` tail), so
 *    trailing junk such as `/tunnel":`, quotes, colons or box padding can
 *    never become part of the result;
 * 2. the first label must contain a hyphen — real quick-Tunnel names are
 *    hyphenated random words (`patch-cassette-golden-carb`), while Cloudflare's
 *    own hosts (`api`, `dash`, `www`, `developers`, `cloudflare`) are not;
 * 3. those reserved first labels are blocklisted case-insensitively.
 *
 * All matches in the line are considered and the first VALID one is returned,
 * so a line that mentions `api.trycloudflare.com` and then a real URL yields
 * the real URL.
 */
object TunnelUrlParser {

    /**
     * Bounded candidate: `https://<label>.trycloudflare.com`, with nothing
     * captured after the TLD.
     */
    private val CANDIDATE =
        Regex("""https://([A-Za-z0-9-]+)\.trycloudflare\.com""", RegexOption.IGNORE_CASE)

    /** First labels that belong to Cloudflare's own hosts, never to a quick Tunnel. */
    private val RESERVED_FIRST_LABELS =
        setOf("api", "dash", "developers", "www", "cloudflare")

    /**
     * Returns the first quick-Tunnel URL contained in [line], or null when the
     * line carries no valid URL.
     */
    fun extractUrl(line: String): String? =
        CANDIDATE.findAll(line)
            .firstOrNull { isQuickTunnelLabel(it.groupValues[1]) }
            ?.value

    private fun isQuickTunnelLabel(label: String): Boolean {
        val normalized = label.lowercase()
        if (normalized in RESERVED_FIRST_LABELS) return false
        return normalized.contains('-')
    }
}
