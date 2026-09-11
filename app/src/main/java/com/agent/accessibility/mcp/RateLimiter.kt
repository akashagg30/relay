// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RateLimiter(private val requestsPerSecond: Int = 30) {

    private val requestCounts = ConcurrentHashMap<String, RequestWindow>()
    private val lastCleanup = AtomicLong(System.currentTimeMillis())

    data class RequestWindow(
        var count: Int,
        var windowStart: Long
    )

    fun isAllowed(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - 1000 // 1 second window

        // Cleanup old entries periodically
        if (now - lastCleanup.get() > 60000) { // Every 60 seconds
            cleanup(now)
            lastCleanup.set(now)
        }

        val window = requestCounts.computeIfAbsent(clientIp) {
            RequestWindow(0, windowStart)
        }

        synchronized(window) {
            // Reset window if it's expired
            if (window.windowStart < windowStart) {
                window.count = 0
                window.windowStart = windowStart
            }

            // Check if we're within the limit
            if (window.count >= requestsPerSecond) {
                return false
            }

            window.count++
            return true
        }
    }

    private fun cleanup(now: Long) {
        val expiredThreshold = now - 300000 // Remove entries older than 5 minutes
        requestCounts.entries.removeIf { entry ->
            entry.value.windowStart < expiredThreshold
        }
    }

    fun getRequestCount(clientIp: String): Int {
        return requestCounts[clientIp]?.count ?: 0
    }

    fun clear() {
        requestCounts.clear()
    }
}