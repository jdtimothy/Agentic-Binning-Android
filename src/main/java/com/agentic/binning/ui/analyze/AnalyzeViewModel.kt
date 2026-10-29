package com.agentic.binning.ui.analyze

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.VideoTiming
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.DownloadState
import com.agentic.binning.pipeline.PipelineController
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.pipeline.PipelineState
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectRef
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    val failureReason: String? = null,
    /** Frames the model reads from a video (its frame-strip ticks); 0 for a photo. */
    val frameCount: Int = 0,
) {
    val isDone: Boolean get() = status.isDone
    val isAnalyzed: Boolean get() = status.isAnalyzed
}

/** [current] is the index of the centre card, or -1 when there are no cards. */
data class CarouselState(val cards: List<CardItem>, val current: Int) {
    val focus: CardItem? get() = cards.getOrNull(current)
}

/** Where a video's frames are: being extracted one by one, encoded by the model together, or read. */
enum class FrameStage { Extracting, Encoding, Read }

/** How far the item being analyzed has got; drives its scan line, frame strip and progress bar. */
data class CurrentProgress(
    val itemId: Long?,
    val scanning: Boolean,
    val fraction: Float,
    /** Video frames extracted so far (while [stage] is Extracting). */
    val framesReady: Int,
    val stage: FrameStage,
)

private val NoProgress = CurrentProgress(null, false, 0f, 0, FrameStage.Extracting)

enum class StatusTone { Active, Paused, Quiet }

data class HeaderState(
    val model: DownloadState?,
    val projectName: String?,
    val itemCount: Int,
    val statusText: String,
    val statusTone: StatusTone,
    val countText: String,
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
    val speed: String,
)

private val ItemStatus.isDone: Boolean
    get() = this != ItemStatus.Pending && this != ItemStatus.Analyzing

private val ItemStatus.isAnalyzed: Boolean
    get() = this == ItemStatus.Analyzed || this == ItemStatus.Sorted || this == ItemStatus.Binned

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

/** Feeds the Analyze screen from the pipeline, the open project's database and the model download state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeViewModel(
    private val pipeline: PipelineController,
    private val currentProject: StateFlow<OpenProject?>,
    modelState: Flow<DownloadState>,
    /** Starts analysis of a project in the foreground service (AppGraph.startAnalysis). */
    private val startAnalysis: (ProjectRef) -> Boolean,
) : ViewModel() {
    private class ProjectItems(val project: OpenProject, val items: List<ItemEntity>)

    private val projectItems: StateFlow<ProjectItems?> = currentProject
        .flatMapLatest { project ->
            if (project == null) {
                flowOf(null)
            } else {
                project.db.items().observeAll()
                    .map { ProjectItems(project, it) }
                    // The database closes on a project switch, possibly before this collector moves on.
                    .catch { e -> Log.w(TAG, "Item list of ${project.ref.name} stopped", e) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The pipeline state if it belongs to the open project, else null. */
    private val live: Flow<PipelineState?> = combine(pipeline.state, currentProject) { state, project ->
        state.takeIf { project != null && it.project == project.ref }
    }

    /** Re-checks thumbnails when the current item moves on: they are written while it is prepared. */
    private val thumbTick = pipeline.state.map { it.currentItemId to it.phase }.distinctUntilChanged()

    private val cards: Flow<List<CardItem>> = combine(projectItems, thumbTick) { pi, _ -> pi }
        .mapLatest { pi ->
            if (pi == null) {
                emptyList()
            } else {
                val thumbs = ProjectFolders.thumbsDir(File(pi.project.ref.path))
                pi.items.map { card(it, thumbs) }
            }
        }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    val carousel: StateFlow<CarouselState> = combine(
        cards,
        live.map { it?.currentItemId }.distinctUntilChanged(),
    ) { cards, liveId -> CarouselState(cards, focusIndex(cards, liveId)) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CarouselState(emptyList(), -1))

    val progress: StateFlow<CurrentProgress> = live
        .map { progressOf(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NoProgress)

    val header: StateFlow<HeaderState> = combine(
        modelState.flowOn(Dispatchers.IO),
        projectItems,
        live,
        carousel,
    ) { model, pi, state, carousel ->
        val items = pi?.items.orEmpty()
        val (text, tone) = statusOf(pi != null, items, state, carousel.focus)
        val analyzed = items.count { it.status.isAnalyzed }
        val failed = items.count { it.status == ItemStatus.Failed || it.status == ItemStatus.Missing }
        HeaderState(
            model = model,
            projectName = pi?.project?.ref?.name ?: currentProject.value?.ref?.name,
            itemCount = items.size,
            statusText = text,
            statusTone = tone,
            countText = if (pi == null) "" else "$analyzed of ${items.size} analyzed" + if (failed > 0) " · $failed failed" else "",
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, HeaderState(null, null, 0, "", StatusTone.Quiet, ""))

    val controls: StateFlow<ControlsState> = combine(projectItems, live) { pi, state ->
        val running = state?.phase?.isRunning == true
        val paused = state?.phase == PipelinePhase.Paused
        val hasPending = pi?.items.orEmpty().any { !it.status.isDone }
        ControlsState(
            canStop = running,
            showResume = !running || paused,
            canToggle = running || (pi != null && hasPending),
            mediaCount = pi?.items?.size ?: 0,
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ControlsState(false, true, false, 0))

    private class Stored(val itemId: Long, val description: String, val actions: List<ActionEntry>, val tags: List<String>)

    /** The saved analysis of the centre card, once it has one. */
    private val stored: Flow<Stored?> = combine(currentProject, carousel.map { it.focus }.distinctUntilChanged()) { p, c -> p to c }
        .mapLatest { (project, card) ->
            if (project == null || card == null || !card.status.isAnalyzed) return@mapLatest null
            try {
                val analysis = project.db.analyses().get(card.id) ?: return@mapLatest null
                val actions = runCatching {
                    json.decodeFromString(ListSerializer(ActionEntry.serializer()), analysis.actionsJson)
                }.getOrDefault(emptyList())
                Stored(card.id, analysis.description, actions, project.db.analyses().tagsFor(card.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the analysis of item ${card.id}", e)
                null
            }
        }
        .flowOn(Dispatchers.IO)

    val output: StateFlow<OutputState> = combine(
        projectItems.map { (it != null) to (it?.items?.isEmpty() ?: true) }.distinctUntilChanged(),
        carousel.map { it.focus }.distinctUntilChanged(),
        live,
        stored,
    ) { (hasProject, empty), card, state, stored -> outputOf(hasProject, empty, card, state, stored) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, OutputState(null, "", false, null, emptyList(), emptyList(), ""))

    fun stop() = pipeline.stop()

    /** Pause while running; Resume continues a paused run or starts a new one for the open project. */
    fun togglePause() {
        val project = currentProject.value ?: return
        val state = pipeline.state.value
        val live = state.project == project.ref
        when {
            live && state.phase == PipelinePhase.Paused -> pipeline.resume()
            live && state.phase.isRunning -> pipeline.pause()
            else -> startAnalysis(project.ref)
        }
    }

    private fun card(item: ItemEntity, thumbsDir: File): CardItem {
        val thumb = File(thumbsDir, "${item.id}.jpg").takeIf { it.isFile }
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

    private fun focusIndex(cards: List<CardItem>, liveId: Long?): Int {
        if (cards.isEmpty()) return -1
        liveId?.let { id -> cards.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it } }
        // Otherwise the last item that has been through analysis, or the first one waiting.
        return cards.indexOfLast { it.isDone }.takeIf { it >= 0 } ?: 0
    }

    private fun progressOf(state: PipelineState?): CurrentProgress {
        val id = state?.currentItemId ?: return NoProgress
        val partial = state.partial
        return when (state.phase) {
            PipelinePhase.Preparing -> CurrentProgress(id, true, 0.08f, state.framesReady, FrameStage.Extracting)
            // Until the first token the model is still encoding the media on the NPU.
            PipelinePhase.Describing -> if (partial == null) {
                CurrentProgress(id, true, 0.2f, state.framesReady, FrameStage.Encoding)
            } else {
                var fraction = 0.3f + 0.5f * (partial.description.length / EXPECTED_DESCRIPTION_CHARS).coerceAtMost(1f)
                if (partial.descriptionDone) fraction = maxOf(fraction, 0.8f)
                if (partial.tags.isNotEmpty()) fraction = 0.95f
                CurrentProgress(id, true, fraction, state.framesReady, FrameStage.Read)
            }
            else -> CurrentProgress(id, false, 0.08f, 0, FrameStage.Extracting)
        }
    }

    private fun statusOf(hasProject: Boolean, items: List<ItemEntity>, state: PipelineState?, focus: CardItem?): Pair<String, StatusTone> {
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
            items.isEmpty() -> "No media yet" to StatusTone.Quiet
            items.any { !it.status.isDone } -> "Waiting to analyze" to StatusTone.Quiet
            else -> "All caught up" to StatusTone.Quiet
        }
    }

    private fun outputOf(hasProject: Boolean, empty: Boolean, card: CardItem?, state: PipelineState?, stored: Stored?): OutputState {
        val speed = state?.lastProfile?.decodingSpeed?.takeIf { it > 0 }?.let { "${it.roundToInt()} tok/s" } ?: ""
        fun notice(text: String) = OutputState(text, "", false, null, emptyList(), emptyList(), "")
        if (!hasProject) return notice("Create a project, then add a folder or pick photos and clips from the project menu.")
        if (empty || card == null) return notice("Add a folder or pick photos and clips from the project menu.")
        if (state != null && state.currentItemId == card.id) {
            val partial = state.partial
            if (state.phase != PipelinePhase.Describing || partial == null) {
                return notice(
                    if (card.kind == ItemKind.Video) {
                        "Reading ${VideoTiming.sampleTimesMs(card.durationMs ?: 0).size} frames on the NPU…"
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
                speed = speed,
            )
        }
        return when (card.status) {
            ItemStatus.Failed, ItemStatus.Missing -> OutputState(
                null, "", false,
                failure = when {
                    card.status == ItemStatus.Missing -> "The file is missing."
                    card.failureReason != null -> "Could not analyze this item: ${card.failureReason}"
                    else -> "Could not analyze this item."
                },
                actions = emptyList(), tags = emptyList(), speed = "",
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
                    // The speed is only known for replies streamed in this session.
                    speed = "",
                )
            }
        }
    }

    private companion object {
        const val TAG = "AnalyzeViewModel"
        /** A typical description length; the photo progress bar fills against it while describing. */
        const val EXPECTED_DESCRIPTION_CHARS = 220f
        val json = Json { ignoreUnknownKeys = true }
    }
}
