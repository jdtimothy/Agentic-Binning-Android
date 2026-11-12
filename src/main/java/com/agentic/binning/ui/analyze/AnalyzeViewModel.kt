package com.agentic.binning.ui.analyze

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.model.DownloadState
import com.agentic.binning.pipeline.PipelineController
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.pipeline.PipelineState
import com.agentic.binning.pipeline.storedSortNotice
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.SortingModelChoice
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Feeds the Analyze screen from the pipeline, the open project's database and the model download state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeViewModel(
    private val pipeline: PipelineController,
    private val currentProject: StateFlow<OpenProject?>,
    modelState: Flow<DownloadState>,
    /**
     * Lifts a Stop and analyzes a project's waiting items in the foreground service, or with none
     * categorizes its uncategorized ones (AppGraph.resumeAnalysis).
     */
    private val resumeAnalysis: (ProjectRef) -> Unit,
    /** Stops the run and remembers it for the open project (AppGraph.stopAnalysis). */
    private val stopAnalysis: () -> Unit,
    /** The user's max frames per clip, which sets how many frames a clip's card shows. */
    maxFramesPerClip: Flow<Int>,
    /** The sorting model from Settings, named in the model chip while categorizing. */
    sortingModel: Flow<SortingModelChoice>,
) : ViewModel() {
    private class ProjectItems(val project: OpenProject, val items: List<ItemEntity>, val categoryNames: Map<Long, String> = emptyMap())

    private val projectItems: StateFlow<ProjectItems?> = currentProject
        .flatMapLatest { project ->
            if (project == null) {
                flowOf(null)
            } else {
                combine(project.db.items().observeAll(), project.db.sort().observeCategories()) { items, categories ->
                    ProjectItems(project, items, categories.associate { it.id to it.name })
                }
                    // The database closes on a project switch, possibly before this collector moves on.
                    .catch { e -> Log.w(TAG, "Item list of ${project.ref.name} stopped", e) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Stop remembered in the open project's database (AnalysisLauncher); false when none or unreadable. */
    private val stoppedByUser: Flow<Boolean> = currentProject
        .flatMapLatest { project ->
            if (project == null) {
                flowOf(false)
            } else {
                project.db.meta().observeStoppedByUser()
                    .map { it == true }
                    // The database closes on a project switch, possibly before this collector moves on.
                    .catch { e ->
                        Log.w(TAG, "Stop flag of ${project.ref.name} stopped", e)
                        emit(false)
                    }
            }
        }
        .distinctUntilChanged()

    /** Analyzed items of the open project still waiting for a category; 0 when none or unreadable. */
    private val uncategorized: Flow<Int> = currentProject
        .flatMapLatest { project ->
            if (project == null) {
                flowOf(0)
            } else {
                project.db.sort().observeUncategorizedCount()
                    // The database closes on a project switch, possibly before this collector moves on.
                    .catch { e ->
                        Log.w(TAG, "Uncategorized count of ${project.ref.name} stopped", e)
                        emit(0)
                    }
            }
        }
        .distinctUntilChanged()

    /** Why the open project's last sort run left items uncategorized, read from its run records. */
    private val storedNotice: Flow<String?> = currentProject
        .flatMapLatest { project ->
            if (project == null) {
                flowOf(null)
            } else {
                project.db.sort().observeRecentRuns()
                    .map { storedSortNotice(it) }
                    // The database closes on a project switch, possibly before this collector moves on.
                    .catch { e ->
                        Log.w(TAG, "Sort runs of ${project.ref.name} stopped", e)
                        emit(null)
                    }
            }
        }
        .distinctUntilChanged()

    /** The pipeline state if it belongs to the open project, else null. */
    private val live: Flow<PipelineState?> = combine(pipeline.state, currentProject) { state, project ->
        liveState(state, project?.ref)
    }

    /** Re-checks thumbnails when the current item moves on: they are written while it is prepared. */
    private val thumbTick = pipeline.state.map { it.currentItemId to it.phase }.distinctUntilChanged()

    /** Cards together with the project they were read from, so lookups never mix two projects. */
    private class ProjectCards(val project: OpenProject?, val cards: List<CardItem>)

    private val cards: Flow<ProjectCards> = combine(projectItems, thumbTick, maxFramesPerClip.distinctUntilChanged()) { pi, _, cap -> pi to cap }
        .mapLatest { (pi, cap) ->
            if (pi == null) {
                ProjectCards(null, emptyList())
            } else {
                val thumbs = ProjectFolders.thumbsDir(File(pi.project.ref.path))
                ProjectCards(
                    pi.project,
                    pi.items.map { cardOf(it, File(thumbs, "${it.id}.jpg").takeIf(File::isFile), cap, it.categoryId?.let(pi.categoryNames::get)) },
                )
            }
        }
        .flowOn(Dispatchers.IO)

    private class Focused(val project: OpenProject?, val carousel: CarouselState)

    private data class ReviewSelection(val projectPath: String, val itemId: Long)
    private val reviewSelection = MutableStateFlow<ReviewSelection?>(null)

    private val focused: StateFlow<Focused> = combine(
        cards,
        live.map { it?.currentItemId }.distinctUntilChanged(),
        reviewSelection,
    ) { pc, liveId, selection ->
        val selectedId = selection?.takeIf { it.projectPath == pc.project?.ref?.path }?.itemId
        Focused(pc.project, CarouselState(pc.cards, focusIndex(pc.cards, liveId, selectedId), pc.project?.ref?.path,
            reviewing = selectedId != null && pc.cards.any { it.id == selectedId }))
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Focused(null, CarouselState(emptyList(), -1)))

    val carousel: StateFlow<CarouselState> = focused
        .map { it.carousel }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CarouselState(emptyList(), -1))

    val progress: StateFlow<CurrentProgress> = live
        .map { progressOf(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NoProgress)

    /** The vision model's download state and the sorting model choice (null until read). */
    private val models: Flow<Pair<DownloadState, SortingModelChoice?>> = combine(
        modelState.flowOn(Dispatchers.IO),
        sortingModel.map<SortingModelChoice, SortingModelChoice?> { it }.onStart { emit(null) }.distinctUntilChanged(),
        ::Pair,
    )

    val header: StateFlow<HeaderState> = combine(
        models,
        projectItems,
        live,
        carousel,
        combine(stoppedByUser, uncategorized, ::Pair),
    ) { (vision, sorting), pi, state, carousel, (stopped, uncategorized) ->
        val statuses = pi?.items.orEmpty().map { it.status }
        val liveCard = carousel.cards.firstOrNull { it.id == state?.currentItemId }
        val (text, tone) = statusOf(pi != null, statuses, state, liveCard, stopped, uncategorized)
        HeaderState(
            model = modelChipOf(state, vision, sorting),
            projectName = pi?.project?.ref?.name ?: currentProject.value?.ref?.name,
            itemCount = statuses.size,
            statusText = text,
            statusTone = tone,
            countText = countTextOf(pi != null, statuses, state),
            speedText = speedText(state),
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, HeaderState(modelChipOf(null, null, null), null, 0, "", StatusTone.Quiet, ""))

    val controls: StateFlow<ControlsState> = combine(projectItems, live, uncategorized) { pi, state, uncategorized ->
        controlsOf(
            state,
            hasProject = pi != null,
            hasPending = pi?.items.orEmpty().any { !it.status.isDone },
            mediaCount = pi?.items?.size ?: 0,
            uncategorized = uncategorized,
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
        combine(uncategorized, storedNotice, ::Pair),
    ) { (hasProject, empty), card, state, stored, (uncategorized, storedNotice) ->
        outputOf(hasProject, empty, card, state, stored, uncategorized, storedNotice)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, EmptyOutput)

    /** Selects only cards from the currently displayed project; does not pause the pipeline. */
    fun selectCard(itemId: Long) {
        val state = carousel.value
        val path = state.projectPath ?: return
        if (state.cards.any { it.id == itemId }) reviewSelection.value = ReviewSelection(path, itemId)
    }

    fun followAnalysis() { reviewSelection.value = null }

    fun stop() = stopAnalysis()

    /** Pause while running; Resume continues a paused run or starts a new one for the open project. */
    fun togglePause() {
        val project = currentProject.value ?: return
        val state = pipeline.state.value
        val live = state.project == project.ref
        when {
            live && state.phase == PipelinePhase.Paused -> pipeline.resume()
            live && state.phase.isRunning -> if (state.phase.canPause) pipeline.pause()
            else -> resumeAnalysis(project.ref)
        }
    }

    private companion object {
        const val TAG = "AnalyzeViewModel"
        val json = Json { ignoreUnknownKeys = true }
    }
}
