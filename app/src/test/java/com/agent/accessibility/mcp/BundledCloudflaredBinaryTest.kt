// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the class of bug that shipped in 1.0.111: the bundled
 * `libcloudflared.so` was a `GOOS=linux`, `CGO_ENABLED=0` pure-Go build. On
 * Android such a binary resolves names through `/etc/resolv.conf`, which does
 * not exist there, so it falls back to `localhost:53` and the tunnel dies with
 * `connection refused` before it ever yields a URL.
 *
 * The cure is `GOOS=android` + `CGO_ENABLED=1` (bionic `getaddrinfo` → netd).
 * These assertions read the Go build info embedded in the shipped artifact, so
 * a regression cannot reach a release again.
 *
 * Pure JVM — gradle's unit-test working directory is the `app/` module.
 */
class BundledCloudflaredBinaryTest {

    private val binaryPath = "src/main/jniLibs/arm64-v8a/libcloudflared.so"

    private companion object {
        /** 40 MB artifact: read once for the whole class, as Latin-1 to keep a 1:1 byte view. */
        val buildInfo: String? by lazy {
            val file = File("src/main/jniLibs/arm64-v8a/libcloudflared.so")
            if (!file.isFile) null else file.readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    private fun buildInfo(): String {
        assumeTrue(
            "bundled libcloudflared.so is absent from this checkout ($binaryPath) — " +
                "run scripts/build-cloudflared-android.sh; skipping binary guard",
            buildInfo != null
        )
        return buildInfo!!
    }

    @Test
    fun `bundled cloudflared is compiled for android`() {
        assertTrue(
            "bundled libcloudflared.so must carry GOOS=android in its Go build info",
            buildInfo().contains("GOOS=android")
        )
    }

    @Test
    fun `bundled cloudflared links the android cgo resolver`() {
        assertTrue(
            "bundled libcloudflared.so must carry CGO_ENABLED=1 (bionic getaddrinfo → netd)",
            buildInfo().contains("CGO_ENABLED=1")
        )
    }

    @Test
    fun `bundled cloudflared is not the linux pure-go build`() {
        val info = buildInfo()
        assertFalse("GOOS=linux build shipped again", info.contains("GOOS=linux"))
        assertFalse("CGO_ENABLED=0 build shipped again", info.contains("CGO_ENABLED=0"))
    }
}
