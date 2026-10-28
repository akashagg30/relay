// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalEngineRouterTest {

    private fun routerForClient(client: JevSystemOneClient): GoalEngineRouter {
        return GoalEngineRouter(jev = { scene, goal, cands -> client.decide(scene, goal, cands) })
    }

    private fun wifiScene(): SemanticScene {
        return SemanticScene(
            app = "com.test.settings",
            screenTitle = "Settings",
            scrollable = false,
            elements = listOf(
                SemanticElement(elementId = "node-wifi", role = SemanticRole.ACTION, title = "Wi-Fi settings"),
                SemanticElement(elementId = "node-bt", role = SemanticRole.ACTION, title = "Bluetooth")
            )
        )
    }

    @Test
    fun blankKeyWithTierJevReturnsHeuristicAndNeverCallsTransport() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0)))
        val client = JevSystemOneClient(FakeEngineConfig(key = ""), transport)
        val router = routerForClient(client)
        val routed = router.decide(wifiScene(), "Open Wi-Fi settings", FakeEngineConfig(key = ""))

        assertEquals(EngineTier.HEURISTIC, routed.engine)
        assertEquals("no_api_key", routed.fallbackReason)
        assertEquals(0, transport.calls)
    }

    @Test
    fun heuristicTierNeverCallsTransport() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0)))
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val router = routerForClient(client)
        val routed = router.decide(wifiScene(), "Open Wi-Fi settings", FakeEngineConfig(tier = EngineTier.HEURISTIC))

        assertEquals(EngineTier.HEURISTIC, routed.engine)
        assertNull(routed.fallbackReason)
        assertEquals(0, transport.calls)
    }

    @Test
    fun jev403DegradesToHeuristicWithExplicitReason() {
        val transport = FakeTransport(code = 403, body = "forbidden")
        val router = routerForClient(JevSystemOneClient(FakeEngineConfig(), transport))
        val routed = router.decide(wifiScene(), "Open Wi-Fi settings", FakeEngineConfig())

        assertEquals(EngineTier.HEURISTIC, routed.engine)
        assertEquals("http_403", routed.fallbackReason)
    }

    @Test
    fun jevTimeoutDegradesWithoutThrowing() {
        val transport = FakeTransport(body = "{}")
        transport.toThrow = SocketTimeoutException("timed out")
        val router = routerForClient(JevSystemOneClient(FakeEngineConfig(), transport))
        val routed = router.decide(wifiScene(), "Open Wi-Fi settings", FakeEngineConfig())

        assertEquals(EngineTier.HEURISTIC, routed.engine)
        assertEquals("timeout", routed.fallbackReason)
    }

    @Test
    fun jevOkMapsToAClickDecisionOnTierJev() {
        val transport = FakeTransport(body = okBody("c2", mapOf("c2" to 0.7, "c1" to 0.3)))
        val router = routerForClient(JevSystemOneClient(FakeEngineConfig(), transport))
        val routed = router.decide(wifiScene(), "Open Bluetooth", FakeEngineConfig())

        assertEquals(EngineTier.JEV, routed.engine)
        assertNull(routed.fallbackReason)
        assertEquals(GoalAction.CLICK_NODE, routed.decision.action?.action)
        assertEquals("node-bt", routed.decision.action?.elementId)
        assertEquals(0.7, routed.decision.confidence, 1e-9)
        assertTrue(routed.decision.probabilities?.get("c2") == 0.7)
    }

    @Test
    fun jevOkWithGoalDoneReturnsNullActionAndGoalDone() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0), goalDone = 0.9))
        val router = routerForClient(JevSystemOneClient(FakeEngineConfig(), transport))
        val routed = router.decide(wifiScene(), "Open Wi-Fi settings", FakeEngineConfig())

        assertEquals(EngineTier.JEV, routed.engine)
        assertNull(routed.fallbackReason)
        assertNull(routed.decision.action)
        assertTrue(routed.decision.goalDone)
    }

    @Test
    fun jevOkOnInputTargetUsesTheQuotedLiteral() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 0.8)))
        val router = routerForClient(JevSystemOneClient(FakeEngineConfig(), transport))
        val scene = SemanticScene(
            app = "com.test.app",
            screenTitle = "Search",
            scrollable = false,
            elements = listOf(
                SemanticElement(elementId = "node-q", role = SemanticRole.INPUT, title = "Search box")
            )
        )
        val routed = router.decide(scene, "Type \"hello world\" into search", FakeEngineConfig())

        assertEquals(EngineTier.JEV, routed.engine)
        assertEquals(GoalAction.INPUT_TEXT, routed.decision.action?.action)
        assertEquals("node-q", routed.decision.action?.elementId)
        assertEquals("hello world", routed.decision.action?.text)
    }

    @Test
    fun explodingTransportDegradesInsteadOfThrowing() {
        val router = GoalEngineRouter(jev = { _, _, _ -> throw RuntimeException("boom") })
        val routed = router.decide(wifiScene(), "Open Wi-Fi settings", FakeEngineConfig())

        assertEquals(EngineTier.HEURISTIC, routed.engine)
        assertEquals("network", routed.fallbackReason)
    }
}
