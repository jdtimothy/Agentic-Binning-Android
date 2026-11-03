package com.agentic.binning.ui.media

import androidx.compose.ui.graphics.vector.ImageVector
import com.agentic.binning.ui.analyze.AnalyzeIcons
import com.agentic.binning.ui.project.ProjectIcons

/** Project Media's 24×24 line icons from the mockup, those not already in [AnalyzeIcons] or [ProjectIcons]. */
internal object MediaIcons {
    private const val FOLDER = "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"

    val Back: ImageVector = AnalyzeIcons.icon("back", 2.2f, "M15 18l-6-6 6-6")

    val Settings: ImageVector = AnalyzeIcons.Settings

    val Search: ImageVector = AnalyzeIcons.icon("search", 2f, "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M20 20l-3.5-3.5")

    val Close: ImageVector = AnalyzeIcons.icon("x", 2f, "M6 6l12 12M18 6L6 18")

    val Check: ImageVector = AnalyzeIcons.Check

    val Lock: ImageVector = AnalyzeIcons.icon(
        "lock",
        2.2f,
        "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z",
        "M8 11V8a4 4 0 0 1 8 0v3",
    )

    val Clock: ImageVector = AnalyzeIcons.icon("clock", 2.4f, "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M12 8v4l3 2")

    val Alert: ImageVector = AnalyzeIcons.icon("alert", 2.4f, "M12 4l9 16H3z", "M12 10v4M12 17.5v.01")

    val Play: ImageVector = AnalyzeIcons.Play

    val Chevron: ImageVector = AnalyzeIcons.icon("chev", 2f, "M6 9l6 6 6-6")

    val Shuffle: ImageVector = AnalyzeIcons.icon(
        "shuffle",
        2f,
        "M4 7h3l10 10h3M4 17h3l3-3M14 10l3-3h3",
        "M18 5l2 2-2 2M18 15l2 2-2 2",
    )

    val Pencil: ImageVector = AnalyzeIcons.icon("pencil", 2f, "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")

    val Select: ImageVector = AnalyzeIcons.icon(
        "select",
        2f,
        "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4z",
        "M8 12l3 3 5-6",
    )

    val Photos: ImageVector = AnalyzeIcons.icon(
        "photos",
        1.8f,
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7 10a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "M21 16l-5-5-8 8",
    )

    val Folder: ImageVector = ProjectIcons.Folder

    val FolderCheck: ImageVector = AnalyzeIcons.icon("folder-check", 2f, FOLDER, "M9 13l2 2 4-4")

    val Plus: ImageVector = ProjectIcons.Plus

    val ChevronRight: ImageVector = ProjectIcons.ChevronRight

    val FolderMove: ImageVector = AnalyzeIcons.icon("folder-move", 2f, FOLDER, "M9 13h6M13 11l2 2-2 2")

    val Remove: ImageVector = AnalyzeIcons.icon("remove", 2f, "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M8 12h8")

    val Info: ImageVector = AnalyzeIcons.icon("info", 2f, "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M12 11v5M12 8v.01")
}
