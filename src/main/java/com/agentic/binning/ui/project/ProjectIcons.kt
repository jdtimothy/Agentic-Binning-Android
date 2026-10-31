package com.agentic.binning.ui.project

import androidx.compose.ui.graphics.vector.ImageVector
import com.agentic.binning.ui.analyze.AnalyzeIcons

/** The project sheet's 24×24 line icons (tinted by `Icon`). */
internal object ProjectIcons {
    private const val FOLDER = "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"

    val Folder: ImageVector = AnalyzeIcons.Folder

    val FolderPlus: ImageVector = AnalyzeIcons.icon("folder-plus", 1.8f, FOLDER, "M12 10v6M9 13h6")

    val Photo: ImageVector = AnalyzeIcons.icon(
        "photo",
        1.8f,
        "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M10 9.5a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z",
        "M21 15l-5-5L5 20",
    )

    val PhotoPlus: ImageVector = AnalyzeIcons.icon(
        "photo-plus",
        1.8f,
        "M13 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6",
        "M18 2v6M15 5h6",
        "M10 9.5a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z",
        "M19 15.5l-4-4L5.5 20",
    )

    val Plus: ImageVector = AnalyzeIcons.icon("plus", 2.2f, "M12 5v14M5 12h14")

    val ChevronRight: ImageVector = AnalyzeIcons.icon("chevron-right", 2f, "M9 6l6 6-6 6")

    val ArrowLeft: ImageVector = AnalyzeIcons.icon("arrow-left", 2f, "M19 12H5M12 19l-7-7 7-7")
}
