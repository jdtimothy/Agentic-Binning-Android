package com.agentic.binning.ui.project

import com.agentic.binning.project.ProjectRef
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

/** The sheet opens on New project when there is no project to add media to. */
fun startPage(current: ProjectRef?): SheetPage = if (current == null) SheetPage.NewProject else SheetPage.Main

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
