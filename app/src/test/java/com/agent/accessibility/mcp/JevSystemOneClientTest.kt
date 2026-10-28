// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeEngineConfig(
    private val tier: EngineTier = EngineTier.JEV,
    private val key: String? = "test-key",
    private val endpoint: String = "https://opencode.ai/zen/v1/systemone"
) : EngineConfig {
    override fun tier(): EngineTier = tier
    override fun apiKey(): String? = key
    override fun endpoint(): String = endpoint
    override fun confidenceThreshold(): Double = 0.55
}

internal class FakeTransport(
    var code: Int = 200,
    var body: String = "{}"
) : SystemOneTransport {
    var calls = 0
    var lastUrl = ""
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody = ""
    var toThrow: Exception? = null

    override fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
        calls++
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        toThrow?.let { throw it }
        return TransportResponse(code, this.body)
    }
}

internal fun okBody(
    chosen: String,
    probs: Map<String, Double>,
    goalDone: Double = 0.0,
    bareConfidence: Double = 0.65
): String {
    val probsJson = probs.entries.joinToString(",") { (k, v) -> "${MiniJson.quote(k)}: $v" }
    return "{\"model\":\"jev-1.13-free\"," +
        "\"answers\":{" +
        "\"choice\":{\"type\":\"choice\"," +
        "\"choice\":${MiniJson.quote(chosen)}," +
        "\"confidence\":$bareConfidence," +
        "\"probabilities\":{$probsJson}}," +
        "\"noul_goal_done\":{\"type\":\"noul\",\"noul\":$goalDone}," +
        "\"noul_destructive\":{\"type\":\"noul\",\"noul\":0.1}," +
        "\"noul_blocked\":{\"type\":\"noul\",\"noul\":0.05}}}"
}

internal fun actionEl(id: String, title: String): SemanticElement {
    return SemanticElement(elementId = id, role = SemanticRole.ACTION, title = title)
}

internal fun sceneOf(vararg elements: SemanticElement): SemanticScene {
    return SemanticScene(app = "com.test.app", screenTitle = "Test", scrollable = false, elements = elements.toList())
}

@Suppress("UNCHECKED_CAST")
internal fun obj(value: Any?, slot: String): Map<String, Any?> {
    return (value as? Map<String, Any?>) ?: throw AssertionError("'$slot' is not a JSON object")
}

class JevSystemOneClientTest {

    @Test
    fun bodyShapeFollowsTheVerifiedRecipe() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0)))
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-secret-1", "Wi-Fi settings"))
        client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)

        val root = MiniJson.parseObject(transport.lastBody)
        assertEquals("jev-1.13-free", root["model"])
        val state = obj(root["state"], "state")
        assertTrue((state["text"] as? String)?.isNotEmpty() == true)
        val questions = obj(root["questions"], "questions")
        assertTrue(questions.size >= 5)
        for ((slot, entry) in questions) {
            val question = obj(entry, slot)
            assertTrue("question $slot carries a type", (question["type"] as? String)?.isNotEmpty() == true)
        }
        val choice = obj(questions["choice"], "choice")
        assertEquals("choice", choice["type"])
        assertTrue("choice.criteria is a dict", choice["criteria"] is Map<*, *>)
        assertTrue("choice.choices is a list", choice["choices"] is List<*>)
        val score = obj(questions["score"], "score")
        assertEquals("score", score["type"])
        assertTrue("score.criteria is a list", score["criteria"] is List<*>)
        for (slot in listOf("noul_goal_done", "noul_destructive", "noul_blocked")) {
            val noul = obj(questions[slot], slot)
            assertEquals("noul", noul["type"])
            assertTrue("$slot carries instructions", (noul["instructions"] as? String)?.isNotEmpty() == true)
        }
        // No elementIds or resource ids leak into the wire body.
        assertFalse(transport.lastBody.contains("node-secret-1"))
    }

    @Test
    fun headersCarryBearerKeyAndCurlUserAgent() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0)))
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-1", "Wi-Fi settings"))
        client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)

        assertEquals("Bearer test-key", transport.lastHeaders["Authorization"])
        assertEquals("application/json", transport.lastHeaders["Content-Type"])
        assertTrue(transport.lastHeaders["User-Agent"]?.startsWith("curl/") == true)
    }

    @Test
    fun candidateListIsCappedAt64() {
        val client = JevSystemOneClient(FakeEngineConfig(), FakeTransport())
        val cands = (1..100).map { actionEl("node-$it", "Option number $it") }
        val body = MiniJson.parseObject(client.buildRequestBody(sceneOf(*cands.toTypedArray()), "Pick one", cands))
        val choice = obj(obj(body["questions"], "questions")["choice"], "choice")
        assertEquals(64, (choice["criteria"] as Map<*, *>).size)
        assertEquals(64, (choice["choices"] as List<*>).size)
    }

    @Test
    fun passwordValueIsScrubbedFromTheRequest() {
        val client = JevSystemOneClient(FakeEngineConfig(), FakeTransport())
        val secret = SemanticElement(
            elementId = "node-9",
            role = SemanticRole.INPUT,
            title = "Password",
            value = "hunter2"
        )
        val body = client.buildRequestBody(sceneOf(secret), "Log in", listOf(secret))
        assertFalse(body.contains("hunter2"))
        assertTrue(body.contains("«redacted»"))
    }

    @Test
    fun successMappingReadsTheProbabilityDistribution() {
        val transport = FakeTransport(
            body = okBody("c2", mapOf("c2" to 0.7, "c1" to 0.3), bareConfidence = 0.65)
        )
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"), actionEl("node-b", "Bluetooth"))
        val result = client.decide(sceneOf(*cands.toTypedArray()), "Open Bluetooth", cands)

        assertTrue(result is JevResult.Ok)
        result as JevResult.Ok
        assertEquals("node-b", result.elementId)
        assertEquals(0.7, result.confidence, 1e-9)
        assertFalse(result.goalDone)
    }

    @Test
    fun noulGoalDoneMapsToGoalDone() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0), goalDone = 0.9))
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)

        assertTrue(result is JevResult.Ok)
        result as JevResult.Ok
        assertTrue(result.goalDone)
        assertFalse(result.destructive)
        assertFalse(result.blocked)
    }

    @Test
    fun http403MapsToHttp403() {
        val transport = FakeTransport(code = 403, body = "forbidden")
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)
        assertEquals(JevResult.Failed("http_403"), result)
    }

    @Test
    fun http500MapsToHttp5xx() {
        val transport = FakeTransport(code = 500, body = "boom")
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)
        assertEquals(JevResult.Failed("http_5xx"), result)
    }

    @Test
    fun socketTimeoutMapsToTimeout() {
        val transport = FakeTransport(body = "{}")
        transport.toThrow = SocketTimeoutException("timed out")
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)
        assertEquals(JevResult.Failed("timeout"), result)
    }

    @Test
    fun malformedBodyMapsToParseError() {
        val transport = FakeTransport(body = "not json{{{")
        val client = JevSystemOneClient(FakeEngineConfig(), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)
        assertEquals(JevResult.Failed("parse_error"), result)
    }

    @Test
    fun nonHttpsEndpointMapsToBadEndpointWithoutCallingTransport() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0)))
        val client = JevSystemOneClient(FakeEngineConfig(endpoint = "http://evil.example/x"), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)
        assertEquals(JevResult.Failed("bad_endpoint"), result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun blankKeyMapsToNoApiKeyWithoutCallingTransport() {
        val transport = FakeTransport(body = okBody("c1", mapOf("c1" to 1.0)))
        val client = JevSystemOneClient(FakeEngineConfig(key = ""), transport)
        val cands = listOf(actionEl("node-a", "Wi-Fi settings"))
        val result = client.decide(sceneOf(cands[0]), "Open Wi-Fi settings", cands)
        assertEquals(JevResult.Failed("no_api_key"), result)
        assertEquals(0, transport.calls)
    }
}
