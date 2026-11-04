package com.agentic.binning.ui.project

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewParent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.agentic.binning.media.HeldFile
import com.agentic.binning.media.SharedCopyResult
import com.agentic.binning.move.formatBytes
import com.agentic.binning.ui.media.Button
import com.agentic.binning.ui.media.ButtonStyle
import com.agentic.binning.ui.media.MediaIcons
import com.agentic.binning.ui.media.isDark
import com.agentic.binning.ui.media.well
import com.agentic.binning.ui.theme.LocalGlassTokens

/** What the notice for media another project holds says (mockup state 7, P8). */
data class SharedNoticeUi(val title: String, val lines: List<String>, val bullets: List<String> = emptyList())

/** The notice's stages. Its window stays up throughout: only the contents, or just the bar, change. */
sealed interface SharedNoticeState {
    data class Asking(val ui: SharedNoticeUi) : SharedNoticeState
    data class Copying(val total: Int, val fraction: Float) : SharedNoticeState
    data class Done(val ui: SharedNoticeUi) : SharedNoticeState
}

private fun filesText(n: Int) = if (n == 1) "1 file" else "$n files"

private fun joined(items: List<String>) = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

fun sharedAskUi(held: List<HeldFile>, added: Int, projectName: String): SharedNoticeUi {
    val byProject = held.groupBy { it.project }
    val where = byProject.entries.joinToString(" ") { (project, files) ->
        val note = if (files.first().listed) "" else " (not on your list)"
        "In $project$note: ${filesText(files.size)}."
    }
    val others = when (added) {
        0 -> ""
        1 -> " The other file you picked was added."
        else -> " The other $added files you picked were added."
    }
    val folders = byProject.keys.map { "Copied from $it/" }
    val first = "$projectName/${folders.first()}" + folders.drop(1).let { if (it.isEmpty()) "" else " and " + joined(it) }
    val copy = when {
        held.size == 1 -> "Copy media files puts a copy of it in $first. The other project's file stays exactly where it is."
        byProject.size == 1 -> "Copy media files puts a copy of each in $first. The other project's files stay exactly where they are."
        else -> "Copy media files puts a copy of each in $first. The other projects' files stay exactly where they are."
    }
    val title = if (held.size == 1) "1 file is already in another project" else "${held.size} files are already in another project"
    return SharedNoticeUi(title, listOf(where + others, copy))
}

fun sharedCancelledUi(held: List<HeldFile>, added: Int): SharedNoticeUi {
    val projects = joined(held.map { it.project }.distinct())
    return SharedNoticeUi(
        title = if (added == 0) "Nothing added" else "$added added, ${held.size} left out",
        lines = listOf(
            if (held.size == 1) "The file in $projects wasn't added. Pick it again to copy it." else "The ${held.size} files in $projects weren't added. Pick them again to copy them.",
        ),
    )
}

fun sharedDoneUi(r: SharedCopyResult, added: Int): SharedNoticeUi {
    val total = added + r.copied
    return SharedNoticeUi(
        title = if (total == 0) "Nothing added" else "$total added",
        lines = if (r.copied > 0) listOf("They're analyzed and sorted like any other item, and Bin Files moves them into their category folders.") else emptyList(),
        bullets = buildList {
            if (added > 0) add("$added added from where they are")
            if (r.copied > 0) add("${r.copied} copied into ${joined(r.folders)}")
            if (r.alreadyHere > 0) add(if (r.alreadyHere == 1) "1 was already in this project" else "${r.alreadyHere} were already in this project")
            if (r.failed > 0) add("${r.failed} couldn't be copied cleanly and stayed out. Nothing was lost.")
            if (r.notTried > 0) {
                val weren = if (r.notTried == 1) "wasn't" else "weren't"
                add("${r.notTried} $weren copied: free up ${formatBytes(r.neededBytes)} and pick them again.")
            }
        },
    )
}

internal interface SharedNoticeActions {
    fun onCopyHeld()
    fun onCancelHeld()
    fun onNoticeOk()
}

private fun findWindowProvider(view: View): DialogWindowProvider? {
    var p: ViewParent? = view.parent
    if (view is DialogWindowProvider) return view
    while (p != null) {
        if (p is DialogWindowProvider) return p
        p = p.parent
    }
    return null
}

/**
 * Mockup state 7. It sits over the project sheet, which has its own window, so unlike the Bin Files sheets
 * its glass is Android's window blur over whatever is behind. With cross-window blur off (battery saver,
 * for one) the same tint sits over the theme's base so the text stays readable. Nothing here redraws while
 * files copy: only the bar's width changes.
 */
@Composable
internal fun SharedNoticeDialog(state: SharedNoticeState, actions: SharedNoticeActions) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(28.dp)
    Dialog(
        onDismissRequest = {
            when (state) {
                is SharedNoticeState.Asking -> actions.onCancelHeld()
                is SharedNoticeState.Done -> actions.onNoticeOk()
                is SharedNoticeState.Copying -> Unit
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        val density = LocalDensity.current
        val tint = if (tokens.isDark) tokens.well else tokens.glass
        SideEffect {
            findWindowProvider(view)?.window?.let { window ->
                val blur = window.windowManager.isCrossWindowBlurEnabled
                val fill = if (blur) tint else tint.compositeOver(tokens.bg)
                window.setBackgroundDrawable(
                    GradientDrawable().apply {
                        cornerRadius = with(density) { 28.dp.toPx() }
                        setColor(fill.toArgb())
                    },
                )
                if (blur) window.setBackgroundBlurRadius(with(density) { 26.dp.roundToPx() } * 2)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.border, shape).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is SharedNoticeState.Asking -> {
                    Body(state.ui)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        Button("Cancel", null, ButtonStyle.Secondary, onClick = actions::onCancelHeld)
                        Button("Copy media files", MediaIcons.Copy, ButtonStyle.Primary, onClick = actions::onCopyHeld)
                    }
                }
                is SharedNoticeState.Copying -> {
                    Body(SharedNoticeUi("Copying ${filesText(state.total)}", listOf("Each copy is checked against its original before it's added.")))
                    val fraction by animateFloatAsState(state.fraction, tween(300), label = "copy progress")
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(tokens.track)) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(tokens.accent))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Spacer(Modifier.weight(1f))
                        Button("Copying…", null, ButtonStyle.Secondary, enabled = false, onClick = {})
                    }
                }
                is SharedNoticeState.Done -> {
                    Body(state.ui)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Spacer(Modifier.weight(1f))
                        Button("OK", null, ButtonStyle.Primary, Modifier.width(88.dp), onClick = actions::onNoticeOk)
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(ui: SharedNoticeUi) {
    val tokens = LocalGlassTokens.current
    Text(ui.title, Modifier.semantics { heading() }, color = tokens.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
    if (ui.bullets.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ui.bullets.forEach { Text("•  $it", color = tokens.sub, fontSize = 13.5.sp, lineHeight = 19.sp) }
        }
    }
    ui.lines.forEach { Text(it, color = tokens.sub, fontSize = 14.sp, lineHeight = 20.sp) }
}
