// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Append-only sink for [GoalDecisionLog] lines. Injectable so unit tests can
 * capture lines in memory instead of touching the filesystem.
 */
interface DecisionSink {
    fun append(line: String)
}

/**
 * [DecisionSink] writing to `goal_decisions.jsonl` inside [dir].
 *
 * Rotation: when the next line would push the file past [maxBytes], the
 * current file is moved to `goal_decisions.1.jsonl` (single rotation,
 * overwriting any previous backup) before the line is written. All I/O is
 * guarded — a logging failure drops the line instead of breaking an agent run.
 */
class FileDecisionSink(
    private val dir: File,
    private val maxBytes: Long = 512_000
) : DecisionSink {

    override fun append(line: String) {
        try {
            val file = File(dir, FILE_NAME)
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (file.exists() && file.length() + bytes.size > maxBytes) {
                val rotated = File(dir, ROTATED_NAME)
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            FileOutputStream(file, true).use { it.write(bytes) }
        } catch (_: Exception) {
            // Logging must never break an agent run.
        }
    }

    companion object {
        const val FILE_NAME = "goal_decisions.jsonl"
        const val ROTATED_NAME = "goal_decisions.1.jsonl"
    }
}

/**
 * JSONL audit log for the `act_toward_goal` micro-loop: one JSON object per
 * line, keys `ts, goalHash, screenHash, step, engine, elementId, action,
 * confidence, probabilities, guard, outcome, status`.
 *
 * The raw goal text is never logged — [goalHash] carries only the first 16
 * hex chars of the goal's SHA-256 (QA flows can contain customer data).
 *
 * Serialization is hand-rolled with [MiniJson.quote] instead of `org.json`,
 * which is Android-stubbed under plain JVM unit tests.
 */
class GoalDecisionLog(
    private val sink: DecisionSink,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun record(
        goalHash: String,
        screenHash: String,
        step: Int,
        engine: String,
        elementId: String?,
        action: String,
        confidence: Double,
        probabilities: Map<String, Double>?,
        guard: String?,
        outcome: String,
        status: String
    ) {
        try {
            sink.append(buildLine(goalHash, screenHash, step, engine, elementId, action,
                confidence, probabilities, guard, outcome, status))
        } catch (_: Exception) {
            // Logging must never break an agent run.
        }
    }

    private fun buildLine(
        goalHash: String,
        screenHash: String,
        step: Int,
        engine: String,
        elementId: String?,
        action: String,
        confidence: Double,
        probabilities: Map<String, Double>?,
        guard: String?,
        outcome: String,
        status: String
    ): String {
        val probs = if (probabilities == null) {
            "null"
        } else {
            probabilities.entries.joinToString(",", "{", "}") { (k, v) ->
                "${MiniJson.quote(k)}:$v"
            }
        }
        return "{" +
            "\"ts\":${clock()}," +
            "\"goalHash\":${MiniJson.quote(goalHash)}," +
            "\"screenHash\":${MiniJson.quote(screenHash)}," +
            "\"step\":$step," +
            "\"engine\":${MiniJson.quote(engine)}," +
            "\"elementId\":${if (elementId == null) "null" else MiniJson.quote(elementId)}," +
            "\"action\":${MiniJson.quote(action)}," +
            "\"confidence\":$confidence," +
            "\"probabilities\":$probs," +
            "\"guard\":${if (guard == null) "null" else MiniJson.quote(guard)}," +
            "\"outcome\":${MiniJson.quote(outcome)}," +
            "\"status\":${MiniJson.quote(status)}" +
            "}"
    }
}

/**
 * First 16 hex chars of the SHA-256 of [goal]. Used as the log key so the
 * raw goal text never lands in the JSONL file.
 */
internal fun goalHash(goal: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(goal.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
}
