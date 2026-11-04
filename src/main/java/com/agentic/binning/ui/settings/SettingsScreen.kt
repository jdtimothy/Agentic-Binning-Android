package com.agentic.binning.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.model.DownloadState
import com.agentic.binning.settings.AppSettings
import com.agentic.binning.settings.ApiKeyInfo
import com.agentic.binning.settings.FRAME_CAP_OPTIONS
import com.agentic.binning.settings.MediaPickerChoice
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.glass.GlassIconButton
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.project.sheetOutline
import com.agentic.binning.ui.theme.LocalGlassTokens
import com.agentic.binning.ui.theme.ThemeMode
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/** Backdrop blob colours shared by the top-level screens. */
val AmbientBlobColors = listOf(Color(0xFF6CB6FF), Color(0xFF9B7BFF), Color(0xFF3DD6B0))

/** The saved Claude key as Settings knows it; [info] is null when no key is saved. */
data class KeyRow(val info: ApiKeyInfo?)

interface SettingsActions {
    fun onThemeMode(mode: ThemeMode)
    fun onKeepScreenOn(on: Boolean)

    /** The user's pick; it is remembered, so a later key save no longer switches the model. */
    fun onSortingModel(choice: SortingModelChoice)

    /** Downloads [choice]'s on-device model (after the confirm dialog, or Retry). */
    fun onDownloadSortingModel(choice: SortingModelChoice)

    /** Saves a non-blank key (never logged); [onResult] gets whether it was saved. */
    fun onSaveKey(key: String, onResult: (Boolean) -> Unit)
    fun onRemoveKey()
    fun onMaxFramesPerClip(cap: Int)
    fun onMediaPicker(choice: MediaPickerChoice)
    fun onBack()
}

/**
 * [keyRow] is null until the saved key has been read; [sortingDownload] is the on-device sorting
 * model's download state (null until known).
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    keyRow: KeyRow?,
    sortingDownload: DownloadState?,
    actions: SettingsActions,
) {
    val tokens = LocalGlassTokens.current
    val hazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()
    val keyField = remember { BringIntoViewRequester() }
    // Set when Claude is picked without a key; the key section then asks for one.
    var askForKey by rememberSaveable { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf<SortingPick.AskDownload?>(null) }
    // Null until the saved key has been read.
    val hasKey = keyRow?.let { it.info != null }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop(AmbientBlobColors, hazeState)
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(onClick = actions::onBack, contentDescription = "Back") {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = tokens.text)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Settings", color = tokens.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }

                SettingsCard {
                    CardTitle("Sorting model")
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SortingOptions.forEach { option ->
                            val download = if (option.spec != null) sortingDownload else null
                            SortingRow(
                                option = option,
                                status = sortingRowStatus(option.choice, download),
                                selected = settings.sortingModel == option.choice,
                                onClick = {
                                    when (val pick = pickSorting(option.choice, settings.sortingModel, download, hasKey)) {
                                        SortingPick.Nothing -> Unit
                                        is SortingPick.Select -> actions.onSortingModel(pick.choice)
                                        is SortingPick.AskDownload -> confirmDownload = pick
                                        is SortingPick.NeedKey -> {
                                            if (pick.select) actions.onSortingModel(option.choice)
                                            askForKey = true
                                            scope.launch { keyField.bringIntoView() }
                                        }
                                    }
                                },
                                onRetry = { actions.onDownloadSortingModel(option.choice) },
                            )
                        }
                    }
                }

                SettingsCard(Modifier.bringIntoViewRequester(keyField)) {
                    CardTitle("Claude API key")
                    if (keyRow != null) {
                        val info = keyRow.info
                        if (info != null) {
                            SavedKey(info, actions::onRemoveKey)
                        } else {
                            // Also after the key was removed while Claude stays the sorting model.
                            if (askForKey || settings.sortingModel == SortingModelChoice.Claude) {
                                Text(NEED_KEY_TEXT, color = tokens.warn, fontSize = 14.sp)
                            }
                            KeyField(onSave = { key, onResult ->
                                actions.onSaveKey(key) { ok ->
                                    if (ok) askForKey = false
                                    onResult(ok)
                                }
                            })
                        }
                    }
                    Text(KEY_HELPER_TEXT, color = tokens.sub, fontSize = 13.sp)
                }

                SettingsCard {
                    CardTitle("Max frames per clip")
                    SegmentedControl(
                        options = FRAME_CAP_OPTIONS,
                        selected = settings.maxFramesPerClip,
                        label = Int::toString,
                        onSelect = actions::onMaxFramesPerClip,
                    )
                    Text(frameHint(settings.maxFramesPerClip), color = tokens.sub, fontSize = 13.sp)
                }

                SettingsCard {
                    CardTitle("Photo and video picker")
                    SegmentedControl(
                        options = MediaPickerChoice.entries,
                        selected = settings.mediaPicker,
                        label = ::mediaPickerLabel,
                        onSelect = actions::onMediaPicker,
                    )
                    Text(mediaPickerHint(settings.mediaPicker), color = tokens.sub, fontSize = 13.sp)
                }

                SettingsCard {
                    CardTitle("Theme")
                    SegmentedControl(ThemeMode.entries, settings.themeMode, { it.name }, actions::onThemeMode)
                }

                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .toggleable(
                                value = settings.keepScreenOn,
                                role = Role.Switch,
                                onValueChange = actions::onKeepScreenOn,
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Keep screen on", color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Keeps the screen on while the app is open and analysis is running",
                                color = tokens.sub,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.keepScreenOn,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tokens.onAccent,
                                checkedTrackColor = tokens.accent,
                                uncheckedThumbColor = tokens.text,
                                uncheckedTrackColor = tokens.track,
                                uncheckedBorderColor = tokens.border,
                            ),
                        )
                    }
                }
            }
        }
    }

    confirmDownload?.let { pick ->
        // The dialog sits outside the blurred backdrop, so it gets the glass colour made opaque.
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(pick.title, color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = null
                    if (pick.select) actions.onSortingModel(pick.choice)
                    actions.onDownloadSortingModel(pick.choice)
                }) { Text("Download", color = tokens.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) { Text("Cancel", color = tokens.text) }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = tokens.glassStrong.compositeOver(tokens.bg),
        )
    }
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = LocalGlassTokens.current.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

/** A sorting model with a radio mark; its status line turns into a progress bar while downloading. */
@Composable
private fun SortingRow(
    option: SortingOption,
    status: RowStatus,
    selected: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) tokens.chip else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(option.title, color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(status.text, color = if (status.canRetry) tokens.warn else tokens.sub, fontSize = 13.sp)
            status.progress?.let { fraction ->
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(tokens.track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(tokens.accent, CircleShape),
                    )
                }
            }
        }
        if (status.canRetry) {
            Spacer(Modifier.width(8.dp))
            SmallPill("Retry", onRetry)
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .size(22.dp)
            .border(if (selected) 6.dp else 1.5.dp, if (selected) tokens.accent else sheetOutline(tokens), CircleShape),
    )
}

/**
 * The masked key field with its Save button. The text stays in this composable only, and is kept
 * until the save succeeds; a failed save shows [KeyFieldState.error] under the field.
 */
@Composable
private fun KeyField(onSave: (key: String, onResult: (Boolean) -> Unit) -> Unit) {
    val tokens = LocalGlassTokens.current
    var field by remember { mutableStateOf(KeyFieldState("")) }
    var saving by remember { mutableStateOf(false) }
    val text = field.text
    val save = {
        if (canSaveKey(text) && !saving) {
            saving = true
            onSave(text) { ok ->
                saving = false
                field = field.afterSave(ok)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { field = field.edit(it) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("sk-ant-…", color = tokens.sub) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = tokens.text,
                unfocusedTextColor = tokens.text,
                cursorColor = tokens.accent,
                focusedBorderColor = tokens.accent,
                unfocusedBorderColor = sheetOutline(tokens),
            ),
        )
        Spacer(Modifier.width(10.dp))
        SmallPill("Save", onClick = save, enabled = canSaveKey(text) && !saving, primary = true)
    }
    field.error?.let { Text(it, color = tokens.warn, fontSize = 13.sp) }
}

@Composable
private fun SavedKey(info: ApiKeyInfo, onRemove: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(keySavedText(info), Modifier.weight(1f), color = tokens.text, fontSize = 15.sp)
        SmallPill("Remove", onRemove)
    }
}

/** Content-sized pill; the touch target is still 48 dp. */
@Composable
private fun SmallPill(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> tokens.chip
                    primary -> tokens.accent
                    else -> tokens.chip
                },
            )
            .border(1.dp, if (primary && enabled) Color.Transparent else sheetOutline(tokens), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> tokens.sub
                primary -> tokens.onAccent
                else -> tokens.text
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun <T> SegmentedControl(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.chip, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (isSelected) tokens.accent else Color.Transparent, CircleShape)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = if (isSelected) tokens.onAccent else tokens.text,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
