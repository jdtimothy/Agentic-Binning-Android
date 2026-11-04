package com.agentic.binning.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.move.MovedFile
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.GlassTokens
import com.agentic.binning.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch

// The owner's glass for Bin Files (mockup review, Nov 3): in dark mode a sheet is the lighter glass and
// the cards inside it the darker. Light mode keeps the regular glass and chips.
internal val GlassTokens.sheetGlass: Color get() = if (isDark) Color(0x8C484E60) else glass
internal val GlassTokens.well: Color get() = if (isDark) Color(0x750A0C12) else chip
internal val GlassTokens.wellWarn: Color get() = if (isDark) Color(0x9E34240C) else warnSoft
internal val GlassTokens.binScrim: Color get() = if (isDark) Color(0x80000000) else Color(0x57141A28)

private val SheetTop = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

/** How far a sheet is dragged by its handle before letting go closes it. */
private val DismissDrag = 120.dp

/**
 * The Bin Files sheets (mockup states 1, 3, 4 and 5). Unlike Plan 2b's sheets they are drawn in the
 * screen's own window, not a dialog's, so the glass really blurs the screen behind them. Call inside
 * the screen's root [Box], after the content they cover.
 */
@Composable
internal fun BoxScope.BinSheetHost(sheet: BinSheetUi?, vm: BinFilesViewModel, onOpenItem: (Long) -> Unit) {
    val tokens = LocalGlassTokens.current
    var shown by remember { mutableStateOf(sheet) }
    if (sheet != null) shown = sheet
    val visible = sheet != null
    BackHandler(visible) { vm.onCloseSheet() }

    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.binScrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = vm::onCloseSheet),
        )
    }
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        when (val s = shown) {
            is BinSheetUi.Confirm -> ConfirmSheet(s.ui, vm, onOpenItem)
            is BinSheetUi.Report -> ReportSheet(s.ui, vm, onOpenItem)
            is BinSheetUi.Unknown -> UnknownSheet(s.ui, vm)
            null -> Unit
        }
    }
}

@Composable
private fun BinSheet(title: String, onClose: () -> Unit, footer: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val dismissAt = with(LocalDensity.current) { DismissDrag.toPx() }
    Box(Modifier.offset { IntOffset(0, drag.value.toInt()) }.fillMaxHeight(0.92f).statusBarsPadding()) {
        GlassSurface(Modifier.fillMaxSize(), shape = SheetTop, fill = tokens.sheetGlass) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.draggable(
                        rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = {
                            if (drag.value > dismissAt) onClose()
                            scope.launch { drag.animateTo(0f) }
                        },
                    ),
                ) {
                    Box(Modifier.padding(top = 10.dp, bottom = 8.dp).align(Alignment.CenterHorizontally).size(40.dp, 5.dp).background(tokens.track, CircleShape))
                    SheetHead(title, onClose)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = body,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
                Column(
                    Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = footer,
                )
            }
        }
    }
}

@Composable
private fun Big(text: String, sub: String?) {
    val tokens = LocalGlassTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, Modifier.semantics { heading() }, color = tokens.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
        if (sub != null) Text(sub, color = tokens.sub, fontSize = 13.5.sp, lineHeight = 18.sp)
    }
}

private fun iconOf(icon: FactIcon): ImageVector = when (icon) {
    FactIcon.Photos -> MediaIcons.Photos
    FactIcon.Disk -> MediaIcons.Disk
    FactIcon.Copy -> MediaIcons.Copy
    FactIcon.FolderCheck, FactIcon.InPlace -> MediaIcons.FolderCheck
    FactIcon.FolderMove -> MediaIcons.FolderMove
    FactIcon.Tag -> MediaIcons.Tag
    FactIcon.Trash -> MediaIcons.Trash
    FactIcon.Clock -> MediaIcons.Clock
    FactIcon.Check -> MediaIcons.Check
}

@Composable
private fun Facts(facts: List<FactUi>) {
    val tokens = LocalGlassTokens.current
    if (facts.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.well)) {
        facts.forEachIndexed { i, fact ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = when (fact.tone) {
                    FactTone.Good -> tokens.ok
                    FactTone.Warn -> tokens.warn
                    FactTone.Normal -> tokens.accent
                }
                Icon(iconOf(fact.icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(fact.text, color = tokens.text, fontSize = 13.5.sp)
                    if (fact.sub != null) Text(fact.sub, color = tokens.sub, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (fact.value != null) {
                    Text(fact.value, color = tokens.sub, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

@Composable
private fun BinCallout(callout: CalloutUi, icon: ImageVector) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (callout.warn) tokens.wellWarn else tokens.well, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (callout.warn) tokens.warn else tokens.accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(
            buildAnnotatedString {
                callout.bold?.let {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it) }
                    append(" ")
                }
                append(callout.text)
            },
            color = tokens.text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

/** "Left where they are · 3" or "Couldn't move · 1": each row opens its item's sheet. */
@Composable
private fun LeftList(title: String, rows: List<LeftRowUi>, onOpenItem: (Long) -> Unit) {
    val tokens = LocalGlassTokens.current
    if (rows.isEmpty()) return
    Section("$title · ${rows.size}") {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.well)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenItem(row.itemId) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val info = row.why == "Skipped"
                    Box(Modifier.size(40.dp).background(tokens.chip, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (row.why == "Failed") MediaIcons.Alert else MediaIcons.Photos, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(row.name, color = tokens.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.sub, color = tokens.sub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = if (info) 3 else 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        row.why,
                        Modifier
                            .background(if (info) tokens.accentSoft else tokens.warnSoft, RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        color = if (info) tokens.accent else tokens.warn,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** State 1: what Bin Files is about to do, and that it can't be undone (B7). */
@Composable
private fun ConfirmSheet(ui: ConfirmUi, vm: BinFilesViewModel, onOpenItem: (Long) -> Unit) {
    BinSheet(
        title = "Bin Files",
        onClose = vm::onCloseSheet,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Cancel", null, ButtonStyle.Secondary, onClick = vm::onCloseSheet)
                Button(ui.startLabel, MediaIcons.FolderCheck, ButtonStyle.Primary, Modifier.weight(1f), onClick = vm::onConfirm)
            }
        },
    ) {
        Big(ui.headline, ui.intro)
        Facts(ui.facts)
        ui.callouts.forEachIndexed { i, c -> BinCallout(c, if (i == 0) MediaIcons.Shield else MediaIcons.Info) }
        LeftList("Left where they are", ui.left, onOpenItem)
    }
}

/** States 3 and 4: what a run did, or why it stopped (B8). */
@Composable
private fun ReportSheet(ui: ReportUi, vm: BinFilesViewModel, onOpenItem: (Long) -> Unit) {
    BinSheet(
        title = ui.title,
        onClose = vm::onCloseSheet,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.secondary != null) Button(ui.secondary, null, ButtonStyle.Secondary, onClick = vm::onCloseSheet)
                Button(
                    ui.primary,
                    when {
                        !ui.primaryStartsARun -> null
                        ui.primary == "Try again" -> MediaIcons.Retry
                        else -> MediaIcons.FolderCheck
                    },
                    ButtonStyle.Primary,
                    Modifier.weight(1f),
                    onClick = vm::onReportPrimary,
                )
            }
        },
    ) {
        ui.headline?.let { Big(it, ui.sub) }
        ui.callout?.let { BinCallout(it, MediaIcons.Stop) }
        Facts(ui.facts)
        LeftList("Couldn't move", ui.skipped, onOpenItem)
        LeftList("Left where they are", ui.left, onOpenItem)
    }
}

@Composable
private fun Paths(title: String, rows: List<UnknownRowUi>) {
    val tokens = LocalGlassTokens.current
    if (rows.isEmpty()) return
    Section("$title · ${rows.size}") {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.well)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(row.path, color = tokens.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(row.sub, color = tokens.sub, fontSize = 11.5.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

/** State 5: files in category folders no item points to block the run (B5). */
@Composable
private fun UnknownSheet(ui: UnknownUi, vm: BinFilesViewModel) {
    BinSheet(
        title = "Files Bin Files doesn't know",
        onClose = vm::onCloseSheet,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Leave them", null, ButtonStyle.Secondary, onClick = vm::onLeaveFiles)
                Button("Add to project", MediaIcons.Plus, ButtonStyle.Primary, Modifier.weight(1f), onClick = vm::onAddFiles)
            }
        },
    ) {
        BinCallout(ui.callout, MediaIcons.Alert)
        Paths("Not in the project", ui.unknown)
        Paths("Marked missing", ui.missing)
        val tokens = LocalGlassTokens.current
        Row(
            Modifier.fillMaxWidth().background(tokens.well, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(MediaIcons.Info, contentDescription = null, tint = tokens.accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Add to project") }
                    append(" analyzes and sorts them, then the next Bin Files moves them into their own category. ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Leave them") }
                    append(" keeps them where they are and the check skips them from now on.")
                },
                color = tokens.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/**
 * States 2 and 6: the progress card above the dock. It stays where it is; only its text and bar change,
 * and the flow behind it is paced so a file name stays up long enough to read.
 */
@Composable
internal fun MoveCard(card: MoveCardUi, vm: BinFilesViewModel, modifier: Modifier = Modifier) {
    val tokens = LocalGlassTokens.current
    val fraction by animateFloatAsState(card.fraction, tween(300), label = "move progress")
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.title, Modifier.weight(1f).semantics { heading() }, color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(card.count, color = tokens.sub, style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"))
            }
            card.banner?.let {
                Row(
                    Modifier.fillMaxWidth().background(tokens.accentSoft, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(MediaIcons.Retry, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(14.dp))
                    Text(it, color = tokens.accent, fontSize = 12.5.sp, lineHeight = 16.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(tokens.track)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(tokens.accent))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.file, color = tokens.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.fileSub, color = tokens.sub, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (card.recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { card.recent.forEach { RecentRow(it) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(MediaIcons.Clock, contentDescription = null, tint = tokens.sub, modifier = Modifier.padding(top = 1.dp).size(14.dp))
                    Text(card.note, color = tokens.sub, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (card.paused) {
                    CardButton("Resume", MediaIcons.Play, tokens.accent, enabled = true, onClick = vm::onResume)
                } else {
                    CardButton("Pause", MediaIcons.Pause, tokens.accent, enabled = card.canPause, onClick = vm::onPause)
                }
                CardButton("Stop", MediaIcons.Stop, tokens.danger, enabled = card.canStop, onClick = vm::onStop)
            }
        }
    }
}

@Composable
private fun RecentRow(file: MovedFile) {
    val tokens = LocalGlassTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(20.dp).background(tokens.ok, CircleShape), contentAlignment = Alignment.Center) {
            Icon(MediaIcons.Check, contentDescription = null, tint = tokens.bg, modifier = Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(file.name, color = tokens.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.folder, color = tokens.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Done", color = tokens.sub, fontSize = 11.sp)
    }
}

@Composable
private fun CardButton(label: String, icon: ImageVector, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .background(tokens.chip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
