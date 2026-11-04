package com.agentic.binning.ui.media

import com.agentic.binning.move.BinPreview
import com.agentic.binning.move.LayoutProblems
import com.agentic.binning.move.LeftOut
import com.agentic.binning.move.LeftOutReason
import com.agentic.binning.move.MoveKind
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.move.MoveReport
import com.agentic.binning.move.MovedFile
import com.agentic.binning.move.RunState
import com.agentic.binning.move.SkippedFile
import com.agentic.binning.move.StepErrors
import com.agentic.binning.move.formatBytes
import com.agentic.binning.pipeline.FailureReasons

/*
 * What the Bin Files surfaces say (Plan 3 Task 16, mockup states 1 to 6), free of Compose so the wording
 * and the rules behind it run as JVM tests.
 */

enum class FactIcon { Photos, Disk, Copy, FolderCheck, FolderMove, Tag, Trash, Clock, Check, InPlace }

enum class FactTone { Normal, Good, Warn }

/** One row of a facts card: "From Camera, DCIM/Camera, 192". */
data class FactUi(val icon: FactIcon, val text: String, val sub: String?, val value: String?, val tone: FactTone = FactTone.Normal)

data class CalloutUi(val text: String, val bold: String? = null, val warn: Boolean = false)

/** An item a run left where it is, or couldn't move; a tap opens its sheet. */
data class LeftRowUi(val itemId: Long, val name: String, val sub: String, val why: String)

data class ConfirmUi(
    val headline: String,
    val intro: String,
    val facts: List<FactUi>,
    val callouts: List<CalloutUi>,
    val left: List<LeftRowUi>,
    val startLabel: String,
)

data class ReportUi(
    val title: String,
    /** The big line of a finished run; a stopped one leads with its callout instead. */
    val headline: String?,
    val sub: String?,
    val callout: CalloutUi?,
    val facts: List<FactUi>,
    val skipped: List<LeftRowUi>,
    val left: List<LeftRowUi>,
    val primary: String,
    /** The button beside [primary] that only closes; null when [primary] does. */
    val secondary: String?,
    val primaryStartsARun: Boolean,
    /** [primary] opens the confirm sheet: a Save's report can't say what a Bin Files would move. */
    val confirmFirst: Boolean = false,
    /** What [primary] promises to move, when it names a count; more than this asks first. */
    val stillToMove: Int = 0,
)

data class UnknownRowUi(val path: String, val sub: String)

/** What the unknown-files sheet says about one file. */
data class UnknownFileFacts(val added: String, val size: String)

data class UnknownUi(
    val callout: CalloutUi,
    val unknown: List<UnknownRowUi>,
    val missing: List<UnknownRowUi>,
    /** The files Add to project and Leave them act on. */
    val paths: List<String>,
    /** False once Add to project has failed for these: only Leave them can get past them. */
    val canAdd: Boolean = true,
)

/** What the user asked of the run in progress, until it has happened (B2). */
enum class MoveRequest { None, Pause, Stop }

data class MoveCardUi(
    val title: String,
    val count: String,
    val fraction: Float,
    /** State 6: the run picked up after the app closed. */
    val banner: String?,
    val file: String,
    val fileSub: String,
    val recent: List<MovedFile>,
    val note: String,
    val paused: Boolean,
    val canPause: Boolean,
    val canStop: Boolean,
)

private const val SHARED_STORAGE = "/storage/emulated/0/"

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

private fun folders(n: Int) = if (n == 1) "1 folder" else "$n folders"

private fun folderOf(path: String) = path.substringBeforeLast('/').substringAfterLast('/')

private fun leftRows(leftOut: List<LeftOut>) = leftOut.filter { it.reason != LeftOutReason.NoCategory }.map {
    if (it.reason == LeftOutReason.Failed) {
        LeftRowUi(it.itemId, it.name, "${folderOf(it.path)} · ${FailureReasons.describe(it.failure)}", "Failed")
    } else {
        LeftRowUi(it.itemId, it.name, "Was in ${folderOf(it.path)} · not found", "Missing")
    }
}

/** [projectPath] is the project's real folder, wherever it was opened from: this sheet can't be undone. */
fun confirmUi(p: BinPreview, projectPath: String): ConfirmUi {
    val facts = buildList {
        p.sources.forEach { add(FactUi(FactIcon.Photos, "From ${it.folder}", it.path, "${it.count}")) }
        if (p.otherStorage > 0) {
            val sub = "Copied, checked, then deleted from the card · needs ${formatBytes(p.copyBytes)} free, ${formatBytes(p.freeBytes)} free"
            add(FactUi(FactIcon.Disk, "From the SD card", sub, "${p.otherStorage}"))
        }
        if (p.shared > 0) add(FactUi(FactIcon.Copy, "Also in another project", "Copied into their folders; the other project keeps its file", "${p.shared}"))
        if (p.inPlace > 0) add(FactUi(FactIcon.FolderCheck, "Already in their folders", "Stay as they are", "${p.inPlace}", FactTone.Good))
    }
    val gallery = if (p.sources.any { it.path == "DCIM/Camera" }) {
        "In Gallery they leave the Camera album and show up in albums named after their categories."
    } else {
        "In Gallery they show up in albums named after their categories."
    }
    return ConfirmUi(
        headline = "Bin ${files(p.files)} into ${folders(p.folders)}",
        intro = "Into ${projectPath.removePrefix(SHARED_STORAGE).trimEnd('/')}/, one folder per category.",
        facts = facts,
        callouts = listOf(
            CalloutUi("Each file moves into its category folder and is checked. Nothing is overwritten: a name that's taken gets a number.", bold = "No undo."),
            CalloutUi(gallery),
        ),
        left = leftRows(p.leftOut),
        startLabel = "Bin ${files(p.files)}",
    )
}

fun moveCardUi(p: MoveProgress, resumedFrom: Int?, request: MoveRequest): MoveCardUi {
    val shown = if (p.paused) p.done else minOf(p.done + 1, p.total)
    val banner = resumedFrom?.let {
        val moved = when (it) {
            0 -> "Nothing"
            1 -> "1 file had moved;"
            else -> "$it files had moved;"
        }
        if (it == 0) "Picked up where it stopped when the app closed. Nothing was lost." else "Picked up where it stopped when the app closed. $moved nothing was lost."
    }
    return MoveCardUi(
        title = when {
            p.paused -> "Paused"
            p.kind == MoveKind.Save -> "Saving changes"
            resumedFrom != null -> "Finishing Bin Files"
            else -> "Binning files"
        },
        count = if (p.kind == MoveKind.Save) "$shown of ${files(p.total)}" else "$shown of ${p.total}",
        fraction = if (p.total == 0) 0f else p.done.toFloat() / p.total,
        banner = banner,
        file = if (p.paused) "Next: ${p.next ?: p.current.orEmpty()}" else p.current.orEmpty(),
        fileSub = when {
            p.paused -> "The file in progress finished. Nothing is half moved."
            p.from != null && p.to != null -> "${p.from} → ${p.to}"
            else -> ""
        },
        recent = p.recent,
        note = when {
            request == MoveRequest.Stop -> "Stopping after this file…"
            request == MoveRequest.Pause && !p.paused -> "Pausing after this file…"
            p.paused -> "Other edits wait until it ends."
            p.kind == MoveKind.Save -> "Other edits wait until this finishes. You can still browse and search."
            else -> "Other edits wait. You can leave this screen."
        },
        paused = p.paused,
        canPause = !p.paused && request == MoveRequest.None,
        canStop = request != MoveRequest.Stop,
    )
}

private fun skippedRow(s: SkippedFile): LeftRowUi {
    val why = when (s.reason) {
        StepErrors.COPY_MISMATCH -> "Its copy didn't match the original twice."
        StepErrors.INTERRUPTED -> "The app closed twice while moving it."
        else -> "The phone refused to move it."
    }
    return LeftRowUi(s.itemId, s.name, "$why Nothing was lost; the next Bin Files tries again.", "Skipped")
}

private fun namesSub(all: List<String>): String {
    val names = all.distinct()
    return if (names.size <= SHOWN_NAMES) names.joinToString(", ") else names.take(SHOWN_NAMES).joinToString(", ") + " and ${names.size - SHOWN_NAMES} more"
}

private const val SHOWN_NAMES = 3

fun reportUi(r: MoveReport): ReportUi = if (r.state == RunState.Done) doneReport(r) else stoppedReport(r)

private fun doneReport(r: MoveReport): ReportUi {
    val total = r.moved + r.copiedShared
    val sub = when (r.checkOk) {
        true -> if (total == 0) "Checked the category folders. Nothing missing or extra." else "Checked ${files(total)} in ${folders(r.folders)}. Nothing missing or extra."
        false -> "Something in the category folders doesn't match. The next Bin Files shows what."
        null -> null
    }
    val facts = buildList {
        if (total > 0) {
            val movedSub = if (r.copiedShared > 0) "${r.moved} moved, ${r.copiedShared} copied from another project" else null
            add(FactUi(FactIcon.FolderMove, "Moved now", movedSub, "$total"))
        }
        if (r.inPlace > 0) add(FactUi(FactIcon.FolderCheck, "Already in place", "Marked as binned", "${r.inPlace}", FactTone.Good))
        if (r.numbered.isNotEmpty()) add(FactUi(FactIcon.Tag, "Names that got a number", namesSub(r.numbered), "${r.numbered.size}"))
        if (r.removedFolders.isNotEmpty()) add(FactUi(FactIcon.Trash, "Empty folders removed", namesSub(r.removedFolders), "${r.removedFolders.size}"))
        checkFact(r.checkOk, "Nothing missing or extra")?.let(::add)
    }
    return ReportUi(
        title = if (r.kind == MoveKind.Save) "Files saved" else "Files binned",
        headline = r.headline,
        sub = sub,
        callout = null,
        facts = facts,
        skipped = r.skipped.map(::skippedRow),
        left = leftRows(r.leftOut),
        primary = "Done",
        secondary = null,
        primaryStartsARun = false,
    )
}

private fun checkFact(ok: Boolean?, okSub: String): FactUi? = when (ok) {
    true -> FactUi(FactIcon.Check, "Folder check", okSub, "OK", FactTone.Good)
    false -> FactUi(FactIcon.Clock, "Folder check", "Something doesn't match", "Check", FactTone.Warn)
    null -> null
}

private fun stoppedReport(r: MoveReport): ReportUi {
    val total = r.moved + r.copiedShared
    val storage = r.state == RunState.Stopped && r.message != null
    val failed = r.state != RunState.Stopped
    val callout = when {
        storage -> CalloutUi(r.message.orEmpty().removePrefix(STORAGE_PREFIX), bold = "Bin Files stopped: storage is full.", warn = true)
        failed -> CalloutUi(r.message.orEmpty().trimEnd('.') + ".", bold = "Bin Files stopped: something went wrong.", warn = true)
        r.lastMoved == null -> CalloutUi("You stopped it before any file moved. Press Bin Files to move them.", bold = "Bin Files stopped.", warn = true)
        else -> CalloutUi("You stopped it after ${r.lastMoved}. Press Bin Files to move the rest.", bold = "Bin Files stopped.", warn = true)
    }
    val movedSub = if (total == 1) "The 1 moved file is in place" else "The $total moved files are all in place"
    val facts = buildList {
        add(FactUi(FactIcon.FolderMove, "Moved", null, "$total"))
        if (r.stillToMove > 0) add(FactUi(FactIcon.Clock, "Still where they were", "Moved by the next Bin Files", "${r.stillToMove}", FactTone.Warn))
        checkFact(r.checkOk, if (total == 0) "Nothing missing or extra" else movedSub)?.let(::add)
    }
    val again = storage || failed
    val save = r.kind == MoveKind.Save
    return ReportUi(
        title = if (save) "Saving stopped" else "Bin Files stopped",
        headline = null,
        sub = null,
        callout = callout,
        facts = facts,
        skipped = r.skipped.map(::skippedRow),
        left = leftRows(r.leftOut),
        primary = when {
            save -> "Bin Files"
            again -> "Try again"
            else -> "Bin ${files(r.stillToMove)}"
        },
        secondary = "Close",
        primaryStartsARun = true,
        confirmFirst = save,
        stillToMove = r.stillToMove,
    )
}

private const val STORAGE_PREFIX = "Storage is full. "

fun unknownUi(problems: LayoutProblems, projectPath: String, projectName: String, canAdd: Boolean = true, facts: (String) -> UnknownFileFacts): UnknownUi {
    fun inside(path: String) = path.removePrefix(projectPath.trimEnd('/') + "/")
    return UnknownUi(
        callout = CalloutUi(
            "These files are in category folders, but they aren't in $projectName. Bin Files checks the folders before it moves anything.",
            bold = "Nothing was moved.",
            warn = true,
        ),
        unknown = problems.unexpected.map { path ->
            val f = facts(path)
            UnknownRowUi(inside(path), "Added ${f.added} · ${f.size}")
        },
        missing = problems.missing.map {
            UnknownRowUi(inside(it), "Binned, but no longer there. It is listed as Missing and doesn't block Bin Files.")
        },
        paths = problems.unexpected,
        canAdd = canAdd,
    )
}
