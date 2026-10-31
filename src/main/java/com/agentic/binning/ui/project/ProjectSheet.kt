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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch

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
)

/** What the sheet reports; the host launches the system pickers and decides where their result goes. */
interface ProjectSheetActions {
    /** "Add a folder" on the main page, or the folder tile / "Change" on the New project page. */
    fun onPickFolder()

    /** "Pick photos and clips" on the main page, or the photos tile / "Change" on the New project page. */
    fun onPickFiles()

    fun onSwitch(ref: ProjectRef)

    fun onNewProject()

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSheet(state: ProjectSheetState, actions: ProjectSheetActions) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val onNewProjectPage = state.page == SheetPage.NewProject

    /**
     * Back on the New project page: to the main page, or closes the sheet when there is no project.
     * Ignored while the project is being created.
     */
    val back: () -> Unit = back@{
        if (state.creating) return@back
        if (state.current == null) {
            scope.launch { sheetState.hide() }.invokeOnCompletion { actions.onDismiss() }
        } else {
            actions.onBack()
        }
    }

    ModalBottomSheet(
        onDismissRequest = actions::onDismiss,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        // On the New project page, Back goes to the main page instead of closing: this handler takes
        // it before the sheet does. (ModalBottomSheetProperties is read only when the sheet window is
        // created, so switching shouldDismissOnBackPress per page left Back dead on the main page.)
        BackHandler(enabled = onNewProjectPage, onBack = back)
        // The sheet lives in its own window, so the screen's backdrop blur cannot reach it:
        // an opaque base under a strong glass fill instead.
        CompositionLocalProvider(LocalHazeState provides null) {
            // A full-height sheet would otherwise run under the status bar.
            Box(Modifier.statusBarsPadding().padding(top = 8.dp).background(tokens.bg, shape)) {
                GlassSurface(Modifier.fillMaxWidth(), shape = shape, strong = true) {
                    AnimatedContent(
                        targetState = state.page,
                        transitionSpec = { fadeIn(tween(PAGE_FADE_MS)) togetherWith fadeOut(tween(PAGE_FADE_MS)) },
                        label = "sheet page",
                    ) { page ->
                        Column(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 20.dp, vertical = 20.dp),
                        ) {
                            when (page) {
                                SheetPage.Main -> MainPage(state, actions)
                                SheetPage.NewProject -> NewProjectPage(state, actions, back)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MainPage(state: ProjectSheetState, actions: ProjectSheetActions) {
    val tokens = LocalGlassTokens.current
    val canAddMedia = state.current != null && state.registration == null && !state.creating

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
    }

    PillButton("Add a folder", ProjectIcons.FolderPlus, enabled = canAddMedia, primary = true, onClick = actions::onPickFolder)
    Spacer(Modifier.height(10.dp))
    PillButton("Pick photos and clips", ProjectIcons.PhotoPlus, enabled = canAddMedia, onClick = actions::onPickFiles)
    state.registration?.let {
        Spacer(Modifier.height(10.dp))
        Text(registrationProgressText(it), color = tokens.text, fontSize = 14.sp)
    }
    state.registrationSummary?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = tokens.sub, fontSize = 14.sp)
    }

    Spacer(Modifier.height(28.dp))
    Text("Projects", color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    NewProjectChip(Modifier.align(Alignment.CenterHorizontally), actions::onNewProject)
    val others = otherProjects(state.projects, state.current)
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            others.forEach { ref -> ProjectRow(ref) { actions.onSwitch(ref) } }
        }
    }
    state.switchError?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = tokens.warn, fontSize = 13.sp)
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
            "Photos and clips",
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
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val content = if (primary) tokens.onAccent else tokens.text
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
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
        Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Compact, content-sized pill; the touch target is still 48 dp tall. */
@Composable
private fun NewProjectChip(modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(tokens.tagBg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ProjectIcons.Plus, contentDescription = null, tint = tokens.tagText, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("New project", color = tokens.tagText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProjectRow(ref: ProjectRef, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ref.name,
            modifier = Modifier.weight(1f),
            color = tokens.text,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Icon(ProjectIcons.ChevronRight, contentDescription = null, tint = tokens.sub, modifier = Modifier.size(18.dp))
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
            .background(if (selected) tokens.tagBg else tokens.chip)
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
