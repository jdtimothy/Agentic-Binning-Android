package com.agentic.binning.projectmedia

import com.agentic.binning.db.ItemStatus
import com.agentic.binning.pipeline.PipelinePhase

/**
 * Item counts for one project, as the Project Media status line needs them. Every item is in
 * exactly one bucket, so the buckets add up to [total].
 */
data class MediaCounts(
    val total: Int,
    val pending: Int = 0,
    val analyzing: Int = 0,
    /** Analyzed, but sorting hasn't given it a category yet. */
    val waiting: Int = 0,
    val failed: Int = 0,
    val missing: Int = 0,
    /** Has a category, and its file is not yet at its target path. */
    val readyToBin: Int = 0,
    /** Has a category, and its file is at its target path. */
    val binned: Int = 0,
)

/**
 * Waiting to be categorized (Task 6.4): analyzed, with no category, and not one the user placed.
 * Exactly what the sorter picks up, so Categorize now has something to do whenever this is true.
 */
fun isWaiting(status: ItemStatus, categoryId: Long?, categorySetByUser: Boolean): Boolean =
    status == ItemStatus.Analyzed && categoryId == null && !categorySetByUser

/**
 * Counts [items] into [MediaCounts]' buckets (Task 10). Project Media and the Analyze screen's
 * Project Media button both count through here. 2a's `ItemCounts` lumps statuses differently
 * (its failed includes Missing), so it is not reused.
 */
fun <T> mediaCountsOf(items: List<T>, status: (T) -> ItemStatus, waiting: (T) -> Boolean): MediaCounts {
    val by = items.groupingBy(status).eachCount()
    fun n(s: ItemStatus) = by[s] ?: 0
    return MediaCounts(
        total = items.size,
        pending = n(ItemStatus.Pending),
        analyzing = n(ItemStatus.Analyzing),
        waiting = items.count(waiting),
        failed = n(ItemStatus.Failed),
        missing = n(ItemStatus.Missing),
        readyToBin = n(ItemStatus.Sorted),
        binned = n(ItemStatus.Binned),
    )
}

/** The one job allowed to run on the project (the spec's "one model loaded" and Save/Bin rules). */
sealed interface ActiveJob {
    data object Analysis : ActiveJob
    data object Sorting : ActiveJob
    data class Saving(val filesDone: Int, val filesTotal: Int) : ActiveJob
    data class Binning(val filesDone: Int, val filesTotal: Int) : ActiveJob
}

/**
 * The job running now, from the pipeline's phase (Task 10): categorizing is Sorting, any other
 * running phase (including Paused, which holds the vision model) is Analysis; Idle, Stopped or no
 * run is none. Saving and Binning come with Plan 3. Not from `ModelArbiter.active` (Task 6.3).
 */
fun activeJobOf(phase: PipelinePhase?): ActiveJob? = when {
    phase == null || !phase.isRunning -> null
    phase == PipelinePhase.Categorizing -> ActiveJob.Sorting
    else -> ActiveJob.Analysis
}

enum class MediaTone { Running, Attention, Ready, Quiet }

enum class StatusAction { CategorizeNow }

data class MediaStatus(
    val text: String,
    val tone: MediaTone,
    /** A link shown after the text, e.g. "Categorize now". */
    val action: StatusAction?,
    val binFilesEnabled: Boolean,
    /** The count shown on Bin Files when it is enabled. */
    val readyToBin: Int,
)

/**
 * The status line and Bin Files state for Project Media. Bin Files is enabled only when files are
 * waiting to move, nothing is pending, analyzing or waiting to be categorized, and no job runs.
 * Failed and missing items never block it. The line shows at most two facts so it fits two lines.
 */
fun mediaStatus(counts: MediaCounts, job: ActiveJob?): MediaStatus {
    val c = counts
    val enabled = job == null && c.readyToBin > 0 && c.pending == 0 && c.analyzing == 0 && c.waiting == 0
    fun running(text: String) = MediaStatus(text, MediaTone.Running, null, false, c.readyToBin)

    when (job) {
        is ActiveJob.Saving -> return running(
            if (job.filesTotal == 0) "Saving…"
            else "Saving · moving ${minOf(job.filesDone + 1, job.filesTotal)} of ${files(job.filesTotal)}",
        )
        is ActiveJob.Binning -> return running(
            "Binning · ${minOf(job.filesDone + 1, job.filesTotal)} of ${files(job.filesTotal)}",
        )
        ActiveJob.Sorting -> return running("Categorizing…")
        ActiveJob.Analysis -> return running("Analyzing ${c.pending + c.analyzing} left")
        null -> Unit
    }
    if (c.analyzing > 0) return running("Analyzing ${c.pending + c.analyzing} left")

    val first = when {
        c.readyToBin > 0 -> "${files(c.readyToBin)} ready to bin"
        c.binned > 0 -> "All files binned"
        else -> null
    }
    val (second, action) = when {
        c.pending > 0 -> "${c.pending} waiting to analyze" to null
        c.waiting > 0 -> "${c.waiting} waiting to be categorized" to StatusAction.CategorizeNow
        c.failed > 0 && c.missing > 0 -> "${c.failed} failed, ${c.missing} missing" to null
        c.failed > 0 -> "${c.failed} failed" to null
        c.missing > 0 -> "${c.missing} missing" to null
        else -> null to null
    }
    val text = listOfNotNull(first, second).joinToString(" · ").ifEmpty { "Nothing to bin yet" }
    val tone = when {
        c.pending > 0 || c.waiting > 0 -> MediaTone.Attention
        enabled -> MediaTone.Ready
        else -> MediaTone.Quiet
    }
    return MediaStatus(text, tone, action, enabled, c.readyToBin)
}

/**
 * Why Bin Files is off, for the toast a tap on it shows; null when it is on (Q12). The same order
 * as [mediaStatus]: a running job first, then what blocks it, then nothing to move.
 */
fun binFilesBlockedReason(counts: MediaCounts, job: ActiveJob?): String? {
    val c = counts
    return when {
        job != null -> "Bin Files is available when analysis is stopped or done"
        c.pending > 0 || c.analyzing > 0 -> "Bin Files waits for analysis to finish"
        c.waiting > 0 -> "Bin Files waits for ${items(c.waiting)} to be categorized"
        c.readyToBin == 0 -> if (c.binned > 0) "All files are binned" else "Nothing to bin yet"
        else -> null
    }
}

private fun items(n: Int) = if (n == 1) "1 item" else "$n items"

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
