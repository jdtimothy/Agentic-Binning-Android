package com.agentic.binning.ui.project

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.db.ItemTotals
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.registrationProgressText
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.ui.media.GlassDialog
import com.agentic.binning.ui.media.GlassSheet
import com.agentic.binning.ui.media.well
import com.agentic.binning.ui.theme.LocalGlassTokens
import kotlinx.coroutines.delay

/** What the sheet shows. */
data class ProjectSheetState(
    val page: SheetPage,
    val current: ProjectRef?,
    /** The current project's item counts, once read. */
    val counts: ItemTotals?,
    /** Every known project; the main page lists all but [current]. */
    val projects: List<ProjectRef>,
    /** The New project page's name field. */
    val name: String,
    /** Media held on the New project page until the project is created. */
    val selection: MediaChoice?,
    val createError: String?,
    /** Shown under the project list when switching fails. */
    val switchError: String? = null,
    /** Set while media is being added; the add buttons are disabled meanwhile. */
    val registration: RegistrationProgress? = null,
    /** One-line result of the last add, e.g. "12 added · 3 already in this project". */
    val registrationSummary: String? = null,
    /** Set while a project is being created. */
    val creating: Boolean = false,
    /** Current projects is in Edit mode: each row ends in × (plan 2b Task 6A). */
    val editing: Boolean = false,
    /** Paths of listed projects whose folder is gone. */
    val missing: Set<String> = emptySet(),
    /** Set while asking "Stop analysis and remove …?". */
    val confirmRemove: ProjectRef? = null,
    /** "Removed <name>", offered with Undo; [undoId] tells one removal from the next. */
    val undoText: String? = null,
    val undoId: Long = 0L,
)

/** What the sheet reports; the host launches the system pickers and decides where their result goes. */
interface ProjectSheetActions {
    /** "Add a folder" on the main page, or the folder tile / "Change" on the New project page. */
    fun onPickFolder()

    /** "Pick photos and videos" on the main page, or the photos tile / "Change" on the New project page. */
    fun onPickFiles()

    fun onSwitch(ref: ProjectRef)

    fun onNewProject()

    /** Open project: the system folder picker, then the folder is checked and opened. */
    fun onOpenProject()

    /** Edit / Done on Current projects. */
    fun onToggleEdit()

    /** × on a row: takes the project off the list (never deletes anything). */
    fun onRemove(ref: ProjectRef)

    fun onConfirmRemove()

    fun onCancelRemove()

    fun onUndo()

    /** The Undo toast's time is up. */
    fun onUndoExpired(id: Long)

    /** Back from the New project page to the main page. */
    fun onBack()

    /** Edits to the project name; the host also clears a stale create error. */
    fun onNameChange(name: String)

    fun onCreate()

    fun onDismiss()
}

private const val PAGE_FADE_MS = 200

/**
 * Glass bottom sheet with two pages: the main page adds media to the current project and switches
 * projects; the New project page creates one, optionally with media to analyze straight away.
 */
@Composable
fun ProjectSheet(state: ProjectSheetState, actions: ProjectSheetActions) {
    val tokens = LocalGlassTokens.current
    val onNewProjectPage = state.page == SheetPage.NewProject

    /**
     * Back on the New project page: to the main page, which always exists now (Current projects with
     * Open project and New project, plan 2b P7d). Ignored while the project is being created.
     */
    val back: () -> Unit = back@{
        if (state.creating) return@back
        actions.onBack()
    }

    GlassSheet(onDismiss = actions::onDismiss, tall = false) {
        // On the New project page, Back goes to the main page instead of closing: this handler takes it
        // before the sheet's, being composed later.
        BackHandler(enabled = onNewProjectPage, onBack = back)
        Box {
            AnimatedContent(
                targetState = state.page,
                transitionSpec = { fadeIn(tween(PAGE_FADE_MS)) togetherWith fadeOut(tween(PAGE_FADE_MS)) },
                label = "sheet page",
            ) { page ->
                val undoInset = sheetBottomInsetForUndo(
                    showingUndo = state.undoText != null && page == SheetPage.Main,
                )
                Column(
                    Modifier
                        // Inset outside the scroll so the last × cannot sit under the toast.
                        .padding(bottom = undoInset.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                ) {
                    when (page) {
                        SheetPage.Main -> MainPage(state, actions)
                        SheetPage.NewProject -> NewProjectPage(state, actions, back)
                    }
                }
            }
            state.undoText?.let { text ->
                UndoToast(text, Modifier.align(Alignment.BottomCenter), actions::onUndo)
                LaunchedEffect(state.undoId) {
                    delay(UNDO_MILLIS)
                    actions.onUndoExpired(state.undoId)
                }
            }
        }
    }
    state.confirmRemove?.let { ref ->
        GlassDialog(onDismiss = actions::onCancelRemove) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(removeConfirmTitle(ref.name), color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    DialogButton("Cancel", tokens.accent, actions::onCancelRemove)
                    DialogButton("Remove", tokens.warn, actions::onConfirmRemove)
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "Removed <name>" with Undo, shown for [UNDO_MILLIS] after × (plan 2b P7b). */
@Composable
private fun UndoToast(text: String, modifier: Modifier, onUndo: () -> Unit) {
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF00E1016))
            // Swallow taps on the bar so they cannot hit an × behind it.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color(0xFFF4F6FA), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onUndo)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Undo", color = Color(0xFF8CC6FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ColumnScope.MainPage(state: ProjectSheetState, actions: ProjectSheetActions) {
    val tokens = LocalGlassTokens.current
    val busy = state.registration != null || state.creating
    val canAddMedia = state.current != null && !busy

    state.current?.let { current ->
        Text("Current project", color = tokens.sub, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            current.name,
            color = tokens.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        state.counts?.let {
            Spacer(Modifier.height(2.dp))
            Text(countsLine(it.total, it.waiting), color = tokens.sub, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        PillButton("Add a folder", ProjectIcons.FolderPlus, enabled = canAddMedia, primary = true, onClick = actions::onPickFolder)
        Spacer(Modifier.height(10.dp))
        PillButton("Pick photos and videos", ProjectIcons.PhotoPlus, enabled = canAddMedia, onClick = actions::onPickFiles)
        state.registration?.let {
            Spacer(Modifier.height(10.dp))
            Text(registrationProgressText(it), color = tokens.text, fontSize = 14.sp)
        }
        state.registrationSummary?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = tokens.sub, fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
    }

    val section = currentProjectsUi(
        projects = state.projects,
        current = state.current,
        openSubline = state.counts?.let { countsLine(it.total, it.waiting) },
        missing = state.missing,
        editing = state.editing,
        busy = busy,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Current projects", color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        section.editLabel?.let { label ->
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .alpha(if (section.editEnabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .clickable(enabled = section.editEnabled, role = Role.Button, onClick = actions::onToggleEdit)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = tokens.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (section.showButtons) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                "Open project",
                ProjectIcons.FolderOpen,
                enabled = !state.creating,
                compact = true,
                modifier = Modifier.weight(1f),
                onClick = actions::onOpenProject,
            )
            PillButton(
                "New project",
                ProjectIcons.Plus,
                enabled = !state.creating,
                compact = true,
                modifier = Modifier.weight(1f),
                onClick = actions::onNewProject,
            )
        }
    }
    state.switchError?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = tokens.warn, fontSize = 13.sp)
    }
    section.emptyText?.let {
        Text(
            it,
            color = tokens.sub,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 22.dp, bottom = 6.dp),
        )
    }
    if (section.rows.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section.rows.forEach { row ->
                ProjectRow(row, onSwitch = { actions.onSwitch(row.ref) }, onRemove = { actions.onRemove(row.ref) })
            }
        }
    }
}

@Composable
private fun NewProjectPage(state: ProjectSheetState, actions: ProjectSheetActions, onBack: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val canCreate = state.name.isNotBlank() && !state.creating

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 4.dp)
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .alpha(if (state.creating) 0.4f else 1f)
                .clip(CircleShape)
                .clickable(enabled = !state.creating, role = Role.Button, onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ProjectIcons.ArrowLeft, contentDescription = "Back", tint = tokens.text, modifier = Modifier.size(22.dp))
        }
        Text("New project", color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }

    Spacer(Modifier.height(16.dp))
    FieldLabel("Name")
    OutlinedTextField(
        value = state.name,
        onValueChange = actions::onNameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Project name", color = tokens.sub) },
        isError = state.createError != null,
        supportingText = state.createError?.let { error ->
            { Text(error, color = tokens.warn) }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canCreate) actions.onCreate() }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = tokens.text,
            unfocusedTextColor = tokens.text,
            cursorColor = tokens.accent,
            focusedBorderColor = tokens.accent,
            unfocusedBorderColor = sheetOutline(tokens),
            errorBorderColor = tokens.warn,
            errorTextColor = tokens.text,
            errorCursorColor = tokens.warn,
        ),
    )

    Spacer(Modifier.height(16.dp))
    FieldLabel("Media")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MediaTile(
            "A folder",
            ProjectIcons.Folder,
            selected = state.selection is MediaChoice.Folder,
            enabled = !state.creating,
            modifier = Modifier.weight(1f),
            onClick = actions::onPickFolder,
        )
        MediaTile(
            "Photos and videos",
            ProjectIcons.Photo,
            selected = state.selection is MediaChoice.Files,
            enabled = !state.creating,
            modifier = Modifier.weight(1f),
            onClick = actions::onPickFiles,
        )
    }
    Spacer(Modifier.height(8.dp))
    val selection = state.selection
    if (selection == null) {
        Text(
            "Optional. You can add media later.",
            color = tokens.sub,
            fontSize = 13.sp,
            modifier = Modifier.heightIn(min = 48.dp).padding(top = 14.dp),
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selectionLabel(selection),
                color = tokens.text,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .alpha(if (state.creating) 0.4f else 1f)
                    .clip(CircleShape)
                    .clickable(enabled = !state.creating, role = Role.Button) {
                        if (selection is MediaChoice.Folder) actions.onPickFolder() else actions.onPickFiles()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Change", color = tokens.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    PillButton(createButtonLabel(selection), icon = null, enabled = canCreate, primary = true, onClick = actions::onCreate)
}

@Composable
private fun FieldLabel(text: String) {
    val tokens = LocalGlassTokens.current
    Text(text, color = tokens.sub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector?,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val content = if (primary) tokens.onAccent else tokens.text
    Row(
        modifier
            .heightIn(min = if (compact) 48.dp else 50.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (primary) tokens.accent else tokens.chip)
            .border(1.dp, if (primary) Color.Transparent else sheetOutline(tokens), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = content, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ProjectRow(row: ProjectRowUi, onSwitch: () -> Unit, onRemove: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(tokens.well)
            .border(1.dp, if (row.isOpen) tokens.accent else Color.Transparent, shape)
            .clickable(enabled = row.switches, role = Role.Button, onClick = onSwitch)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.ref.name, color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.subline?.let { Text(it, color = if (row.warn) tokens.warn else tokens.sub, fontSize = 12.sp, maxLines = 1) }
        }
        if (row.isOpen) {
            Text(
                "OPEN",
                color = tokens.tagText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp).clip(RoundedCornerShape(8.dp)).background(tokens.tagBg).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        when (row.end) {
            RowEnd.Chevron -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(ProjectIcons.ChevronRight, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(18.dp))
            }
            RowEnd.Remove -> Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "Remove ${row.ref.name} from the list" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(tokens.chip).border(1.dp, sheetOutline(tokens), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ProjectIcons.Close, contentDescription = null, tint = tokens.text, modifier = Modifier.size(14.dp))
                }
            }
            RowEnd.None -> Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun MediaTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 88.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) tokens.tagBg else tokens.well)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) tokens.accent else sheetOutline(tokens), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) tokens.accent else tokens.text, modifier = Modifier.size(26.dp))
        Text(label, color = tokens.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
