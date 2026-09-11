// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.agent.accessibility.controller.AccessibilityController
import com.agent.accessibility.ui.DebugScreen

class MainActivity : ComponentActivity() {

    private lateinit var controller: AccessibilityController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AccessibilityController(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background
                ) {
                    DebugScreen(controller = controller)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
}
