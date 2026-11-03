package com.agentic.binning.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.theme.LocalGlassTokens

private val SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
private val FieldShape = RoundedCornerShape(14.dp)
private val BadgeBg = Color(0x8C000000)

/**
 * The item detail sheet (plan 2b Task 12, mockup state 3), with the category picker (state 3b)
 * and the remove confirmation stacked over it. Every edit waits for Save (Q6).
 */
@Composable
fun DetailSheetHost(sheet: DetailSheetState, vm: ProjectMediaViewModel) {
    GlassSheet(onDismiss = vm::onCloseDetail, tall = true) {
        DetailContent(sheet, vm)
    }
    sheet.picker?.let { picker -> PickerSheet(picker, vm.detailPicker) }
    sheet.remove?.let { RemoveDialog(it, onCancel = vm::onCancelRemove, onRemove = vm::onConfirmRemove) }
}

/** A bottom sheet in its own window: an opaque theme base under strong glass, as the project sheet does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassSheet(onDismiss: () -> Unit, tall: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalGlassTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        CompositionLocalProvider(LocalHazeState provides null) {
            Box(Modifier.statusBarsPadding().padding(top = 8.dp).background(tokens.bg, SheetShape)) {
                GlassSurface(Modifier.fillMaxWidth(), shape = SheetShape, strong = true) {
                    Column(
                        Modifier
                            .then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier)
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        Box(Modifier.padding(top = 10.dp, bottom = 8.dp).align(Alignment.CenterHorizontally).size(40.dp, 5.dp).background(tokens.track, CircleShape))
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHead(title: String, onClose: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).semantics { heading() }, color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        RoundIcon(MediaIcons.Close, "Close", onClose, size = 38, iconSize = 18)
    }
}

@Composable
private fun ColumnScope.DetailContent(sheet: DetailSheetState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    val ui = sheet.ui
    SheetHead(ui.title, vm::onCloseDetail)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Media(sheet)
        Field("Friendly name", ui.nameMarker, onLink = vm::onToggleUnlockName) {
            val focus = LocalFocusManager.current
            BasicTextField(
                value = ui.name,
                onValueChange = vm::onDetailName,
                singleLine = true,
                textStyle = TextStyle(color = tokens.text, fontSize = 16.sp),
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Friendly name" },
                decorationBox = { inner -> FieldBox { inner() } },
            )
            Text(ui.nameHint, Modifier.padding(horizontal = 2.dp), color = tokens.sub, fontSize = 12.sp)
        }
        Field("Category", ui.categoryMarker, onLink = vm::onToggleUnlockCategory) {
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
        CalloutBox(ui.callout)
        Section("Description") { Text(ui.description, color = tokens.text, fontSize = 14.sp, lineHeight = 20.sp) }
        if (ui.actions.isNotEmpty()) {
            Section("Actions") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.actions.forEach { a ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(a.time.orEmpty(), Modifier.width(44.dp), color = tokens.accent, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                            Text(a.text, color = tokens.text, fontSize = 13.5.sp)
                        }
                    }
                }
            }
        }
        if (ui.tags.isNotEmpty()) Section("Tags") { Tags(ui.tags) }
        Section("Details") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ui.details.forEach { (k, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(k, Modifier.width(110.dp), color = tokens.sub, fontSize = 13.sp)
                        Text(v, color = tokens.text, fontSize = 13.sp)
                    }
                }
            }
        }
        Button("Remove from project", MediaIcons.Remove, ButtonStyle.Danger, Modifier.fillMaxWidth(), onClick = vm::onRemoveDetail)
    }
    HorizontalDivider(color = tokens.line)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sheet.error?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = tokens.danger, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Cancel", null, ButtonStyle.Secondary, onClick = vm::onCloseDetail)
            Button(
                "Save",
                null,
                ButtonStyle.Primary,
                Modifier.weight(1f),
                enabled = ui.saveEnabled && !sheet.saving,
                onClick = vm::onSaveDetail,
            )
        }
    }
}

@Composable
private fun Media(sheet: DetailSheetState) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    val request = remember(sheet.image, sheet.imageIsVideo) {
        ImageRequest.Builder(context)
            .data(sheet.image)
            .apply { if (sheet.imageIsVideo) decoderFactory(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    Box(Modifier.fillMaxWidth().height(214.dp).clip(RoundedCornerShape(20.dp)).background(tokens.chip)) {
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val ui = sheet.ui
        if (ui.isVideo) {
            Box(
                Modifier.align(Alignment.Center).size(56.dp).background(Color(0x73000000), CircleShape).border(1.dp, Color(0x66FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(MediaIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            ui.duration?.let { MediaBadge(MediaIcons.Play, it, Alignment.BottomEnd) }
        }
        when (ui.badge) {
            StatusBadge.Waiting -> MediaBadge(MediaIcons.Clock, "Waiting to be categorized", Alignment.TopStart)
            StatusBadge.Sorted -> MediaBadge(null, "Sorted", Alignment.TopStart)
        }
    }
}

@Composable
private fun BoxScope.MediaBadge(icon: ImageVector?, text: String, align: Alignment) {
    Row(
        Modifier.align(align).padding(10.dp).background(BadgeBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Field(label: String, marker: LockMarker, onLink: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalGlassTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), Modifier.weight(1f), color = tokens.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
            MarkerPill(marker)
            marker.link?.let { link ->
                Text(
                    link,
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onLink).padding(2.dp),
                    color = tokens.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        content()
    }
}

@Composable
private fun MarkerPill(marker: LockMarker) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(11.dp)
    val (text, fill, color) = when (marker.kind) {
        MarkerKind.FromSorting -> Triple("From sorting", tokens.chip, tokens.sub)
        MarkerKind.SetByYou -> Triple("Set by you", tokens.accentSoft, tokens.accent)
        MarkerKind.WillUnlock -> Triple("Sorting can change it after you save", tokens.chip, tokens.sub)
        MarkerKind.PendingYours -> Triple("Set by you when you save", Color.Transparent, tokens.accent)
        MarkerKind.NotPlaced -> Triple("Not placed yet", tokens.warnSoft, tokens.warn)
    }
    val icon = when (marker.kind) {
        MarkerKind.SetByYou, MarkerKind.PendingYours -> MediaIcons.Lock
        MarkerKind.NotPlaced -> MediaIcons.Clock
        else -> null
    }
    Row(
        Modifier
            .height(22.dp)
            .background(fill, shape)
            .then(if (marker.kind == MarkerKind.PendingYours) Modifier.border(1.dp, tokens.accent, shape) else Modifier)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun FieldBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tokens = LocalGlassTokens.current
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(FieldShape)
            .background(tokens.field)
            .border(1.dp, tokens.line, FieldShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

@Composable
private fun CalloutBox(callout: Callout) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier.fillMaxWidth().background(if (callout.warn) tokens.warnSoft else tokens.chip, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (callout.warn) MediaIcons.Clock else MediaIcons.Info,
            contentDescription = null,
            tint = if (callout.warn) tokens.warn else tokens.accent,
            modifier = Modifier.padding(top = 1.dp).size(18.dp),
        )
        Text(callout.text, color = tokens.text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val tokens = LocalGlassTokens.current
    Column {
        Text(title.uppercase(), Modifier.padding(bottom = 6.dp).semantics { heading() }, color = tokens.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(tags: List<String>) {
    val tokens = LocalGlassTokens.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach {
            Text(it, Modifier.background(tokens.tagBg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), color = tokens.tagText, fontSize = 12.5.sp)
        }
    }
}

internal enum class ButtonStyle { Primary, Secondary, Danger }

@Composable
internal fun Button(
    label: String,
    icon: ImageVector?,
    style: ButtonStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val (fill, content) = when {
        !enabled -> tokens.chip to tokens.sub
        style == ButtonStyle.Primary -> tokens.accent to tokens.onAccent
        style == ButtonStyle.Danger -> tokens.chip to tokens.danger
        else -> tokens.chip to tokens.text
    }
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The category picker (mockup state 3b): New category… first, then every category with its count.
 * For bulk move (state 4) it keeps the choice and saves from its footer ("Save · move N").
 */
@Composable
internal fun PickerSheet(picker: PickerUi, actions: PickerActions) {
    GlassSheet(onDismiss = actions::onClose, tall = false) { PickerContent(picker, actions) }
}

@Composable
private fun ColumnScope.PickerContent(picker: PickerUi, actions: PickerActions) {
    val tokens = LocalGlassTokens.current
    SheetHead(picker.title, actions::onClose)
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.chip),
    ) {
        if (!picker.newOpen) {
            PickerRow(
                selected = null,
                title = "New category…",
                subtitle = "Yours: sorting never renames or merges it",
                newStyle = true,
                onClick = actions::onNewOpen,
            ) { Icon(MediaIcons.Plus, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(18.dp)) }
        } else {
            NewCategoryField(picker, actions)
        }
        picker.pendingNew?.let { name ->
            RowDivider()
            PickerRow(selected = true, title = name, subtitle = "New · created when you save", onClick = actions::onPickPendingNew) {
                Icon(MediaIcons.Lock, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(14.dp))
            }
        }
        picker.options.forEach { o ->
            RowDivider()
            PickerRow(selected = o.selected, title = o.name, subtitle = o.description.takeIf { it.isNotBlank() }, onClick = { actions.onPick(o.id) }) {
                if (o.locked) Icon(MediaIcons.Lock, contentDescription = "Yours", tint = tokens.sub, modifier = Modifier.size(13.dp))
                Text("${o.count}", color = tokens.sub, style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"))
            }
        }
    }
    picker.saveLabel?.let { label ->
        HorizontalDivider(color = tokens.line)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(MediaIcons.Info, contentDescription = null, tint = tokens.sub, modifier = Modifier.padding(top = 1.dp).size(14.dp))
                Text(
                    "Only the database changes now. The files move at the next Bin Files. Moved items count as set by you.",
                    color = tokens.sub,
                    fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Cancel", null, ButtonStyle.Secondary, onClick = actions::onClose)
                Button(label, null, ButtonStyle.Primary, Modifier.weight(1f), enabled = picker.canSave, onClick = actions::onSave)
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = LocalGlassTokens.current.line)

@Composable
private fun PickerRow(
    selected: Boolean?,
    title: String,
    subtitle: String?,
    newStyle: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics { if (selected != null) this.selected = selected }
            .clickable(role = if (selected != null) Role.RadioButton else Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, if (selected == true || newStyle) tokens.accent else tokens.sub, CircleShape)
                .padding(4.dp)
                .background(if (selected == true) tokens.accent else Color.Transparent, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (newStyle) tokens.accent else tokens.text,
                fontSize = 15.sp,
                fontWeight = if (newStyle) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { Text(it, color = tokens.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun NewCategoryField(picker: PickerUi, actions: PickerActions) {
    val tokens = LocalGlassTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NEW CATEGORY", color = tokens.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = picker.newName,
                onValueChange = actions::onNewName,
                singleLine = true,
                textStyle = TextStyle(color = tokens.text, fontSize = 16.sp),
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.onAddNew() }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "New category" },
                decorationBox = { inner ->
                    FieldBox {
                        if (picker.newName.isEmpty()) Text("For example “Snorkeling”", color = tokens.sub, fontSize = 16.sp)
                        inner()
                    }
                },
            )
            Button("Add", null, ButtonStyle.Primary, enabled = picker.canAdd, onClick = actions::onAddNew)
        }
        val error = picker.newError
        Text(
            error ?: "Becomes a folder name when the files are binned.",
            color = if (error != null) tokens.danger else tokens.sub,
            fontSize = 12.sp,
        )
    }
}

/** Remove from project (mockup state 4b): the file stays; the project forgets the item. */
@Composable
internal fun RemoveDialog(dialog: RemoveDialogUi, onCancel: () -> Unit, onRemove: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Dialog(onDismissRequest = onCancel) {
        CompositionLocalProvider(LocalHazeState provides null) {
            Box(Modifier.background(tokens.bg, RoundedCornerShape(28.dp))) {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), strong = true) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(dialog.title, Modifier.semantics { heading() }, color = tokens.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                        Text(dialog.body, color = tokens.sub, fontSize = 14.sp, lineHeight = 20.sp)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            Button("Cancel", null, ButtonStyle.Secondary, onClick = onCancel)
                            Button(dialog.removeLabel, MediaIcons.Remove, ButtonStyle.Danger, onClick = onRemove)
                        }
                    }
                }
            }
        }
    }
}
