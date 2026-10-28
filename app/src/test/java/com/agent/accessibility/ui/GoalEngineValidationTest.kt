// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalEngineValidationTest {

    @Test
    fun `https endpoint is valid`() {
        assertTrue(isValidGoalEndpoint("https://opencode.ai/zen/v1/systemone"))
        assertTrue(isValidGoalEndpoint("  https://example.com/x  "))
        assertTrue(isValidGoalEndpoint("https://localhost:8443/compat"))
    }

    @Test
    fun `non-https endpoint is invalid`() {
        assertFalse(isValidGoalEndpoint("http://opencode.ai/zen/v1/systemone"))
        assertFalse(isValidGoalEndpoint("https://"))
        assertFalse(isValidGoalEndpoint(""))
        assertFalse(isValidGoalEndpoint("   "))
        assertFalse(isValidGoalEndpoint("ftp://example.com/x"))
    }

    @Test
    fun `threshold inside range is valid`() {
        assertTrue(isValidGoalThreshold("0.55"))
        assertTrue(isValidGoalThreshold("0.30"))
        assertTrue(isValidGoalThreshold("0.95"))
        assertTrue(isValidGoalThreshold(" 0.7 "))
    }

    @Test
    fun `threshold outside range or unparsable is invalid`() {
        assertFalse(isValidGoalThreshold("0.29"))
        assertFalse(isValidGoalThreshold("0.96"))
        assertFalse(isValidGoalThreshold("abc"))
        assertFalse(isValidGoalThreshold(""))
        assertFalse(isValidGoalThreshold("NaN"))
    }

    @Test
    fun `parse threshold trims and parses`() {
        assertEquals(0.55, parseGoalThreshold(" 0.55 ")!!, 1e-9)
        assertNull(parseGoalThreshold("nope"))
    }

    @Test
    fun `key mask shows last 4 only`() {
        assertEquals("sk-…abcd", maskGoalApiKey("sk-1234abcd"))
        // Short keys never reveal more than the mask.
        assertEquals("sk-…••••", maskGoalApiKey("ab"))
    }
}
