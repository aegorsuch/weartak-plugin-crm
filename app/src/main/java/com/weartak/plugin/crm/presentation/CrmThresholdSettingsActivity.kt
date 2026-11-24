package com.weartak.plugin.crm.presentation

/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weartak.plugin.crm.CrmApplication
import com.weartak.plugin.crm.service.CrmPluginRuntime
import com.weartak.plugin.crm.service.CrmSettingsStore
import com.weartak.plugin.sdk.model.SettingsApplyMode

class CrmThresholdSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as CrmApplication).settingsStore.current()
        setContent {
            CrmThresholdSettingsScreen(
                initialPoorMax = settings.poorMax,
                initialModerateMax = settings.moderateMax,
                onSave = { poorMax, moderateMax ->
                    val result = CrmPluginRuntime.applySettings(
                        Bundle().apply {
                            putDouble(CrmSettingsStore.FIELD_POOR_MAX, poorMax.toDouble())
                            putDouble(CrmSettingsStore.FIELD_MODERATE_MAX, moderateMax.toDouble())
                        },
                        SettingsApplyMode.PARTIAL_PATCH,
                    )
                    if (result.success) {
                        finish()
                        null
                    } else {
                        result.errors.joinToString("\n") { it.message }
                    }
                },
                onBack = { finish() },
            )
        }
    }
}

@Composable
private fun CrmThresholdSettingsScreen(
    initialPoorMax: Int,
    initialModerateMax: Int,
    onSave: (Int, Int) -> String?,
    onBack: () -> Unit,
) {
    var poorMax by remember { mutableStateOf(initialPoorMax.toString()) }
    var moderateMax by remember { mutableStateOf(initialModerateMax.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CRM Thresholds", style = MaterialTheme.typography.titleLarge)
        Text("Set score boundaries from 0 to 100.")
        OutlinedTextField(
            value = poorMax,
            onValueChange = { poorMax = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Poor maximum") },
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = moderateMax,
            onValueChange = { moderateMax = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Moderate maximum") },
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val poor = poorMax.toIntOrNull()
                val moderate = moderateMax.toIntOrNull()
                error = when {
                    poor == null || moderate == null -> "Enter both threshold values."
                    else -> onSave(poor, moderate)
                }
            },
        ) {
            Text("Save thresholds")
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = onBack) {
            Text("Back")
        }
    }
}
