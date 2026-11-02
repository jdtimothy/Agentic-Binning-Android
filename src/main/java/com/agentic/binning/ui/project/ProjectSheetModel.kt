package com.agentic.binning.ui.project

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import com.agentic.binning.ui.theme.GlassTokens
import java.text.NumberFormat
import java.util.Locale

/** The project sheet's two pages. */
enum class SheetPage { Main, NewProject }

/** Media picked on the New project page, held until the project is created. */
sealed interface MediaChoice {
    /** A folder; [path] is shown as picked, relative to shared storage (see [sharedStoragePath]). */
    data class Folder(val path: String) : MediaChoice

    data class Files(val count: Int) : MediaChoice
}

/** Where a system picker's result goes, recorded when the picker is launched (it survives activity recreation). */
enum class PickFor { Current, NewProject, OpenProject }

enum class PickRoute { Ignore, AddToCurrent, HoldForNewProject, OpenProject }

/**
 * What to do with a picker result. Without a recorded [target] the result is dropped: it must never
 * fall back to adding media to whichever project happens to be current.
 */
fun routePick(target: PickFor?, hasResult: Boolean): PickRoute = when {
    !hasResult || target == null -> PickRoute.Ignore
    target == PickFor.Current -> PickRoute.AddToCurrent
    target == PickFor.OpenProject -> PickRoute.OpenProject
    else -> PickRoute.HoldForNewProject
}

/**
 * Outline for the sheet's field, tiles and secondary button: the border token is near-white in the
 * light theme, so a light sheet uses a muted text colour instead (3:1 against the sheet).
 */
fun sheetOutline(tokens: GlassTokens): Color =
    if (tokens.bg.luminance() > 0.5f) tokens.sub.copy(alpha = 0.7f) else tokens.border

/**
 * The sheet always opens on its main page: with no project it shows Current projects with Open
 * project and New project, so Open project is in view after a reinstall (plan 2b P7d).
 */
@Suppress("UNUSED_PARAMETER")
fun startPage(current: ProjectRef?): SheetPage = SheetPage.Main

/** "136 items · 31 left"; just "136 items" when nothing is left. [left] is Pending plus Analyzing. */
fun countsLine(total: Int, left: Int, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getIntegerInstance(locale)
    val items = "${format.format(total)} ${if (total == 1) "item" else "items"}"
    return if (left > 0) "$items · ${format.format(left)} left" else items
}

/** Every project except [current] (matched by path), in registry order. */
fun otherProjects(projects: List<ProjectRef>, current: ProjectRef?): List<ProjectRef> =
    projects.filter { it.path != current?.path }

fun createButtonLabel(choice: MediaChoice?): String = if (choice == null) "Create project" else "Create and analyze"

fun selectionLabel(choice: MediaChoice, locale: Locale = Locale.getDefault()): String = when (choice) {
    is MediaChoice.Folder -> choice.path
    is MediaChoice.Files ->
        if (choice.count == 1) "1 photo or clip" else "${NumberFormat.getIntegerInstance(locale).format(choice.count)} photos and clips"
}

private const val INTERNAL_STORAGE = "/storage/emulated/0"
private const val STORAGE = "/storage"

/**
 * A shared-storage path as people know it: "DCIM/Beach" for `/storage/emulated/0/DCIM/Beach`,
 * "Internal storage" for its root, and "<volume>/…" for other volumes such as an SD card.
 */
fun sharedStoragePath(absolutePath: String): String {
    val path = absolutePath.trimEnd('/')
    return when {
        path == INTERNAL_STORAGE -> "Internal storage"
        path.startsWith("$INTERNAL_STORAGE/") -> path.removePrefix("$INTERNAL_STORAGE/")
        path.startsWith("$STORAGE/") -> path.removePrefix("$STORAGE/")
        else -> path
    }
}

/** What ends a Current projects row. */
enum class RowEnd { Chevron, None, Remove }

data class ProjectRowUi(
    val ref: ProjectRef,
    /** The open project: listed first, with an "Open" pill. */
    val isOpen: Boolean,
    val subline: String?,
    /** [subline] is a warning ("Folder missing"). */
    val warn: Boolean,
    val end: RowEnd,
    /** Tapping the row switches to it. */
    val switches: Boolean,
)

/** The sheet's Current projects section (plan 2b Task 6A, mockup `2026-11-01-plan-2b-current-projects.html`). */
data class CurrentProjectsUi(
    val rows: List<ProjectRowUi>,
    /** Open project and New project; hidden while editing. */
    val showButtons: Boolean,
    /** "Edit", "Done", or null when the list is empty. */
    val editLabel: String?,
    val editEnabled: Boolean,
    /** "No projects yet" for an empty list. */
    val emptyText: String?,
)

const val FOLDER_MISSING = "Folder missing"
const val NO_PROJECTS = "No projects yet"

/**
 * Builds Current projects: every listed project, the open one first. [openSubline] is the open
 * project's counts line. [busy] (media being added, or a project being created) disables Edit.
 * While [editing], each row ends in × and nothing switches.
 */
fun currentProjectsUi(
    projects: List<ProjectRef>,
    current: ProjectRef?,
    openSubline: String?,
    missing: Set<String>,
    editing: Boolean,
    busy: Boolean,
): CurrentProjectsUi {
    val open = current?.let { c -> projects.firstOrNull { ProjectRegistry.samePath(it.path, c.path) } }
    val ordered = listOfNotNull(open) + projects.filter { it !== open }
    val editingNow = editing && ordered.isNotEmpty()
    val rows = ordered.map { ref ->
        val isOpen = ref === open
        val isMissing = ref.path in missing
        ProjectRowUi(
            ref = ref,
            isOpen = isOpen,
            subline = when {
                isMissing -> FOLDER_MISSING
                isOpen -> openSubline
                else -> null
            },
            warn = isMissing,
            end = when {
                editingNow -> RowEnd.Remove
                isOpen -> RowEnd.None
                else -> RowEnd.Chevron
            },
            switches = !editingNow && !isOpen,
        )
    }
    return CurrentProjectsUi(
        rows = rows,
        showButtons = !editingNow,
        editLabel = if (ordered.isEmpty()) null else if (editingNow) "Done" else "Edit",
        editEnabled = editingNow || !busy,
        emptyText = if (ordered.isEmpty()) NO_PROJECTS else null,
    )
}

/** Edit mode ends by itself when the last project is removed. */
fun editingAfterRemoval(editing: Boolean, remaining: Int): Boolean = editing && remaining > 0

/** The one confirmation, asked only when × would stop running analysis or sorting (plan 2b P7c). */
fun removeConfirmTitle(name: String): String = "Stop analysis and remove $name?"

fun removedToastText(name: String): String = "Removed $name"

/** How long Undo stays offered after × (plan 2b P7b). */
const val UNDO_MILLIS = 5_000L
