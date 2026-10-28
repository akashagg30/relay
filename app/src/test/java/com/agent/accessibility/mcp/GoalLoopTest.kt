// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ListSink : DecisionSink {
    val lines = mutableListOf<String>()
    override fun append(line: String) {
        lines.add(line)
    }
}

private class FakeEnv(
    first: SemanticScene,
    private val onPerform: ((GoalActionSpec) -> SemanticScene?)? = null,
    var allowed: Boolean = true,
    var performOk: Boolean = true
) : GoalEnvironment {
    var current: SemanticScene = first
    var performs = 0
    val waits = mutableListOf<Long>()
    var snapshots = 0

    override fun snapshot(): SemanticScene {
        snapshots++
        return current
    }

    override fun perform(spec: GoalActionSpec): Boolean {
        performs++
        val next = onPerform?.invoke(spec)
        if (next != null) current = next
        return performOk
    }

    override fun waitMs(ms: Long) {
        waits.add(ms)
    }

    override fun isForegroundAllowed(): Boolean = allowed
}

private fun el(
    id: String,
    title: String,
    role: SemanticRole = SemanticRole.ACTION
): SemanticElement = SemanticElement(elementId = id, role = role, title = title)

private fun scene(vararg elements: SemanticElement): SemanticScene = SemanticScene(
    app = "com.test.app",
    screenTitle = "Test",
    scrollable = false,
    elements = elements.toList()
)

private fun click(
    id: String,
    conf: Double = 0.9,
    engine: EngineTier = EngineTier.HEURISTIC
): RoutedDecision = RoutedDecision(
    Decision(GoalActionSpec(GoalAction.CLICK_NODE, id), conf, "test_click"),
    engine,
    null
)

private fun scriptedRouter(vararg decisions: RoutedDecision): (SemanticScene, String) -> RoutedDecision {
    var i = 0
    return { _, _ ->
        val d = decisions[minOf(i, decisions.size - 1)]
        i++
        d
    }
}

private fun runLoop(
    env: FakeEnv,
    router: (SemanticScene, String) -> RoutedDecision,
    sink: ListSink,
    goal: String = "open wifi settings",
    maxSteps: Int = 5,
    threshold: Double = 0.55
): GoalResult {
    return GoalLoop(env, router, GoalDecisionLog(sink) { 12345L })
        .run(goal, maxSteps, threshold, GoalGuards.DEFAULT_PAUSE_KEYWORDS)
}

class GoalLoopTest {

    @Test
    fun `happy path reaches goal on step 3 with 3 log lines`() {
        val s0 = scene(el("pre1", "Settings home"))
        val s1 = scene(el("pre2", "Network list"))
        val s2 = scene(el("pre3", "Wifi entry"))
        val s3 = scene(el("done1", "Open Wi-Fi settings"))
        val stages = listOf(s1, s2, s3)
        var stage = 0
        val env = FakeEnv(s0, onPerform = { stages[minOf(stage++, stages.size - 1)] })
        val sink = ListSink()
        val result = runLoop(env, scriptedRouter(click("pre1"), click("pre2"), click("pre3")), sink)

        assertEquals(GoalStatus.DONE, result.status)
        assertEquals("goal_text_present", result.reason)
        assertEquals(3, result.steps.size)
        assertEquals(3, sink.lines.size)
        assertEquals(listOf(400L, 400L, 400L), env.waits)
        assertNull(result.elementIdsJson)
    }

    @Test
    fun `budget exhausted yields max steps`() {
        var n = 0
        val env = FakeEnv(
            scene(el("a0", "Home screen")),
            onPerform = { n++; scene(el("a$n", "Screen number $n")) }
        )
        val result = runLoop(env, scriptedRouter(click("a0")), ListSink(), maxSteps = 2)

        assertEquals(GoalStatus.MAX_STEPS, result.status)
        assertEquals(2, result.effectiveMaxSteps)
        assertEquals(2, result.steps.size)
        assertEquals(2, env.performs)
    }

    @Test
    fun `escalate carries fresh post-action element ids with nested children`() {
        val s0 = scene(el("pre1", "Home"))
        val s1 = scene(
            el("post9", "Details page"),
            SemanticElement(
                elementId = "parent1",
                role = SemanticRole.SECTION,
                title = "Section",
                children = listOf(el("child7", "Nested item"))
            )
        )
        val env = FakeEnv(s0, onPerform = { s1 })
        val router = scriptedRouter(
            click("pre1"),
            RoutedDecision(Decision(null, 0.9, "no_candidate"), EngineTier.HEURISTIC, null)
        )
        val result = runLoop(env, router, ListSink())

        assertEquals(GoalStatus.ESCALATE, result.status)
        assertEquals("no_candidate", result.reason)
        val ids = result.elementIdsJson
        assertTrue(ids != null)
        @Suppress("UNCHECKED_CAST")
        val arr = MiniJson.parse(ids!!) as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val flat = arr.map { (it as Map<String, Any?>)["id"] as String }
        assertTrue(flat.contains("post9"))
        assertTrue(flat.contains("parent1"))
        assertFalse(flat.contains("pre1"))
        assertTrue(ids.contains("child7"))
    }

    @Test
    fun `pause beats low confidence and destructive with zero performs`() {
        val env = FakeEnv(scene(el("d1", "Send money now")))
        val router = scriptedRouter(
            RoutedDecision(
                Decision(GoalActionSpec(GoalAction.CLICK_NODE, "d1"), 0.2, "test_click"),
                EngineTier.HEURISTIC,
                null
            )
        )
        val sink = ListSink()
        val result = runLoop(env, router, sink)

        assertEquals(GoalStatus.PAUSED, result.status)
        assertTrue(result.reason.startsWith("pause_keyword:"))
        assertEquals(0, env.performs)
        assertTrue(result.steps.isEmpty())
        assertEquals(1, sink.lines.size)
    }

    @Test
    fun `three unchanged screens end stuck with three steps taken`() {
        val same = scene(el("a", "Refresh feed"))
        val env = FakeEnv(same)
        val result = runLoop(env, scriptedRouter(click("a")), ListSink(), maxSteps = 9)

        assertEquals(GoalStatus.STUCK, result.status)
        assertEquals("unchanged_3_steps", result.reason)
        assertEquals(3, result.steps.size)
        assertEquals(3, env.performs)
        assertEquals(5, result.effectiveMaxSteps)
    }

    @Test
    fun `policy disallowed up front blocks with zero actions`() {
        val env = FakeEnv(scene(el("a", "Home")), allowed = false)
        val sink = ListSink()
        val result = runLoop(env, scriptedRouter(click("a")), sink)

        assertEquals(GoalStatus.BLOCKED, result.status)
        assertEquals("policy_blocked", result.reason)
        assertEquals(0, env.performs)
        assertTrue(result.steps.isEmpty())
        assertEquals(0, result.confidence.count)
    }

    @Test
    fun `text input required escalates with fresh ids`() {
        val env = FakeEnv(scene(el("i1", "Search", SemanticRole.INPUT)))
        val router = scriptedRouter(
            RoutedDecision(
                Decision(null, 0.8, "text_input_required"),
                EngineTier.HEURISTIC,
                null
            )
        )
        val result = runLoop(env, router, ListSink())

        assertEquals(GoalStatus.ESCALATE, result.status)
        assertEquals("text_input_required", result.reason)
        assertTrue(result.elementIdsJson != null)
        assertTrue(result.elementIdsJson!!.contains("i1"))
    }

    @Test
    fun `tier A clamps maxSteps 9 to 5`() {
        var n = 0
        val env = FakeEnv(
            scene(el("a0", "Home screen")),
            onPerform = { n++; scene(el("a$n", "Screen number $n")) }
        )
        val result = runLoop(env, scriptedRouter(click("a0")), ListSink(), maxSteps = 9)

        assertEquals(5, result.effectiveMaxSteps)
        assertEquals(5, result.steps.size)
        assertEquals(EngineTier.HEURISTIC, result.engine)
    }

    @Test
    fun `tier B allows maxSteps 9`() {
        var n = 0
        val env = FakeEnv(
            scene(el("a0", "Home screen")),
            onPerform = { n++; scene(el("a$n", "Screen number $n")) }
        )
        val router = scriptedRouter(click("a0", engine = EngineTier.JEV))
        val result = runLoop(env, router, ListSink(), maxSteps = 9)

        assertEquals(9, result.effectiveMaxSteps)
        assertEquals(9, result.steps.size)
        assertEquals(EngineTier.JEV, result.engine)
    }

    @Test
    fun `confidence stats cover every decision including the terminal one`() {
        val s0 = scene(el("a", "Home"))
        val s1 = scene(el("b", "List"))
        val env = FakeEnv(s0, onPerform = { s1 })
        val router = scriptedRouter(
            RoutedDecision(
                Decision(GoalActionSpec(GoalAction.CLICK_NODE, "a"), 0.6, "test_click"),
                EngineTier.HEURISTIC,
                null
            ),
            RoutedDecision(
                Decision(GoalActionSpec(GoalAction.CLICK_NODE, "b"), 0.8, "test_click"),
                EngineTier.HEURISTIC,
                null
            ),
            RoutedDecision(
                Decision(null, 1.0, "goal_text_present", goalDone = true),
                EngineTier.HEURISTIC,
                null
            )
        )
        val result = runLoop(env, router, ListSink())

        assertEquals(GoalStatus.DONE, result.status)
        assertEquals(2, result.steps.size)
        assertEquals(0.55, result.confidence.threshold, 1e-9)
        assertEquals(0.6, result.confidence.min, 1e-9)
        assertEquals(1.0, result.confidence.max, 1e-9)
        assertEquals(0.8, result.confidence.mean, 1e-9)
        assertEquals(3, result.confidence.count)
    }
}
