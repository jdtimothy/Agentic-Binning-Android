package com.agentic.binning.move

import com.agentic.binning.db.MoveRunEntity
import com.agentic.binning.db.MoveStepEntity
import java.util.Locale

/** A file the run left where it was, and why (a `StepErrors` value). */
data class SkippedFile(val itemId: Long, val name: String, val reason: String)

/**
 * What a finished or stopped run did, for the report (B8). Built from the run and its steps, so it
 * can be shown again after a restart.
 */
data class MoveReport(
    val runId: Long,
    val kind: MoveKind,
    val state: RunState,
    val headline: String,
    /** Why the run stopped, as the job wrote it; null for a finished run. */
    val message: String?,
    /** Files moved (renamed or copied, original deleted). */
    val moved: Int,
    /** Files copied from a source another project holds, which stays (P8). */
    val copiedShared: Int,
    /** Category folders that received a file. */
    val folders: Int,
    val skipped: List<SkippedFile>,
    /** Destination names that got a " 2"-style number. */
    val numbered: List<String>,
    /** Items whose file was gone when its turn came. */
    val becameMissing: Int,
    /** Items found already in place and only marked binned (B9). */
    val inPlace: Int,
    /** The last file that moved, by name: "You stopped it after <this>". */
    val lastMoved: String?,
    /** Empty folders the run removed (B12), by name. */
    val removedFolders: List<String>,
    /** Steps not done because the run stopped. */
    val stillToMove: Int,
    /** Items Bin Files leaves where they are (Failed, Missing). */
    val leftOut: List<LeftOut>,
    /** The folder check after the run: true when nothing was missing or extra; null when it didn't run. */
    val checkOk: Boolean?,
) {
    companion object {
        fun of(run: MoveRunEntity, steps: List<MoveStepEntity>, leftOut: List<LeftOut>): MoveReport {
            val inPlace = run.inPlace
            val kind = MoveKind.of(run.kind)
            val state = RunState.of(run.state)
            val done = steps.filter { StepState.of(it.state) == StepState.Done }
            val moved = done.count { !it.keepSource }
            val shared = done.size - moved
            val folders = done.map { it.dest.substringBeforeLast('/').lowercase(Locale.ROOT) }.distinct().size
            val still = steps.count { !StepState.of(it.state).isFinal }
            val headline = when {
                state == RunState.Done && kind == MoveKind.Save -> "Saved. ${files(done.size)} moved and checked."
                state == RunState.Done && done.isEmpty() && inPlace > 0 ->
                    "Nothing to move. ${files(inPlace)} ${if (inPlace == 1) "was" else "were"} already in place."
                state == RunState.Done && done.isEmpty() -> "Nothing to move."
                state == RunState.Done -> "${files(done.size)} binned into $folders category ${if (folders == 1) "folder" else "folders"}"
                kind == MoveKind.Save -> "Saving stopped"
                else -> "Bin Files stopped"
            }
            return MoveReport(
                runId = run.id,
                kind = kind,
                state = state,
                headline = headline,
                message = run.message,
                moved = moved,
                copiedShared = shared,
                folders = folders,
                skipped = steps.filter { StepState.of(it.state) == StepState.Skipped }
                    .map { SkippedFile(it.itemId, it.dest.substringAfterLast('/'), it.error ?: StepErrors.IO) },
                numbered = done.filter { it.numbered }.map { it.dest.substringAfterLast('/') },
                becameMissing = steps.count { StepState.of(it.state) == StepState.Missing },
                inPlace = inPlace,
                lastMoved = done.lastOrNull()?.dest?.substringAfterLast('/'),
                removedFolders = run.removedFolders.orEmpty().lines().filter { it.isNotBlank() }.map { it.substringAfterLast('/') },
                stillToMove = still,
                leftOut = leftOut,
                checkOk = run.checkOk,
            )
        }

        private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    }
}

/** "3.1 GB", "200 MB", "12 KB": the sizes the storage-full message names (B6). */
fun formatBytes(bytes: Long): String {
    val units = listOf("KB" to 1024.0, "MB" to 1024.0 * 1024, "GB" to 1024.0 * 1024 * 1024)
    val (unit, size) = units.lastOrNull { bytes >= it.second } ?: units.first()
    val value = bytes / size
    return if (value >= 100 || value == Math.floor(value)) {
        String.format(Locale.ROOT, "%.0f %s", Math.max(1.0, Math.round(value).toDouble()), unit)
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, unit)
    }
}
