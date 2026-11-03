package com.agentic.binning.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.theme.LocalGlassTokens

/**
 * Edit categories (plan 2b Task 14, mockup states 5a and 5b): rename inline with the clash check,
 * or tick two to merge and choose the name that survives. Save applies every staged change at once.
 * In 2b no category has a folder yet, so the mockup's file consequences don't show.
 */
@Composable
fun CategoryEditSheet(state: CategoryEditState, actions: CategoryEditActions) {
    val tokens = LocalGlassTokens.current
    val ui = state.ui
    GlassSheet(onDismiss = actions::onClose, tall = true) {
        SheetHead("Edit categories", actions::onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Rename a category, or tick two to merge them. Categories you rename or merge become yours, so sorting won't change them.",
                color = tokens.sub,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            ui.mergeBox?.let { MergeBoxView(it, actions) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.chip)) {
                ui.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = tokens.line)
                    CategoryRow(row, actions)
                }
            }
        }
        HorizontalDivider(color = tokens.line)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = tokens.danger, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Cancel", null, ButtonStyle.Secondary, onClick = actions::onClose)
                Button(ui.saveLabel, null, ButtonStyle.Primary, Modifier.weight(1f), enabled = ui.canSave && !state.saving, onClick = actions::onSave)
            }
        }
    }
}

@Composable
private fun MergeBoxView(box: MergeBox, actions: CategoryEditActions) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(tokens.accentSoft).border(1.dp, tokens.accent, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MediaIcons.Merge, contentDescription = null, tint = tokens.text, modifier = Modifier.size(16.dp))
            Text(box.title, Modifier.semantics { heading() }, color = tokens.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        box.options.forEach { o ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics { selected = o.selected }
                    .clickable(role = Role.RadioButton) { actions.onKeep(o.id) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .border(2.dp, if (o.selected) tokens.accent else tokens.sub, CircleShape)
                        .padding(4.dp)
                        .background(if (o.selected) tokens.accent else Color.Transparent, CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(o.label, color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(o.detail, color = tokens.sub, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Cancel", null, ButtonStyle.Secondary, onClick = actions::onUntick)
            Button("Merge", MediaIcons.Merge, ButtonStyle.Primary, Modifier.weight(1f), onClick = actions::onMerge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(row: CategoryEditRow, actions: CategoryEditActions) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (row.ticked) tokens.accentSoft else Color.Transparent)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .alpha(if (row.tickEnabled) 1f else 0.35f)
                .clip(RoundedCornerShape(7.dp))
                .background(if (row.ticked) tokens.accent else Color.Transparent)
                .border(2.dp, if (row.ticked) tokens.accent else tokens.sub, RoundedCornerShape(7.dp))
                .semantics {
                    contentDescription = "Select ${row.name} to merge"
                    stateDescription = if (row.ticked) "Ticked" else "Not ticked"
                }
                .clickable(enabled = row.tickEnabled, role = Role.Checkbox) { actions.onTick(row.id) },
            contentAlignment = Alignment.Center,
        ) {
            if (row.ticked) Icon(MediaIcons.Check, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(14.dp))
        }
        if (row.editing) {
            // The keyboard covers the lower half: keep the field and its clash line in view as it changes.
            val inView = remember { BringIntoViewRequester() }
            LaunchedEffect(row.editError) { inView.bringIntoView() }
            Column(Modifier.weight(1f).bringIntoViewRequester(inView), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicTextField(
                    value = row.editValue,
                    onValueChange = actions::onRenameText,
                    singleLine = true,
                    textStyle = TextStyle(color = tokens.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onRenameDone() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New name" },
                    decorationBox = { inner -> FieldBox(height = 42) { inner() } },
                )
                val error = row.editError
                Text(
                    error ?: "No folder yet; only the database changes",
                    color = if (error != null) tokens.danger else tokens.sub,
                    fontSize = 12.sp,
                )
            }
            RowIcon(MediaIcons.Check, "Done renaming", enabled = row.canFinish, tint = tokens.accent, onClick = actions::onRenameDone)
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(row.name, color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ProjectMediaViewModel.countOf(row.count, "item"), color = tokens.sub, fontSize = 12.sp)
                    if (row.yours) {
                        Text(" · ", color = tokens.sub, fontSize = 12.sp)
                        Icon(MediaIcons.Lock, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(12.dp))
                        Text("yours", color = tokens.sub, fontSize = 12.sp)
                    }
                }
                row.changes.forEach { change ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (change.startsWith("Merged")) MediaIcons.Merge else MediaIcons.Pencil,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(change, color = tokens.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            RowIcon(MediaIcons.Pencil, "Rename ${row.name}", enabled = true, tint = tokens.sub) { actions.onRenameStart(row.id) }
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, label: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
