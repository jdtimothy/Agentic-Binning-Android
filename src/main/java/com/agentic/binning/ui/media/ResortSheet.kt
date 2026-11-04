package com.agentic.binning.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.theme.LocalGlassTokens

private val CardShape = RoundedCornerShape(16.dp)

/**
 * The Re-sort sheet (plan 2b Task 15, mockup state 7): the model for this run, Keep my edits, what
 * changes and what doesn't, and "Re-sort N items", off with its reason when it can't run.
 */
@Composable
fun ResortSheetHost(sheet: ResortSheetState, vm: ProjectMediaViewModel) {
    GlassSheet(onDismiss = vm::onCloseResort, tall = true) {
        ResortContent(sheet, vm)
    }
}

@Composable
private fun ColumnScope.ResortContent(sheet: ResortSheetState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    val ui = sheet.ui
    SheetHead("Re-sort project", vm::onCloseResort)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(ui.intro, color = tokens.text, fontSize = 14.sp, lineHeight = 20.sp)
        // A radio group: TalkBack says which model is picked.
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Sorting model for this run")
            ui.rows.forEach { row -> ModelCard(row) { vm.onResortModel(row.choice) } }
        }
        CalloutBox(Callout(ui.modelNote, warn = false))
        KeepEditsRow(ui.keepEdits, ui.keepText, vm::onResortKeepEdits)
        Panel(MediaIcons.Shuffle, "Changes", ui.changes)
        Panel(MediaIcons.Folder, "Doesn't change now", ui.unchanged)
    }
    HorizontalDivider(color = tokens.line)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (sheet.error ?: ui.reason)?.let {
            Text(it, Modifier.padding(horizontal = 4.dp), color = if (sheet.error != null) tokens.danger else tokens.sub, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Cancel", null, ButtonStyle.Secondary, onClick = vm::onCloseResort)
            Button(
                ui.buttonLabel,
                MediaIcons.Shuffle,
                ButtonStyle.Primary,
                Modifier.weight(1f),
                enabled = ui.enabled && !sheet.starting,
                onClick = vm::onRunResort,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    val tokens = LocalGlassTokens.current
    Text(text.uppercase(), color = tokens.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
}

@Composable
private fun ModelCard(row: ResortModelRow, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(tokens.field)
            .border(1.dp, if (row.selected) tokens.accent else tokens.line, CardShape)
            .selectable(selected = row.selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).border(2.dp, if (row.selected) tokens.accent else tokens.sub, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (row.selected) Box(Modifier.size(10.dp).background(tokens.accent, CircleShape)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (row.settingsChoice) {
                    Text(
                        "Settings choice",
                        Modifier.background(tokens.accentSoft, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = tokens.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(row.subtitle, color = if (row.unavailable) tokens.warn else tokens.sub, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun KeepEditsRow(checked: Boolean, text: String, onToggle: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier.fillMaxWidth().clip(CardShape).toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .background(if (checked) tokens.accent else Color.Transparent, RoundedCornerShape(6.dp))
                .border(2.dp, if (checked) tokens.accent else tokens.sub, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(MediaIcons.Check, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(14.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Keep my edits", color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = tokens.sub, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Panel(icon: ImageVector, title: String, lines: List<String>) {
    val tokens = LocalGlassTokens.current
    Column(
        Modifier.fillMaxWidth().background(tokens.chip, CardShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(14.dp))
            Text(title, color = tokens.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        lines.forEach { Text("• $it", color = tokens.sub, fontSize = 13.sp, lineHeight = 18.sp) }
    }
}
