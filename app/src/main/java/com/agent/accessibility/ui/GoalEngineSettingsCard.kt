// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agent.accessibility.mcp.EngineTier
import com.agent.accessibility.mcp.PrefsEngineConfig

/**
 * Settings panel for the `act_toward_goal` engine (R4).
 *
 * Same visual pattern as the other config cards on [DebugScreen]: an
 * elevated card with a heading and rows. All state is read from / written
 * to [PrefsEngineConfig] — no new storage, no permissions, no network
 * calls from the UI. In-flight field text uses `rememberSaveable` and is
 * persisted only on explicit save actions, never on every keystroke.
 */
@Composable
fun GoalEngineSettingsCard() {
    val context = LocalContext.current
    val config = remember(context) { PrefsEngineConfig(context) }

    var tier by rememberSaveable { mutableStateOf(config.tier().wire) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var keySavedTick by remember { mutableIntStateOf(0) }
    var endpointInput by rememberSaveable { mutableStateOf(config.endpoint()) }
    var endpointError by rememberSaveable { mutableStateOf<String?>(null) }
    var endpointSavedTick by remember { mutableIntStateOf(0) }
    var thresholdInput by rememberSaveable {
        mutableStateOf(config.confidenceThreshold().toString())
    }
    var thresholdError by rememberSaveable { mutableStateOf<String?>(null) }
    var thresholdSavedTick by remember { mutableIntStateOf(0) }
    var keyJustSaved by rememberSaveable { mutableStateOf(false) }

    // Re-read persisted state after each explicit save so the status line
    // and masked hint always reflect what is actually stored.
    @Suppress("UNUSED_EXPRESSION")
    keySavedTick
    @Suppress("UNUSED_EXPRESSION")
    endpointSavedTick
    @Suppress("UNUSED_EXPRESSION")
    thresholdSavedTick
    val storedKey = config.apiKey()
    val storedEndpoint = config.endpoint()
    val storedThreshold = config.confidenceThreshold()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Goal Engine",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Engine used by act_toward_goal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Engine tier ---
            Text(
                text = "Engine tier",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = tier == EngineTier.HEURISTIC.wire,
                    onClick = {
                        tier = EngineTier.HEURISTIC.wire
                        config.setTier(EngineTier.HEURISTIC)
                    }
                )
                Text(
                    text = "Heuristic (offline)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = tier == EngineTier.JEV.wire,
                    onClick = {
                        tier = EngineTier.JEV.wire
                        config.setTier(EngineTier.JEV)
                    }
                )
                Text(
                    text = "Jev (free tier)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // --- Jev API key ---
            Text(
                text = "Jev API key",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = {
                    keyInput = it
                    keyJustSaved = false
                },
                label = {
                    Text(
                        if (storedKey != null) "Key set (${maskGoalApiKey(storedKey)}) — enter new to replace"
                        else "Enter Jev API key"
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val trimmed = keyInput.trim()
                        if (trimmed.isNotEmpty()) {
                            config.setApiKey(trimmed)
                            keyInput = ""
                            keyJustSaved = true
                            keySavedTick += 1
                        }
                    },
                    enabled = keyInput.isNotBlank()
                ) {
                    Text("Save key")
                }
                if (storedKey != null) {
                    TextButton(
                        onClick = {
                            config.setApiKey("")
                            keyInput = ""
                            keyJustSaved = false
                            keySavedTick += 1
                        }
                    ) {
                        Text("Clear")
                    }
                }
            }
            if (keyJustSaved && storedKey != null) {
                Text(
                    text = "Saved (${maskGoalApiKey(storedKey)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Endpoint ---
            Text(
                text = "Endpoint",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = endpointInput,
                onValueChange = {
                    endpointInput = it
                    endpointError = null
                },
                label = { Text("Jev endpoint") },
                singleLine = true,
                isError = endpointError != null,
                supportingText = endpointError?.let { message ->
                    { Text(message) }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    if (!isValidGoalEndpoint(endpointInput)) {
                        endpointError = "Endpoint must start with https://"
                    } else {
                        config.setEndpoint(endpointInput.trim())
                        endpointError = null
                        endpointSavedTick += 1
                    }
                }
            ) {
                Text("Save endpoint")
            }

            // --- Confidence threshold ---
            Text(
                text = "Confidence threshold",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = thresholdInput,
                onValueChange = {
                    thresholdInput = it
                    thresholdError = null
                },
                label = { Text("Threshold (0.30 – 0.95)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = thresholdError != null,
                supportingText = {
                    Text(
                        thresholdError
                            ?: "Below this confidence the loop escalates to the calling agent instead of acting."
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    val parsed = parseGoalThreshold(thresholdInput)
                    if (parsed == null || parsed !in 0.30..0.95) {
                        thresholdError = "Threshold must be between 0.30 and 0.95"
                    } else {
                        config.setThreshold(parsed)
                        thresholdError = null
                        thresholdSavedTick += 1
                    }
                }
            ) {
                Text("Save threshold")
            }

            Spacer(modifier = Modifier.height(4.dp))

            // --- Effective-config status line ---
            val thresholdText = try {
                "%.2f".format(storedThreshold)
            } catch (_: Exception) {
                storedThreshold.toString()
            }
            Text(
                text = "Tier: $tier · threshold $thresholdText · " +
                    "key: ${if (storedKey != null) "set (${maskGoalApiKey(storedKey)})" else "not set"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Keep the endpoint echo compact: it is user-configured, not secret.
            Text(
                text = "Endpoint: $storedEndpoint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Note: tier B sends app UI text to the configured endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
