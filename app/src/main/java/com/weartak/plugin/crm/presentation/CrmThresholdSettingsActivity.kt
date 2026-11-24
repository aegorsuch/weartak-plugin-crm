package com.weartak.plugin.crm.presentation

/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Button as Material3Button
import androidx.compose.material3.ButtonDefaults as Material3ButtonDefaults
import androidx.compose.material3.Icon as Material3Icon
import androidx.compose.material3.IconButton as Material3IconButton
import androidx.compose.material3.IconButtonDefaults as Material3IconButtonDefaults
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text as WearText
import androidx.wear.compose.material.rememberScalingLazyListState
import com.weartak.plugin.crm.CrmApplication
import com.weartak.plugin.crm.R
import com.weartak.plugin.crm.data.sanitizeCrmThresholdRanges
import com.weartak.plugin.crm.service.CrmPluginRuntime
import com.weartak.plugin.crm.service.CrmSettingsStore
import com.weartak.plugin.sdk.model.SettingsApplyMode

private const val MAX_THRESHOLD = 100

private enum class ThresholdPage {
    MENU,
    POOR_MAX,
    MODERATE_MAX,
}

class CrmThresholdSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as CrmApplication).settingsStore.current()
        setContent {
            CrmWearTheme {
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
                            null
                        } else {
                            result.errors.joinToString("\n") { it.message }
                        }
                    },
                    onOpenReserve = {
                        startActivity(Intent(this, CrmReserveActivity::class.java))
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun CrmThresholdSettingsScreen(
    initialPoorMax: Int,
    initialModerateMax: Int,
    onSave: (Int, Int) -> String?,
    onOpenReserve: () -> Unit,
    onBack: () -> Unit,
) {
    var poorMax by rememberSaveable { mutableStateOf(initialPoorMax) }
    var moderateMax by rememberSaveable { mutableStateOf(initialModerateMax) }
    var page by rememberSaveable { mutableStateOf(ThresholdPage.MENU) }
    var entryId by rememberSaveable { mutableStateOf(0) }

    BackHandler(enabled = page != ThresholdPage.MENU) {
        page = ThresholdPage.MENU
    }

    when (page) {
        ThresholdPage.MENU -> CrmThresholdMenu(
            poorMax = poorMax,
            moderateMax = moderateMax,
            onPoorClick = {
                entryId += 1
                page = ThresholdPage.POOR_MAX
            },
            onModerateClick = {
                entryId += 1
                page = ThresholdPage.MODERATE_MAX
            },
            onOpenReserve = onOpenReserve,
            onBack = onBack,
        )

        ThresholdPage.POOR_MAX -> CrmThresholdEntryPage(
            item = poorMax,
            label = "Poor Maximum",
            entryId = entryId,
            onCancel = { page = ThresholdPage.MENU },
            onConfirm = { entered ->
                val sanitized = sanitizeCrmThresholdRanges(entered, moderateMax)
                val error = onSave(sanitized.poorMax, sanitized.moderateMax)
                if (error == null) {
                    poorMax = sanitized.poorMax
                    moderateMax = sanitized.moderateMax
                    page = ThresholdPage.MENU
                }
                error
            },
        )

        ThresholdPage.MODERATE_MAX -> CrmThresholdEntryPage(
            item = moderateMax,
            label = "Moderate Maximum",
            entryId = entryId,
            onCancel = { page = ThresholdPage.MENU },
            onConfirm = { entered ->
                val sanitized = sanitizeCrmThresholdRanges(poorMax, entered)
                val error = onSave(sanitized.poorMax, sanitized.moderateMax)
                if (error == null) {
                    poorMax = sanitized.poorMax
                    moderateMax = sanitized.moderateMax
                    page = ThresholdPage.MENU
                }
                error
            },
        )
    }
}

@Composable
private fun CrmThresholdMenu(
    poorMax: Int,
    moderateMax: Int,
    onPoorClick: () -> Unit,
    onModerateClick: () -> Unit,
    onOpenReserve: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        positionIndicator = {
            PositionIndicator(scalingLazyListState = listState)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WearMaterialTheme.colors.background)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp)
                    .align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    WearText(
                        text = "CRM Thresholds",
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .padding(bottom = 8.dp),
                    )
                }
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenReserve,
                        label = { WearText("CRM Reserve") },
                    )
                }
                item {
                    ThresholdValueChip(
                        title = "Poor Maximum",
                        value = poorMax,
                        onClick = onPoorClick,
                    )
                }
                item {
                    ThresholdValueChip(
                        title = "Moderate Maximum",
                        value = moderateMax,
                        onClick = onModerateClick,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
            ) {
                Material3Button(
                    onClick = onBack,
                    modifier = Modifier
                        .testTag("settings_back")
                        .size(height = 48.dp, width = 80.dp),
                    colors = Material3ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                        contentColor = Color.Red,
                    ),
                ) {
                    Material3Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.route_back),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThresholdValueChip(
    title: String,
    value: Int,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        label = { WearText(text = title) },
        secondaryLabel = {
            WearText(
                text = "$value%",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CrmThresholdEntryPage(
    item: Int,
    label: String,
    entryId: Int,
    onCancel: () -> Unit,
    onConfirm: (Int) -> String?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var textField = TextFieldValue(item.toString(), TextRange(item.toString().length))

    fun validateAndToast(value: String): Int? {
        val entered = value.toIntOrNull()
        val message = when {
            value.isEmpty() -> context.getString(R.string.please_enter_a_value)
            entered == null -> context.getString(R.string.crm_threshold_value_not_number)
            entered > MAX_THRESHOLD -> context.getString(R.string.value_exceeds_maximum_please_re_enter)
            entered < 0 -> context.getString(R.string.crm_threshold_value_less_than_minimum)
            else -> null
        }
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return null
        }
        return entered
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearMaterialTheme.colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        textField = CrmPlatformNumberField(
            string = item.toString(),
            label = label,
            entryId = entryId,
            keyboardActions = KeyboardActions(
                onDone = {
                    if (validateAndToast(textField.text) != null) {
                        focusManager.clearFocus()
                    }
                },
            ),
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            Material3IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(50.dp)
                        .testTag("form_cancel")
                    .border(1.dp, Color.Black, CircleShape),
                colors = Material3IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red,
                    containerColor = Color.DarkGray,
                ),
            ) {
                Material3Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = context.getString(R.string.cancel),
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Material3IconButton(
                onClick = {
                    validateAndToast(textField.text)?.let { entered ->
                        onConfirm(entered)?.let { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier
                    .size(50.dp)
                        .testTag("form_confirm")
                    .border(1.dp, Color.Black, CircleShape),
                colors = Material3IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Black,
                    containerColor = WearMaterialTheme.colors.primary,
                ),
            ) {
                Material3Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = context.getString(R.string.confirm),
                    tint = Color.Black,
                )
            }
        }
    }
}

@Composable
private fun CrmPlatformNumberField(
    string: String,
    label: String,
    entryId: Int,
    keyboardActions: KeyboardActions,
): TextFieldValue {
    val state = rememberSaveable(entryId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(string, TextRange(string.length)))
    }
    var value by state
    val actions by rememberUpdatedState(keyboardActions)
    val holder = remember { arrayOfNulls<EditText>(1) }
    var hadFocus by remember { mutableStateOf(false) }
    val primary = WearMaterialTheme.colors.primary

    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        Material3Text(
            text = label,
            color = primary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    background = null
                    tag = "weartak_platform_text_field"
                    isSaveEnabled = false
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    hint = label
                    setHintTextColor(primary.copy(alpha = 0.6f).toArgb())
                    setTextColor(primary.toArgb())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setText(value.text)
                    setSelection(
                        value.selection.start.coerceIn(0, text.length),
                        value.selection.end.coerceIn(0, text.length),
                    )
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            value = TextFieldValue(
                                s?.toString().orEmpty(),
                                TextRange(selectionStart, selectionEnd),
                            )
                        }
                    })
                    setOnEditorActionListener { view, actionId, event ->
                        val done = actionId == EditorInfo.IME_ACTION_DONE ||
                            (actionId == EditorInfo.IME_NULL &&
                                event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                                event.action == KeyEvent.ACTION_DOWN)
                        if (!done) {
                            return@setOnEditorActionListener false
                        }
                        val scope = object : KeyboardActionScope {
                            override fun defaultKeyboardAction(imeAction: ImeAction) {
                                hideThresholdKeyboard(view as EditText)
                            }
                        }
                        val onDone = actions.onDone
                        if (onDone != null) {
                            onDone.invoke(scope)
                        } else {
                            hideThresholdKeyboard(view as EditText)
                        }
                        true
                    }
                    holder[0] = this
                }
            },
            update = { field ->
                field.hint = label
                if (field.text.toString() != value.text) {
                    field.setText(value.text)
                    field.setSelection(
                        value.selection.start.coerceIn(0, field.text.length),
                        value.selection.end.coerceIn(0, field.text.length),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .border(1.dp, primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp)
                .onFocusChanged { focusState ->
                    if (hadFocus && !focusState.hasFocus) {
                        holder[0]?.let(::hideThresholdKeyboard)
                    }
                    hadFocus = focusState.hasFocus
                },
        )
    }
    return value
}

private fun hideThresholdKeyboard(editText: EditText) {
    editText.clearFocus()
    val inputManager = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    inputManager?.hideSoftInputFromWindow(editText.windowToken, 0)
}
