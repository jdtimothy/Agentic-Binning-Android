package com.agentic.binning.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.theme.LocalGlassTokens

/**
 * The failed item sheet (plan 2b Task 16, mockup state 8): why the item failed, then Retry analysis,
 * Set name and category by hand, or Remove from project. A missing file can only be removed.
 */
@Composable
fun FailedSheetHost(sheet: FailedSheetState, vm: ProjectMediaViewModel) {
    GlassSheet(onDismiss = vm::onCloseDetail, tall = true) {
        FailedContent(sheet, vm)
    }
    sheet.picker?.let { picker -> PickerSheet(picker, vm.detailPicker) }
    sheet.remove?.let { RemoveDialog(it, onCancel = vm::onCancelRemove, onRemove = vm::onConfirmRemove) }
}

private val YoursWhenSaved = LockMarker(MarkerKind.PendingYours, null)

@Composable
private fun ColumnScope.FailedContent(sheet: FailedSheetState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    val ui = sheet.ui
    // System Back in the by-hand form goes back to the three actions, keeping the sheet open.
    BackHandler(enabled = ui.manual, onBack = vm::onSetByHandBack)
    SheetHead(ui.title, vm::onCloseDetail)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MediaBox(sheet.image, sheet.imageIsVideo, ui.isVideo, ui.duration) {
            MediaBadge(MediaIcons.Alert, ui.badge, Alignment.TopStart, iconTint = tokens.warn)
        }
        // Not a Section: its heading is uppercased, and a file name must keep its case.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ui.fileName, Modifier.semantics { heading() }, color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(ui.reason, color = tokens.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ui.details.forEach { (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(k, Modifier.width(110.dp), color = tokens.sub, fontSize = 13.sp)
                    Text(v, color = tokens.text, fontSize = 13.sp)
                }
            }
        }
        if (!ui.manual) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.canRetry) {
                    Button("Retry analysis", MediaIcons.Retry, ButtonStyle.Primary, Modifier.fillMaxWidth(), enabled = !sheet.saving, onClick = vm::onRetryFailed)
                }
                if (ui.canSetByHand) {
                    Button("Set name and category by hand", MediaIcons.Pencil, ButtonStyle.Secondary, Modifier.fillMaxWidth(), enabled = !sheet.saving, onClick = vm::onSetByHand)
                }
                Button("Remove from project", MediaIcons.Remove, ButtonStyle.Danger, Modifier.fillMaxWidth(), enabled = !sheet.saving, onClick = vm::onRemoveDetail)
            }
        } else {
            Field("Friendly name", YoursWhenSaved, onLink = {}) {
                val focus = LocalFocusManager.current
                BasicTextField(
                    value = ui.name,
                    onValueChange = vm::onDetailName,
                    singleLine = true,
                    textStyle = TextStyle(color = tokens.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Friendly name" },
                    decorationBox = { inner ->
                        FieldBox {
                            if (ui.name.isEmpty()) Text("For example “Sunset Drive Home”", color = tokens.sub, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                Text(ui.nameHint, Modifier.padding(horizontal = 2.dp), color = tokens.sub, fontSize = 12.sp)
            }
            Field("Category", YoursWhenSaved, onLink = {}) {
                FieldBox(Modifier.clickable(role = Role.Button, onClick = vm::onOpenPicker)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ui.categoryLabel ?: "Choose a category",
                            Modifier.weight(1f),
                            color = if (ui.categoryLabel == null) tokens.sub else tokens.text,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ui.categoryIsNew) Text(" (new)", color = tokens.accent, fontSize = 13.sp)
                        Icon(MediaIcons.ChevronRight, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(18.dp))
                    }
                }
            }
            CalloutBox(Callout(ui.callout, warn = false))
        }
    }
    if (ui.manual) {
        HorizontalDivider(color = tokens.line)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sheet.error?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = tokens.danger, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Back", null, ButtonStyle.Secondary, onClick = vm::onSetByHandBack)
                Button("Save", null, ButtonStyle.Primary, Modifier.weight(1f), enabled = ui.saveEnabled && !sheet.saving, onClick = vm::onSaveByHand)
            }
        }
    }
}
