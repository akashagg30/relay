// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONObject

/**
 * The `act_toward_goal` MCP tool: a bounded on-device perceive → decide →
 * act → verify micro-loop toward a natural-language goal.
 *
 * Pure pieces ([goalToolDescription], [goalToolDefJson], [goalResultJson]) are
 * hand-rolled strings so they stay JVM-unit-testable — `org.json` is
 * Android-stubbed under plain JVM unit tests. Only [goalToolDef] (thin
 * `JSONObject` wrapper for [ToolRegistry]) and [McpHandler.actTowardGoal] /
 * [AndroidGoalEnvironment] (which touch the accessibility service) need a device.
 */

internal const val GOAL_TOOL_NAME = "act_toward_goal"
internal const val DEFAULT_GOAL_MAX_STEPS = 5

internal fun goalToolDescription(): String {
    return "Run a bounded on-device perceive-decide-act micro-loop toward a natural-language goal. " +
        "Each step snapshots the screen, picks an element with the on-device heuristic " +
        "(or the frontier Jev model when a key is configured), acts, and re-verifies in code. " +
        "Returns one of done|escalate|paused|stuck|blocked|max_steps. " +
        "On escalate the response includes fresh element IDs in observe() format that are " +
        "ready for immediate use with click_node/input_text without calling observe() again."
}

/**
 * Exact JSON of the `tools/list` entry, kept as a pure string so unit tests
 * can assert the schema without `org.json`. [goalToolDef] is the thin
 * device-side wrapper over this text.
 */
internal fun goalToolDefJson(): String {
    val sb = StringBuilder()
    sb.append("{\"name\":${MiniJson.quote(GOAL_TOOL_NAME)},")
    sb.append("\"description\":${MiniJson.quote(goalToolDescription())},")
    sb.append("\"inputSchema\":{\"type\":\"object\",\"properties\":{")
    sb.append("\"goal\":{\"type\":\"string\"," +
        "\"description\":\"Natural-language goal, e.g. 'Open Wi-Fi settings'\"},")
    sb.append("\"maxSteps\":{\"type\":\"integer\"," +
        "\"description\":\"Step budget (heuristic tier clamps to 1..5, Jev tier to 1..10)\"},")
    sb.append("\"confidenceThreshold\":{\"type\":\"number\"," +
        "\"description\":\"Minimum decision confidence before acting (default from settings)\"},")
    sb.append("\"pauseBeforeKeywords\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}," +
        "\"description\":\"Keywords that pause the loop before acting (default: pay, buy, purchase, post, publish, send)\"}")
    sb.append("}}}")
    return sb.toString()
}

/** Device-side `tools/list` entry. Do not call from plain JVM unit tests. */
internal fun goalToolDef(): JSONObject = JSONObject(goalToolDefJson())

/**
 * Serialise a finished [GoalResult] into the exact `act_toward_goal` response
 * shape the frontier and the eval harness depend on. `elementIds` is an array
 * of `observe()`-format element objects (nested children included), populated
 * only for `escalate` and empty otherwise — never null.
 */
internal fun goalResultJson(result: GoalResult, goal: String): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"status\":${MiniJson.quote(result.status.wire)},")
    sb.append("\"goal\":${MiniJson.quote(goal)},")
    sb.append("\"engine\":${MiniJson.quote(result.engine.wire)},")
    sb.append("\"fallbackReason\":${if (result.fallbackReason == null) "null"
        else MiniJson.quote(result.fallbackReason)},")
    sb.append("\"effectiveMaxSteps\":${result.effectiveMaxSteps},")
    sb.append("\"stepsTaken\":${result.steps.size},")
    sb.append("\"steps\":[")
    result.steps.forEachIndexed { i, s ->
        if (i > 0) sb.append(",")
        sb.append("{\"index\":${s.index},")
        sb.append("\"action\":${MiniJson.quote(s.action)},")
        sb.append("\"elementId\":${if (s.elementId == null) "null" else MiniJson.quote(s.elementId)},")
        sb.append("\"text\":${if (s.text == null) "null" else MiniJson.quote(s.text)},")
        sb.append("\"confidence\":${s.confidence},")
        sb.append("\"outcome\":${MiniJson.quote(s.outcome)},")
        sb.append("\"stateHash\":${MiniJson.quote(s.stateHash)},")
        sb.append("\"engine\":${MiniJson.quote(s.engine)},")
        sb.append("\"guard\":${if (s.guard == null) "null" else MiniJson.quote(s.guard)}}")
    }
    sb.append("],")
    sb.append("\"elementIds\":${result.elementIdsJson ?: "[]"},")
    sb.append("\"confidence\":{\"threshold\":${result.confidence.threshold},")
    sb.append("\"min\":${result.confidence.min},")
    sb.append("\"max\":${result.confidence.max},")
    sb.append("\"mean\":${result.confidence.mean},")
    sb.append("\"count\":${result.confidence.count}},")
    sb.append("\"reason\":${MiniJson.quote(result.reason)},")
    sb.append("\"elapsedMs\":${result.elapsedMs}")
    sb.append("}")
    return sb.toString()
}

/**
 * A tool response counts as a failed action when it carries the `isError`
 * framing of [McpHandler.toolErrorResponse] or any embedded `"error"` key
 * (protocol-level `errorResponse`). Both are checked because the tool impls
 * use the former while the contract is phrased in terms of the latter.
 */
internal fun isGoalActionError(response: String): Boolean {
    return response.contains("\"isError\"") || response.contains("\"error\"")
}

internal fun McpHandler.actTowardGoal(id: String, args: JSONObject): String {
    val goal = args.optString("goal", "")
    if (goal.isBlank()) {
        return toolErrorResponse(id, "goal_required: pass a non-empty goal")
    }
    val maxSteps = args.optInt("maxSteps", DEFAULT_GOAL_MAX_STEPS)
    val engineConfig = PrefsEngineConfig(context)
    val threshold = args.optDouble("confidenceThreshold", Double.NaN).let { raw ->
        if (raw.isNaN()) engineConfig.confidenceThreshold() else raw
    }
    val pauseKeywords = args.optJSONArray("pauseBeforeKeywords")?.let { arr ->
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val kw = arr.optString(i)
            if (kw.isNotBlank()) list.add(kw)
        }
        list
    }.takeIf { !it.isNullOrEmpty() } ?: GoalGuards.DEFAULT_PAUSE_KEYWORDS

    val router = GoalEngineRouter(jev = { scene, goalText, candidates ->
        JevSystemOneClient(engineConfig, UrlConnectionTransport()).decide(scene, goalText, candidates)
    })
    val env = AndroidGoalEnvironment(this)
    val decisionLog = GoalDecisionLog(FileDecisionSink(context.filesDir))
    val loop = GoalLoop(env, { scene, goalText -> router.decide(scene, goalText, engineConfig) }, decisionLog)
    val result = loop.run(goal, maxSteps, threshold, pauseKeywords)
    return toolSuccessResponse(id, goalResultJson(result, goal))
}

/**
 * [GoalEnvironment] wired to the live device: [McpHandler.observeInternal] is
 * the state builder (it also stores a fresh [NodeResolver] snapshot, so the
 * IDs it returns are immediately clickable), and [perform] dispatches to the
 * existing tool impls with a blank RPC id, treating any response carrying an
 * error marker as a failed action.
 */
class AndroidGoalEnvironment(private val handler: McpHandler) : GoalEnvironment {

    override fun snapshot(): SemanticScene {
        val service = AgentAccessibilityService.instance
            ?: return SemanticScene(null, null, false, emptyList())
        return handler.observeInternal(service)
    }

    override fun perform(spec: GoalActionSpec): Boolean {
        val response = when (spec.action) {
            GoalAction.CLICK_NODE -> {
                val args = JSONObject()
                args.put("id", spec.elementId ?: "")
                handler.clickNode("", args)
            }
            GoalAction.INPUT_TEXT -> {
                val args = JSONObject()
                args.put("id", spec.elementId ?: "")
                args.put("text", spec.text ?: "")
                handler.inputText("", args)
            }
            GoalAction.SUBMIT -> handler.submit("")
            GoalAction.BACK -> handler.back("")
            GoalAction.WAIT -> {
                Thread.sleep(STEP_SETTLE_FALLBACK_MS)
                return true
            }
            GoalAction.SWIPE, GoalAction.NONE -> return false
        }
        return !isGoalActionError(response)
    }

    override fun waitMs(ms: Long) {
        Thread.sleep(ms)
    }

    override fun isForegroundAllowed(): Boolean {
        val service = AgentAccessibilityService.instance ?: return true
        val pkg = handler.findForegroundRoot(service)?.packageName?.toString()
        return AppPolicy.isAllowed(handler.context, pkg)
    }

    companion object {
        private const val STEP_SETTLE_FALLBACK_MS = 300L
    }
}
