// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Raw HTTP outcome of one System-1 call. */
data class TransportResponse(val code: Int, val body: String)

/** Minimal POST abstraction so unit tests can run with zero real network. */
interface SystemOneTransport {
    fun post(url: String, headers: Map<String, String>, body: String): TransportResponse
}

/**
 * [SystemOneTransport] over [HttpURLConnection] (JDK 8 APIs only).
 * Connect timeout 2000 ms, read timeout 5000 ms. Lets [IOException]s
 * (including [SocketTimeoutException]) propagate so the client can map them
 * to explicit reason codes.
 */
class UrlConnectionTransport : SystemOneTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return TransportResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 2000
        const val READ_TIMEOUT_MS = 5000
    }
}

/** Outcome of one Jev System-1 decision call. Never throws out of [JevSystemOneClient.decide]. */
sealed class JevResult {
    data class Ok(
        val elementId: String?,
        val confidence: Double,
        val probabilities: Map<String, Double>,
        val goalDone: Boolean,
        val destructive: Boolean,
        val blocked: Boolean
    ) : JevResult()

    data class Failed(val reason: String) : JevResult()
}

/**
 * Minimal JSON value reader for System-1 responses (and for unit tests
 * asserting on request bodies). `org.json` is Android-stubbed under plain
 * JVM unit tests, so this avoids it entirely: no new dependencies.
 *
 * Returns maps for objects, lists for arrays, strings, doubles for numbers,
 * booleans, and null. Throws [MiniJson.ParseException] on malformed input.
 */
internal object MiniJson {

    class ParseException(message: String) : Exception(message)

    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw ParseException("top level is not an object")
    }

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWs()
        if (!parser.atEnd()) throw ParseException("trailing characters at ${parser.pos}")
        return value
    }

    fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos] in " \t\n\r") pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw ParseException("unexpected end of input")
            return when (s[pos]) {
                '{' -> parseObjectInner()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw ParseException("unexpected character '${s[pos]}' at $pos")
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun parseObjectInner(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw ParseException("expected string key at $pos")
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw ParseException("expected ':' at $pos")
                pos++
                out[key] = parseValue()
                skipWs()
                if (atEnd()) throw ParseException("unterminated object")
                if (s[pos] == '}') {
                    pos++
                    return out
                }
                if (s[pos] != ',') throw ParseException("expected ',' at $pos")
                pos++
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val out = mutableListOf<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (atEnd()) throw ParseException("unterminated array")
                if (s[pos] == ']') {
                    pos++
                    return out
                }
                if (s[pos] != ',') throw ParseException("expected ',' at $pos")
                pos++
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("bad escape at end of input")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw ParseException("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && s[pos].isDigit()) pos++
            if (!atEnd() && s[pos] == '.') {
                pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            return raw.toDoubleOrNull() ?: throw ParseException("bad number '$raw'")
        }
    }
}

/**
 * Tier-B engine: asks the free `jev-1.13-free` System-1 endpoint which
 * on-screen element moves the screen toward the goal.
 *
 * Password scrubbing limitation: [SemanticElement] (built from
 * `AccessibilityNodeData`) carries no `isPassword` flag, so secret detection
 * is a keyword heuristic over an `INPUT`'s title/placeholder/summary. A
 * password field with an innocuous label will NOT be redacted; treat every
 * value sent as potentially visible to the endpoint and keep digests minimal.
 */
class JevSystemOneClient(
    private val config: EngineConfig,
    private val transport: SystemOneTransport = UrlConnectionTransport()
) {

    /** Headers for the System-1 call. A curl-like UA is mandatory (else HTTP 403). */
    fun buildHeaders(): Map<String, String> {
        val key = config.apiKey() ?: ""
        return mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $key",
            "User-Agent" to CURL_UA
        )
    }

    /**
     * Builds the exact System-1 request body: `model`, `state.text` digest,
     * and a `questions` DICT (never an array) where every entry carries a
     * `type`. `choice.criteria` is a dict, `score.criteria` is a list, and
     * every `noul` carries `instructions`.
     */
    fun buildRequestBody(scene: SemanticScene, goal: String, candidates: List<SemanticElement>): String {
        val capped = candidates.take(MAX_CANDIDATES)
        val sb = StringBuilder()
        sb.append("{\"model\":").append(MiniJson.quote(MODEL))
        sb.append(",\"state\":{\"text\":").append(MiniJson.quote(stateDigest(scene, capped)))
        sb.append("},\"questions\":{")
        sb.append("\"choice\":{\"type\":\"choice\",")
        sb.append("\"question\":").append(MiniJson.quote("Which element moves the screen toward the goal: $goal?"))
        sb.append(",\"criteria\":{")
        capped.forEachIndexed { index, el ->
            if (index > 0) sb.append(',')
            sb.append(MiniJson.quote("c${index + 1}")).append(':')
            sb.append(MiniJson.quote(describeCandidate(el)))
        }
        sb.append("},\"choices\":[")
        capped.forEachIndexed { index, _ ->
            if (index > 0) sb.append(',')
            sb.append(MiniJson.quote("c${index + 1}"))
        }
        sb.append("]},")
        sb.append("\"noul_goal_done\":").append(
            noulQuestion(
                "Is the goal already achieved on this screen: $goal?",
                "true when the screen already satisfies the goal"
            )
        ).append(',')
        sb.append("\"noul_destructive\":").append(
            noulQuestion(
                "Would acting here destroy data or make an irreversible change?",
                "true when the next step may delete data or cannot be undone"
            )
        ).append(',')
        sb.append("\"noul_blocked\":").append(
            noulQuestion(
                "Is progress blocked, e.g. by a login wall, paywall, or permission dialog?",
                "true when a wall or dialog blocks progress toward the goal"
            )
        ).append(',')
        sb.append("\"score\":{\"type\":\"score\",")
        sb.append("\"question\":\"How hard is this step?\",")
        sb.append("\"criteria\":[\"easy\",\"medium\",\"hard\"]}")
        sb.append("}}")
        return sb.toString()
    }

    fun decide(scene: SemanticScene, goal: String, candidates: List<SemanticElement>): JevResult {
        try {
            val key = config.apiKey() ?: return JevResult.Failed("no_api_key")
            if (key.isBlank()) return JevResult.Failed("no_api_key")
            val endpoint = config.endpoint()
            if (endpoint.isBlank()) return JevResult.Failed("no_endpoint")
            if (!endpoint.startsWith("https://")) return JevResult.Failed("bad_endpoint")

            val body = buildRequestBody(scene, goal, candidates)
            val headers = buildHeaders()
            val response = try {
                transport.post(endpoint, headers, body)
            } catch (e: SocketTimeoutException) {
                return JevResult.Failed("timeout")
            } catch (e: IOException) {
                return JevResult.Failed("network")
            }
            if (response.code == 403) return JevResult.Failed("http_403")
            if (response.code != 200) return JevResult.Failed("http_5xx")
            return try {
                mapResponse(response.body, candidates)
            } catch (e: MiniJson.ParseException) {
                JevResult.Failed("parse_error")
            }
        } catch (e: SocketTimeoutException) {
            return JevResult.Failed("timeout")
        } catch (e: IOException) {
            return JevResult.Failed("network")
        } catch (e: Exception) {
            return JevResult.Failed("network")
        }
    }

    private fun noulQuestion(question: String, instructions: String): String {
        return "{\"type\":\"noul\"," +
            "\"question\":" + MiniJson.quote(question) + "," +
            "\"instructions\":" + MiniJson.quote(instructions) + "}"
    }

    private fun describeCandidate(el: SemanticElement): String {
        var visible = GoalGuards.visibleText(el).trim().replace(Regex("\\s+"), " ")
        if (el.role == SemanticRole.INPUT && looksSecret(el)) {
            val secret = el.value
            if (!secret.isNullOrEmpty() && visible.contains(secret)) {
                visible = visible.replace(secret, REDACTED).trim().replace(Regex("\\s+"), " ")
            }
            if (visible.isEmpty()) visible = REDACTED
        }
        if (visible.length > SNIPPET_MAX) visible = visible.take(SNIPPET_MAX)
        return "${el.role.value}: $visible"
    }

    private fun looksSecret(el: SemanticElement): Boolean {
        val haystack = listOfNotNull(el.title, el.placeholder, el.summary).joinToString(" ")
        return SECRET_HINT.containsMatchIn(haystack)
    }

    /** Compact digest: app, screen title, one line per candidate. Never elementIds. */
    internal fun stateDigest(scene: SemanticScene, candidates: List<SemanticElement>): String {
        val sb = StringBuilder()
        sb.append("app: ").append(scene.app ?: "unknown").append('\n')
        sb.append("screen: ").append(scene.screenTitle ?: "unknown").append('\n')
        candidates.forEachIndexed { index, el ->
            sb.append("c").append(index + 1).append(' ').append(describeCandidate(el)).append('\n')
        }
        val full = sb.toString()
        return if (full.length > STATE_MAX_CHARS) full.take(STATE_MAX_CHARS) else full
    }

    private fun mapResponse(body: String, candidates: List<SemanticElement>): JevResult {
        val labelToId = candidates.take(MAX_CANDIDATES)
            .mapIndexed { index, el -> "c${index + 1}" to el.elementId }
            .toMap()
        val answers = (MiniJson.parseObject(body)["answers"] as? Map<String, Any?>)
            ?: throw MiniJson.ParseException("missing answers")
        val choice = answers["choice"] as? Map<String, Any?>
        val rawChosen = choice?.get("choice") as? String
        val chosen = if (rawChosen.isNullOrEmpty()) null else rawChosen
        val probabilities = mutableMapOf<String, Double>()
        val probsJson = choice?.get("probabilities") as? Map<String, Any?>
        if (probsJson != null) {
            for ((k, v) in probsJson) {
                val number = v as? Number ?: continue
                probabilities[k] = number.toDouble()
            }
        }
        val bareConfidence = (choice?.get("confidence") as? Number)?.toDouble() ?: Double.NaN
        val confidence = when {
            chosen != null && probabilities.containsKey(chosen) -> probabilities[chosen] ?: 0.0
            bareConfidence.isFinite() -> bareConfidence
            else -> 0.0
        }
        return JevResult.Ok(
            elementId = if (chosen != null) labelToId[chosen] else null,
            confidence = confidence,
            probabilities = probabilities,
            goalDone = noulProbability(answers, "noul_goal_done") >= 0.5,
            destructive = noulProbability(answers, "noul_destructive") >= 0.5,
            blocked = noulProbability(answers, "noul_blocked") >= 0.5
        )
    }

    private fun noulProbability(answers: Map<String, Any?>, slot: String): Double {
        val entry = answers[slot] as? Map<String, Any?> ?: return 0.0
        return (entry["noul"] as? Number)?.toDouble() ?: 0.0
    }

    companion object {
        const val MODEL = "jev-1.13-free"
        const val CURL_UA = "curl/8.5.0"
        const val MAX_CANDIDATES = 64
        const val SNIPPET_MAX = 80
        const val STATE_MAX_CHARS = 6000
        const val REDACTED = "«redacted»"
        private val SECRET_HINT = Regex("(pass|pwd|pin|code|otp|secret|token|card|cvv)", RegexOption.IGNORE_CASE)
    }
}
