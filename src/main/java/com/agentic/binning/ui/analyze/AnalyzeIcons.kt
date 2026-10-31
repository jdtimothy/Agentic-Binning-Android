package com.agentic.binning.ui.analyze

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The mockup's 24×24 line and solid icons (tinted by `Icon`). */
internal object AnalyzeIcons {
    val Model: ImageVector = icon(
        "model",
        stroke = 1.8f,
        "M8 5h8a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z",
        "M10.5 9.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z",
        "M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3",
    )

    val Folder: ImageVector = icon(
        "folder",
        stroke = 1.8f,
        "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    )

    val Settings: ImageVector = icon(
        "settings",
        stroke = 1.8f,
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 " +
            "1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06" +
            "a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09" +
            "A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 " +
            "1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 " +
            "1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21" +
            "a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
    )

    val Stop: ImageVector = icon(
        "stop",
        stroke = null,
        "M7.5 5h9a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16.5v-9A2.5 2.5 0 0 1 7.5 5z",
    )

    val Pause: ImageVector = icon(
        "pause",
        stroke = null,
        "M7 4h1.5a1.5 1.5 0 0 1 1.5 1.5v13a1.5 1.5 0 0 1-1.5 1.5H7a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 7 4z",
        "M15.5 4H17a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 17 20h-1.5a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 15.5 4z",
    )

    val Play: ImageVector = icon("play", stroke = null, "M7 4l13 8-13 8z")

    val Grid: ImageVector = icon(
        "grid",
        stroke = 2f,
        "M5.5 3.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        "M15.5 3.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        "M5.5 13.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        "M15.5 13.5h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
    )

    val Check: ImageVector = icon("check", stroke = 3f, "M5 12l5 5 9-10")

    val Alert: ImageVector = icon("alert", stroke = 3f, "M12 5v9M12 19v.01")

    /** [stroke] null draws the paths filled. Also builds the project sheet's icons. */
    internal fun icon(name: String, stroke: Float?, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            // A fresh parser per path: toNodes() hands out the parser's own list.
            val nodes = PathParser().parsePathString(d).toNodes()
            if (stroke == null) {
                builder.addPath(nodes, fill = SolidColor(Color.Black))
            } else {
                builder.addPath(
                    nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return builder.build()
    }
}
