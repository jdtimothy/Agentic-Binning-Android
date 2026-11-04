package com.agentic.binning.ui.media

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.db.ItemKind
import com.agentic.binning.projectmedia.MediaChip
import com.agentic.binning.projectmedia.MediaTone
import com.agentic.binning.projectmedia.StatusAction
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.glass.MockupScale
import com.agentic.binning.ui.theme.GlassTokens
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** The mockup's three backdrop blobs (teal, amber, green). */
private val BlobColors = listOf(Color(0xFF2F6E8F), Color(0xFFF2A65A), Color(0xFF3E8F6E))

/** Room under the grid for the dock, so the last row can scroll clear of it. */
private val GridBottomInset = 150.dp

// Mockup colours the shared GlassTokens don't carry.
internal val GlassTokens.isDark: Boolean get() = bg.luminance() < 0.5f
internal val GlassTokens.ok: Color get() = if (isDark) Color(0xFF4CD787) else Color(0xFF1E8E4E)
internal val GlassTokens.accentSoft: Color get() = accent.copy(alpha = if (isDark) 0.18f else 0.12f)
internal val GlassTokens.line: Color get() = if (isDark) Color(0x1FFFFFFF) else Color(0x1F141C2D)
internal val GlassTokens.chipStrong: Color get() = if (isDark) Color(0x1FFFFFFF) else Color(0xC7FFFFFF)
internal val GlassTokens.danger: Color get() = if (isDark) Color(0xFFFFB4A8) else Color(0xFFB3261E)
internal val GlassTokens.field: Color get() = if (isDark) Color(0x0FFFFFFF) else Color(0xB8FFFFFF)
internal val GlassTokens.warnSoft: Color get() = if (isDark) Color(0x29FFB547) else Color(0x1FA35F00)

/**
 * Project Media (plan 2b Task 11; mockup states 1, 2, 9 and 10 and the status line): header,
 * search, the categories row, the kind toggle and Filters, the result line, the tile grid and the
 * dock. The detail, category, Re-sort and failed-item sheets arrive with Tasks 12–16.
 */
@Composable
fun ProjectMediaScreen(
    vm: ProjectMediaViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val hazeState = remember { HazeState() }
    LaunchedEffect(vm) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    BackHandler { if (state.selection != null) vm.onExitSelect() else onBack() }

    val textStyle = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)
    CompositionLocalProvider(LocalHazeState provides hazeState, LocalTextStyle provides textStyle) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop(BlobColors, hazeState)
            MockupScale {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                        if (state.selection != null) SelectHeader(state, vm) else Header(state, onBack, onOpenSettings)
                        Spacer(Modifier.height(12.dp))
                        SearchField(state, vm)
                        if (state.empty) {
                            Spacer(Modifier.height(48.dp))
                            EmptyCard(onAddFolder, onPickFiles)
                        } else if (state.hasProject) {
                            Spacer(Modifier.height(10.dp))
                            CategoriesHead(state, vm)
                            Spacer(Modifier.height(4.dp))
                            ChipsRow(state, vm)
                            Spacer(Modifier.height(4.dp))
                            FiltersRow(state, vm)
                            Spacer(Modifier.height(4.dp))
                            ResultLine(state, vm)
                            Spacer(Modifier.height(8.dp))
                            Grid(state, vm, hazeState, Modifier.weight(1f))
                        }
                    }
                    val bottom = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    if (state.selection != null) SelectionBar(state.selection.orEmpty().isNotEmpty(), vm, bottom) else Dock(state, vm, onBack, bottom)
                }
            }
            state.filtersSheet?.let { FiltersSheetView(it, vm) }
            state.sheet?.let { DetailSheetHost(it, vm) }
            state.failed?.let { FailedSheetHost(it, vm) }
            state.move?.let { PickerSheet(it, vm.movePicker) }
            state.categoryEdit?.let { CategoryEditSheet(it, vm.categoryEdit) }
            state.resort?.let { ResortSheetHost(it, vm) }
            state.removeSelected?.let { RemoveDialog(it, onCancel = vm::onCancelBulkRemove, onRemove = vm::onConfirmBulkRemove) }
        }
    }
}

@Composable
private fun Header(state: MediaUiState, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val tokens = LocalGlassTokens.current
    HeaderBar {
        RoundIcon(MediaIcons.Back, "Back to Analyze", onBack)
        Column(Modifier.weight(1f)) {
            Text(state.projectName, Modifier.semantics { heading() }, color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Project media · ${items(state.itemCount)}", color = tokens.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIcon(MediaIcons.Settings, "Settings", onOpenSettings)
    }
}

@Composable
private fun SelectHeader(state: MediaUiState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    HeaderBar {
        RoundIcon(MediaIcons.Close, "Leave select mode", vm::onExitSelect)
        Column(Modifier.weight(1f)) {
            Text("${state.selection.orEmpty().size} selected", color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Tap tiles to add or remove them", color = tokens.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(tokens.chip)
                .clickable(role = Role.Button, onClick = vm::onToggleSelectAll)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (state.allVisibleSelected) "Clear" else "Select all", color = tokens.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HeaderBar(content: @Composable RowScope.() -> Unit) {
    GlassSurface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
        Row(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
internal fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 44, iconSize: Int = 20) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tokens.chip)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.text, modifier = Modifier.size(iconSize.dp))
    }
}

@Composable
private fun SearchField(state: MediaUiState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    val focus = LocalFocusManager.current
    val enabled = !state.empty && state.hasProject
    GlassSurface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).alpha(if (enabled) 1f else 0.55f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MediaIcons.Search, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) {
                    Text(
                        if (enabled) "Search “sea turtles on the sand”" else "Nothing to search yet",
                        color = tokens.sub,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = vm::onQuery,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(color = tokens.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search this project" },
                )
            }
            if (state.query.isNotEmpty()) RoundIcon(MediaIcons.Close, "Clear search", vm::onClearQuery, size = 40, iconSize = 16)
        }
    }
}

@Composable
private fun CategoriesHead(state: MediaUiState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier.padding(start = 24.dp, end = 16.dp).fillMaxWidth().height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "CATEGORIES · ${state.categoryCount}",
            Modifier.weight(1f),
            color = tokens.sub,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp,
        )
        // Re-sort is off, with the reason, while a run is in progress (Q12); otherwise it opens its sheet.
        MiniButton(MediaIcons.Shuffle, "Re-sort", dimmed = state.busyReason != null, onClick = vm::onResort)
        // Off, with the reason, while a run is in progress: the sorter matches categories by name.
        MiniButton(MediaIcons.Pencil, "Edit", dimmed = state.busyReason != null, onClick = vm::onOpenCategoryEdit)
    }
}

@Composable
private fun MiniButton(icon: ImageVector, label: String, dimmed: Boolean = false, height: Int = 30, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .height(height.dp)
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(CircleShape)
            .background(tokens.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(15.dp))
        Text(label, color = tokens.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChipsRow(state: MediaUiState, vm: ProjectMediaViewModel) {
    LazyRow(
        Modifier.fillMaxWidth().height(40.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(state.chips, key = { it.chip.toString() }) { chip -> CategoryChip(chip) { vm.onChip(chip.chip) } }
    }
}

@Composable
private fun CategoryChip(chip: ChipUi, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(17.dp)
    val (fill, border, content) = when {
        chip.selected && chip.attention -> Triple(tokens.warn, tokens.warn, Color(0xFF1E1300))
        chip.selected -> Triple(tokens.accent, tokens.accent, tokens.onAccent)
        chip.attention -> Triple(tokens.glass, tokens.warn, tokens.warn)
        else -> Triple(tokens.glass, tokens.border, tokens.text)
    }
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .semantics { selected = chip.selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            chip.chip == MediaChip.Waiting -> MediaIcons.Clock
            chip.chip == MediaChip.Failed -> MediaIcons.Alert
            chip.locked -> MediaIcons.Lock
            else -> null
        }
        if (icon != null) Icon(icon, contentDescription = if (chip.locked) "Yours" else null, tint = content, modifier = Modifier.size(if (chip.locked) 12.dp else 13.dp))
        Text(chip.label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(
            "${chip.count}",
            modifier = Modifier.alpha(0.8f),
            color = content,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
    }
}

/** Q3, option C: the All · Photos · Videos toggle, a chip per set filter, then Filters. */
@Composable
private fun FiltersRow(state: MediaUiState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    LazyRow(
        Modifier.fillMaxWidth().height(34.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "kind") {
            Row(Modifier.height(30.dp).clip(CircleShape).border(1.dp, tokens.line, CircleShape)) {
                for ((kind, label) in listOf(null to "All", ItemKind.Photo to "Photos", ItemKind.Video to "Videos")) {
                    val on = state.kind == kind
                    Box(
                        Modifier
                            .height(30.dp)
                            .background(if (on) tokens.accentSoft else Color.Transparent)
                            .semantics { selected = on }
                            .clickable(role = Role.Tab) { vm.onKind(kind) }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (on) tokens.accent else tokens.sub, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
        items(state.setFilters, key = { it.key.name }) { f ->
            Row(
                Modifier
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(tokens.accentSoft)
                    .border(1.dp, tokens.accent, CircleShape)
                    .padding(start = 12.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.label, color = tokens.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Clear ${f.label}" }
                        .clickable(role = Role.Button) { vm.onClearFilter(f.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MediaIcons.Close, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(13.dp))
                }
            }
        }
        item(key = "filters") {
            val set = state.setFilters.isNotEmpty()
            Row(
                Modifier
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(if (set) tokens.accentSoft else Color.Transparent)
                    .border(1.dp, if (set) tokens.accent else tokens.line, CircleShape)
                    .clickable(role = Role.Button, onClick = vm::openFilters)
                    .padding(start = 12.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", color = if (set) tokens.accent else tokens.sub, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Icon(MediaIcons.Chevron, contentDescription = null, tint = if (set) tokens.accent else tokens.sub, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ResultLine(state: MediaUiState, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier.padding(start = 24.dp, end = 16.dp).fillMaxWidth().height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.resultText, Modifier.weight(1f), color = tokens.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.selection == null) MiniButton(MediaIcons.Select, "Select", height = 28, onClick = vm::onStartSelect)
    }
}

@Composable
private fun Grid(state: MediaUiState, vm: ProjectMediaViewModel, hazeState: HazeState, modifier: Modifier) {
    val tokens = LocalGlassTokens.current
    val noMatch = state.noMatchText
    if (noMatch != null) {
        Text(
            noMatch,
            modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 48.dp),
            color = tokens.sub,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        return
    }
    val selecting = state.selection != null
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        // A Haze source above the backdrop, so the dock blurs the tiles that scroll under it.
        modifier = modifier.fillMaxWidth().hazeSource(hazeState, zIndex = 1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = GridBottomInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.tiles, key = { it.id }) { tile ->
            MediaTile(tile, selecting, onClick = { vm.onTileClick(tile.id) }, onLongClick = { vm.onTileLongPress(tile.id) })
        }
    }
}

@Composable
private fun Dock(state: MediaUiState, vm: ProjectMediaViewModel, onAnalyze: () -> Unit, modifier: Modifier) {
    val tokens = LocalGlassTokens.current
    val status = state.status
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp)) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.padding(horizontal = 10.dp).fillMaxWidth().heightIn(min = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(status.tone)
                Text(status.text, Modifier.weight(1f), color = tokens.text, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status.action == StatusAction.CategorizeNow) {
                    Text(
                        "Categorize now",
                        Modifier
                            .alpha(if (state.busyReason != null) 0.5f else 1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = vm::onCategorizeNow)
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        color = tokens.accent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(tokens.chipStrong)
                        .clickable(role = Role.Button, onClick = onAnalyze)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).background(tokens.ok, CircleShape))
                    Text("Analyze", color = tokens.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                val on = status.binFilesEnabled
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(if (on) tokens.accent else tokens.chip)
                        .semantics { if (!on) contentDescription = "Bin Files. ${state.binReason ?: ""}" }
                        .clickable(role = Role.Button, onClick = vm::onBinFiles),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val content = if (on) tokens.onAccent else tokens.sub
                    Icon(MediaIcons.FolderCheck, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                    Text("Bin Files", color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (on) {
                        Text(
                            "${status.readyToBin}",
                            Modifier.background(Color.Black.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                            color = content,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(tone: MediaTone) {
    val tokens = LocalGlassTokens.current
    val color = when (tone) {
        MediaTone.Running -> tokens.accent
        MediaTone.Attention -> tokens.warn
        MediaTone.Ready -> tokens.ok
        MediaTone.Quiet -> tokens.sub
    }
    val alpha = if (tone == MediaTone.Running) {
        val pulse = rememberInfiniteTransition(label = "status pulse")
        pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "status alpha").value
    } else {
        1f
    }
    Box(Modifier.size(8.dp).alpha(alpha).background(color, CircleShape))
}

/** Mockup state 10: a project with nothing in it; the buttons are the project sheet's pickers. */
@Composable
private fun EmptyCard(onAddFolder: () -> Unit, onPickFiles: () -> Unit) {
    val tokens = LocalGlassTokens.current
    GlassSurface(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), shape = RoundedCornerShape(30.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).background(tokens.accentSoft, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                Icon(MediaIcons.Photos, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(30.dp))
            }
            Text("No photos or clips yet", Modifier.padding(top = 6.dp).semantics { heading() }, color = tokens.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Add media and the phone describes each item, then sorts everything into categories. Files stay where they are until you press Bin Files.",
                color = tokens.sub,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            WideButton(MediaIcons.Folder, "Add a folder", primary = true, onClick = onAddFolder)
            WideButton(MediaIcons.Plus, "Pick photos and videos", primary = false, onClick = onPickFiles)
        }
    }
}

@Composable
private fun WideButton(icon: ImageVector, label: String, primary: Boolean, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val content = if (primary) tokens.onAccent else tokens.text
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(if (primary) tokens.accent else tokens.chipStrong)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The Filters sheet (Q3, option C): Shot type and Date, each choice with its count, then Show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheetView(sheet: FiltersSheet, vm: ProjectMediaViewModel) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ModalBottomSheet(
        onDismissRequest = vm::closeFilters,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        // The sheet has its own window, out of the backdrop's reach: an opaque base under strong glass.
        CompositionLocalProvider(LocalHazeState provides null) {
            Box(Modifier.statusBarsPadding().padding(top = 8.dp).background(tokens.bg, shape)) {
                GlassSurface(Modifier.fillMaxWidth(), shape = shape, strong = true) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    ) {
                        Box(Modifier.padding(top = 10.dp, bottom = 8.dp).align(Alignment.CenterHorizontally).size(40.dp, 5.dp).background(tokens.track, CircleShape))
                        Text("Filters", Modifier.semantics { heading() }, color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        GroupLabel("Shot type")
                        sheet.shots.forEach { o -> OptionRow(o.label, o.count, o.selected) { vm.onDraftShot(o.value) } }
                        GroupLabel("Date")
                        sheet.days.forEach { o -> OptionRow(o.label, o.count, o.selected) { vm.onDraftDay(o.value) } }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(tokens.accent)
                                .clickable(role = Role.Button, onClick = vm::applyFilters),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Show ${items(sheet.showCount)}", color = tokens.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    val tokens = LocalGlassTokens.current
    Text(
        text.uppercase(),
        Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() },
        color = tokens.sub,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
    )
}

@Composable
private fun OptionRow(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) tokens.chip else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .border(2.dp, if (selected) tokens.accent else tokens.line, CircleShape)
                .padding(5.dp)
                .background(if (selected) tokens.accent else Color.Transparent, CircleShape),
        )
        Text(label, Modifier.weight(1f), color = tokens.text, fontSize = 15.sp)
        Text("$count", color = tokens.sub, fontSize = 13.sp)
    }
}

private fun items(n: Int) = if (n == 1) "1 item" else "$n items"
