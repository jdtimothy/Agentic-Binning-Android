package com.agentic.binning.ui.analyze

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.DownloadState
import com.agentic.binning.pipeline.PipelineController
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.pipeline.PipelineState
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectRef
import java.io.File
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

/** Feeds the Analyze screen from the pipeline, the open project's database and the model download state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeViewModel(
    private val pipeline: PipelineController,
    private val currentProject: StateFlow<OpenProject?>,
    modelState: Flow<DownloadState>,
    /** Lifts a Stop and analyzes a project's waiting items in the foreground service (AppGraph.resumeAnalysis). */
    private val resumeAnalysis: (ProjectRef) -> Unit,
    /** Stops the run and remembers it for the open project (AppGraph.stopAnalysis). */
    private val stopAnalysis: () -> Unit,
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
        liveState(state, project?.ref)
    }

    /** Re-checks thumbnails when the current item moves on: they are written while it is prepared. */
    private val thumbTick = pipeline.state.map { it.currentItemId to it.phase }.distinctUntilChanged()

    /** Cards together with the project they were read from, so lookups never mix two projects. */
    private class ProjectCards(val project: OpenProject?, val cards: List<CardItem>)

    private val cards: Flow<ProjectCards> = combine(projectItems, thumbTick) { pi, _ -> pi }
        .mapLatest { pi ->
            if (pi == null) {
                ProjectCards(null, emptyList())
            } else {
                val thumbs = ProjectFolders.thumbsDir(File(pi.project.ref.path))
                ProjectCards(pi.project, pi.items.map { cardOf(it, File(thumbs, "${it.id}.jpg").takeIf(File::isFile)) })
            }
        }
        .flowOn(Dispatchers.IO)

    private class Focused(val project: OpenProject?, val carousel: CarouselState)

    private val focused: StateFlow<Focused> = combine(
        cards,
        live.map { it?.currentItemId }.distinctUntilChanged(),
    ) { pc, liveId -> Focused(pc.project, CarouselState(pc.cards, focusIndex(pc.cards, liveId), pc.project?.ref?.path)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Focused(null, CarouselState(emptyList(), -1)))

    val carousel: StateFlow<CarouselState> = focused
        .map { it.carousel }
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
        val statuses = pi?.items.orEmpty().map { it.status }
        val (text, tone) = statusOf(pi != null, statuses, state, carousel.focus)
        val analyzed = statuses.count { it.isAnalyzed }
        val failed = statuses.count { it == ItemStatus.Failed || it == ItemStatus.Missing }
        HeaderState(
            model = model,
            projectName = pi?.project?.ref?.name ?: currentProject.value?.ref?.name,
            itemCount = statuses.size,
            statusText = text,
            statusTone = tone,
            countText = if (pi == null) "" else "$analyzed of ${statuses.size} analyzed" + if (failed > 0) " · $failed failed" else "",
            speedText = speedText(state),
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

    /** The saved analysis of the centre card, read from the project the card came from. */
    private val stored: Flow<StoredAnalysis?> = focused
        .map { it.project to it.carousel.focus }
        .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second }
        .mapLatest { (project, card) ->
            if (project == null || card == null || !card.isAnalyzed) return@mapLatest null
            try {
                val analysis = project.db.analyses().get(card.id) ?: return@mapLatest null
                val actions = runCatching {
                    json.decodeFromString(ListSerializer(ActionEntry.serializer()), analysis.actionsJson)
                }.getOrDefault(emptyList())
                StoredAnalysis(card.id, analysis.description, actions, project.db.analyses().tagsFor(card.id))
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, EmptyOutput)

    fun stop() = stopAnalysis()

    /** Pause while running; Resume continues a paused run or starts a new one for the open project. */
    fun togglePause() {
        val project = currentProject.value ?: return
        val state = pipeline.state.value
        val live = state.project == project.ref
        when {
            live && state.phase == PipelinePhase.Paused -> pipeline.resume()
            live && state.phase.isRunning -> pipeline.pause()
            else -> resumeAnalysis(project.ref)
        }
    }

    private companion object {
        const val TAG = "AnalyzeViewModel"
        val json = Json { ignoreUnknownKeys = true }
    }
}
