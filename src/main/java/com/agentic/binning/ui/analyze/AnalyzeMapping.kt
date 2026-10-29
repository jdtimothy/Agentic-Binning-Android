package com.agentic.binning.ui.analyze

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.VideoTiming
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.DownloadState
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.pipeline.PipelineState
import com.agentic.binning.project.ProjectRef
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// The Analyze screen's state types and the pure mapping from pipeline and database state to them.

/** One carousel card. [image] is the prepared thumbnail once it exists, else the media file itself. */
data class CardItem(
    val id: Long,
    val name: String,
    val kind: ItemKind,
    val status: ItemStatus,
    val meta: String,
    /** `m:ss` for videos. */
    val duration: String?,
    val durationMs: Long?,
    val image: File,
    /** True when [image] is the video file (not a thumbnail), so a frame has to be decoded. */
    val imageIsVideo: Boolean,
    /** The prepared thumbnail, once the item has been through preparation. */
    val thumbnail: File? = null,
    val failureReason: String? = null,
    /** Frames the model reads from a video (its frame-strip ticks); 0 for a photo. */
    val frameCount: Int = 0,
) {
    val isDone: Boolean get() = status.isDone
    val isAnalyzed: Boolean get() = status.isAnalyzed
}

/** [current] is the index of the centre card, or -1 when there are no cards; [projectPath] identifies the project. */
data class CarouselState(val cards: List<CardItem>, val current: Int, val projectPath: String? = null) {
    val focus: CardItem? get() = cards.getOrNull(current)
}

/** Where a video's frames are: being extracted one by one, encoded by the model together, or read. */
enum class FrameStage { Extracting, Encoding, Read }

/** How far the item being analyzed has got; drives its scan line, frame strip and progress bar. */
data class CurrentProgress(
    val itemId: Long?,
    val scanning: Boolean,
    /** Photo progress bar fill. */
    val fraction: Float,
    /** Video frames extracted so far (while [stage] is Extracting), of [framesTotal] (0 until known). */
    val framesReady: Int,
    val stage: FrameStage,
    val framesTotal: Int = 0,
)

val NoProgress = CurrentProgress(null, false, 0f, 0, FrameStage.Extracting)

enum class StatusTone { Active, Paused, Quiet }

data class HeaderState(
    val model: DownloadState?,
    val projectName: String?,
    val itemCount: Int,
    val statusText: String,
    val statusTone: StatusTone,
    val countText: String,
    /** Decoding speed of the last reply, e.g. "39 tok/s"; empty until a reply has been measured. */
    val speedText: String = "",
)

data class ControlsState(
    val canStop: Boolean,
    /** Shows Resume (play) instead of Pause. */
    val showResume: Boolean,
    val canToggle: Boolean,
    val mediaCount: Int,
)

/** [time] is null when the chip is hidden. */
data class ActionRow(val time: String?, val text: String)

data class OutputState(
    /** Shown in place of the description, in the secondary colour (e.g. "Reading the image on the NPU…"). */
    val notice: String?,
    val description: String,
    val typing: Boolean,
    val failure: String?,
    val actions: List<ActionRow>,
    val tags: List<String>,
    /** The item shown; the card keeps one scroll position per item. */
    val itemId: Long? = null,
    /** True while the reply is streaming in, so the card follows it down. */
    val live: Boolean = false,
)

val EmptyOutput = OutputState(null, "", false, null, emptyList(), emptyList())

/** A saved analysis as the output card shows it. */
data class StoredAnalysis(val itemId: Long, val description: String, val actions: List<ActionEntry>, val tags: List<String>)

val ItemStatus.isDone: Boolean
    get() = this != ItemStatus.Pending && this != ItemStatus.Analyzing

val ItemStatus.isAnalyzed: Boolean
    get() = this == ItemStatus.Analyzed || this == ItemStatus.Sorted || this == ItemStatus.Binned

/** A typical description length; the photo progress bar fills against it while describing. */
private const val EXPECTED_DESCRIPTION_CHARS = 220f

/**
 * Hides an action's time chip when [t] (`m:ss`) lies past the end of the clip: the model often
 * invents times on short clips. Unknown durations and unparseable times are shown as they are.
 */
fun actionTimeChip(t: String, durationMs: Long?): String? {
    val parts = t.trim().split(':')
    val ms = if (parts.size == 2) {
        val m = parts[0].toLongOrNull()
        val s = parts[1].toLongOrNull()
        if (m != null && s != null) (m * 60 + s) * 1000 else null
    } else {
        null
    }
    if (ms == null || durationMs == null || durationMs <= 0) return t
    return if (ms > durationMs) null else t
}

/** `m:ss`. */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** The last reply's decoding speed for the status row; [state] is the live state or null. */
fun speedText(state: PipelineState?): String =
    state?.lastProfile?.decodingSpeed?.takeIf { it > 0 }?.let { "${it.roundToInt()} tok/s" } ?: ""

/** The pipeline state if it belongs to the [open] project, else null (e.g. right after a switch). */
fun liveState(state: PipelineState, open: ProjectRef?): PipelineState? =
    state.takeIf { open != null && it.project == open }

/** A card for [item]; [thumb] is its prepared thumbnail if it exists. */
fun cardOf(item: ItemEntity, thumb: File?): CardItem {
    val video = item.kind == ItemKind.Video
    return CardItem(
        id = item.id,
        name = item.originalName,
        kind = item.kind,
        status = item.status,
        meta = metaOf(item),
        duration = if (video) formatDuration(item.durationMs ?: 0) else null,
        durationMs = item.durationMs,
        image = thumb ?: File(item.currentPath),
        imageIsVideo = thumb == null && video,
        thumbnail = thumb,
        failureReason = item.failureReason,
        frameCount = if (video) VideoTiming.sampleTimesMs(item.durationMs ?: 0).size else 0,
    )
}

private fun metaOf(item: ItemEntity): String = when (item.kind) {
    ItemKind.Video -> {
        val duration = item.durationMs ?: 0
        val frames = VideoTiming.sampleTimesMs(duration).size
        val atFps = duration <= 0 || frames * 1000L / VideoTiming.FPS.toLong() >= duration
        formatDuration(duration) + " · " + if (atFps) "$frames frames at 2 fps" else "$frames frames, evenly spaced"
    }
    ItemKind.Photo -> listOfNotNull(
        if (item.width != null && item.height != null) "${item.width} × ${item.height}" else null,
        item.takenAt?.let { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(it)) },
    ).joinToString(" · ")
}

/**
 * The centre card: the item being analyzed; otherwise the last analyzed item, else the last item
 * that has been through analysis (Failed or Missing), else the first one.
 */
fun focusIndex(cards: List<CardItem>, liveId: Long?): Int {
    if (cards.isEmpty()) return -1
    liveId?.let { id -> cards.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it } }
    return cards.indexOfLast { it.isAnalyzed }.takeIf { it >= 0 }
        ?: cards.indexOfLast { it.isDone }.takeIf { it >= 0 }
        ?: 0
}

/** Progress of the live item; [state] is the live state ([liveState]) or null. */
fun progressOf(state: PipelineState?): CurrentProgress {
    val id = state?.currentItemId ?: return NoProgress
    val partial = state.partial
    return when (state.phase) {
        PipelinePhase.Preparing -> CurrentProgress(id, true, 0.08f, state.framesReady, FrameStage.Extracting, state.framesTotal)
        // Until the first token the model is still encoding the media on the NPU.
        PipelinePhase.Describing -> if (partial == null) {
            CurrentProgress(id, true, 0.2f, state.framesReady, FrameStage.Encoding, state.framesTotal)
        } else {
            var fraction = 0.3f + 0.5f * (partial.description.length / EXPECTED_DESCRIPTION_CHARS).coerceAtMost(1f)
            if (partial.descriptionDone) fraction = maxOf(fraction, 0.8f)
            if (partial.tags.isNotEmpty()) fraction = 0.95f
            CurrentProgress(id, true, fraction, state.framesReady, FrameStage.Read, state.framesTotal)
        }
        else -> CurrentProgress(id, false, 0.08f, 0, FrameStage.Extracting)
    }
}

/** The status row: what the live run is doing, or how the project stands. [statuses] are the project's items. */
fun statusOf(hasProject: Boolean, statuses: List<ItemStatus>, state: PipelineState?, focus: CardItem?): Pair<String, StatusTone> {
    if (!hasProject) return "No project open" to StatusTone.Quiet
    when (state?.phase) {
        PipelinePhase.LoadingModel -> {
            val download = state.downloadFraction
            return (if (download != null) "Downloading model · ${(download * 100).roundToInt()}%" else "Loading model") to StatusTone.Active
        }
        // The model encodes the media before its first token arrives.
        PipelinePhase.Preparing, PipelinePhase.Describing -> return when {
            state.phase == PipelinePhase.Describing && state.partial != null -> "Describing"
            focus?.kind == ItemKind.Video -> "Encoding frames"
            else -> "Reading image"
        } to StatusTone.Active
        PipelinePhase.Paused -> return "Paused" to StatusTone.Paused
        PipelinePhase.Releasing -> return "Finishing up" to StatusTone.Active
        PipelinePhase.Stopped -> return (state.error?.let { "Stopped · $it" } ?: "Stopped") to StatusTone.Quiet
        PipelinePhase.Idle, null -> Unit
    }
    return when {
        statuses.isEmpty() -> "No media yet" to StatusTone.Quiet
        statuses.any { !it.isDone } -> "Waiting to analyze" to StatusTone.Quiet
        else -> "All caught up" to StatusTone.Quiet
    }
}

/**
 * The output card for the centre [card]: the streaming reply while it is the live item, else its
 * saved analysis ([stored]), failure or a notice.
 */
fun outputOf(hasProject: Boolean, empty: Boolean, card: CardItem?, state: PipelineState?, stored: StoredAnalysis?): OutputState {
    fun notice(text: String) = EmptyOutput.copy(notice = text, itemId = card?.id)
    if (!hasProject) return notice("Create a project, then add a folder or pick photos and clips from the project menu.")
    if (empty || card == null) return notice("Add a folder or pick photos and clips from the project menu.")
    if (state != null && state.currentItemId == card.id) {
        val partial = state.partial
        if (state.phase != PipelinePhase.Describing || partial == null) {
            return notice(
                if (card.kind == ItemKind.Video) {
                    "Reading ${state.framesTotal.takeIf { it > 0 } ?: card.frameCount} frames on the NPU…"
                } else {
                    "Reading the image on the NPU…"
                },
            )
        }
        return OutputState(
            notice = null,
            description = partial.description,
            typing = !partial.descriptionDone,
            failure = null,
            actions = partial.actions.map { ActionRow(actionTimeChip(it.t, card.durationMs), it.text) },
            tags = partial.tags,
            itemId = card.id,
            live = true,
        )
    }
    return when (card.status) {
        ItemStatus.Failed, ItemStatus.Missing -> EmptyOutput.copy(
            failure = when {
                card.status == ItemStatus.Missing -> "The file is missing."
                card.failureReason != null -> "Could not analyze this item: ${card.failureReason}"
                else -> "Could not analyze this item."
            },
            itemId = card.id,
        )
        ItemStatus.Pending, ItemStatus.Analyzing -> notice("Waiting to be analyzed.")
        else -> if (stored == null || stored.itemId != card.id) {
            notice("")
        } else {
            OutputState(
                notice = null,
                description = stored.description,
                typing = false,
                failure = null,
                actions = stored.actions.map { ActionRow(actionTimeChip(it.t, card.durationMs), it.text) },
                tags = stored.tags,
                itemId = card.id,
            )
        }
    }
}
