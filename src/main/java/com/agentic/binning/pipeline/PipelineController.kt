package com.agentic.binning.pipeline

import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.project.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PipelinePhase {
    Idle, LoadingModel, Preparing, Describing, Paused, Stopped, Releasing;

    /** True while a run is in progress (including Paused); the service, wake lock and keep-screen-on follow this. */
    val isRunning: Boolean get() = this != Idle && this != Stopped
}

data class PipelineState(
    val phase: PipelinePhase,
    val project: ProjectRef?,
    val currentItemId: Long?,
    val partial: PartialAnalysis?,
    val lastProfile: VisionProfile?,
    /** Items still to analyze, including the current one. */
    val pending: Int,
    val analyzed: Int,
    val total: Int,
    /** Set while the vision model is being downloaded (phase [PipelinePhase.LoadingModel]). */
    val downloadFraction: Float? = null,
    /** Why the last run ended in [PipelinePhase.Stopped] without being asked to, e.g. the model failed to load. */
    val error: String? = null,
)

/**
 * Runs analysis batches for one project at a time in a single coroutine loop:
 * reset interrupted items → load the model (downloading it first if needed) → analyze each Pending
 * item → release the model → [BatchCompleteHandler] → Idle.
 *
 * [pause] lets the current item finish and holds with the model loaded. [stop] cancels the current
 * generation, returns that item to Pending, releases the model and ends in Stopped; [resume] after
 * a stop starts a new run.
 */
class PipelineController(
    private val scope: CoroutineScope,
    private val engineFactory: () -> AnalysisEngine,
    private val model: VisionModel,
    private val store: AnalysisStore,
    private val batchComplete: BatchCompleteHandler,
    /** Makes sure the model's weights are on the phone, reporting download progress (0..1). */
    private val ensureModel: suspend (onProgress: (Float) -> Unit) -> Unit = {},
) {
    private enum class Control { Run, Pause, Stop }

    private val _state = MutableStateFlow(
        PipelineState(PipelinePhase.Idle, null, null, null, null, pending = 0, analyzed = 0, total = 0),
    )
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val control = MutableStateFlow(Control.Run)
    private val lock = Any()
    private var job: Job? = null
    private var lastProject: ProjectRef? = null

    /** Bumped by every [start]; state updates from an older run are dropped. */
    @Volatile
    private var generation = 0

    /**
     * Starts a run for [project]. While a run for the same project is in progress this only clears a
     * pause; a run for another project is left alone (stop it first).
     */
    fun start(project: ProjectRef) {
        synchronized(lock) {
            val previous = job
            if (previous?.isActive == true) {
                if (project == lastProject) control.value = Control.Run
                return
            }
            lastProject = project
            control.value = Control.Run
            val gen = ++generation
            // Running at once, so an observer (the service) never sees the old Idle or Stopped.
            _state.update {
                val sameProject = it.project == project
                PipelineState(
                    phase = PipelinePhase.LoadingModel,
                    project = project,
                    currentItemId = null,
                    partial = null,
                    lastProfile = if (sameProject) it.lastProfile else null,
                    pending = if (sameProject) it.pending else 0,
                    analyzed = if (sameProject) it.analyzed else 0,
                    total = if (sameProject) it.total else 0,
                )
            }
            // A stopped run may still be cleaning up; let it finish first.
            job = scope.launch {
                previous?.join()
                run(project, gen)
            }
        }
    }

    /** Holds after the current item; the model stays loaded. */
    fun pause() {
        synchronized(lock) {
            if (job?.isActive == true && control.value == Control.Run) control.value = Control.Pause
        }
    }

    /** Continues a paused run, or starts a new run for the last project after a stop. */
    fun resume() {
        val project = synchronized(lock) {
            if (job?.isActive == true) {
                if (control.value == Control.Pause) control.value = Control.Run
                return
            }
            lastProject
        } ?: return
        start(project)
    }

    /** Cancels the current generation; the run then cleans up and ends in Stopped. */
    fun stop() {
        val running = synchronized(lock) {
            job?.takeIf { it.isActive }?.also { control.value = Control.Stop }
        } ?: return
        scope.launch { model.cancel() }
        running.cancel()
    }

    private suspend fun run(project: ProjectRef, gen: Int) {
        var current: ItemEntity? = null
        try {
            store.resetStatus(ItemStatus.Analyzing, ItemStatus.Pending)
            refreshCounts(gen)
            if (store.nextPending() == null) {
                update(gen) { it.copy(phase = PipelinePhase.Idle) }
                return
            }

            update(gen) { it.copy(phase = PipelinePhase.LoadingModel) }
            ensureModel { fraction -> update(gen) { it.copy(downloadFraction = fraction) } }
            update(gen) { it.copy(downloadFraction = null) }
            model.load()
            val engine = engineFactory()

            while (true) {
                if (control.value == Control.Pause) {
                    update(gen) { it.copy(phase = PipelinePhase.Paused, currentItemId = null) }
                    control.first { it != Control.Pause }
                }
                if (control.value == Control.Stop) throw CancellationException("Stopped")
                currentCoroutineContext().ensureActive()

                val item = store.nextPending() ?: break
                current = item
                store.setStatus(item.id, ItemStatus.Analyzing)
                update(gen) { it.copy(phase = PipelinePhase.Preparing, currentItemId = item.id, partial = null) }
                val outcome = engine.analyze(
                    item,
                    onDescribing = { update(gen) { it.copy(phase = PipelinePhase.Describing) } },
                ) { partial -> update(gen) { it.copy(partial = partial) } }
                // A user-stopped reply: the item goes back to Pending in the cleanup below.
                if (outcome == ItemOutcome.Cancelled) throw CancellationException("Generation was stopped")
                current = null
                update(gen) { it.copy(lastProfile = engine.lastProfile ?: it.lastProfile) }
                refreshCounts(gen)
            }

            // Release always comes before the handler: only one model may be loaded at a time.
            update(gen) { it.copy(phase = PipelinePhase.Releasing, currentItemId = null) }
            model.release()
            batchComplete.onBatchComplete(project)
            update(gen) { it.copy(phase = PipelinePhase.Idle) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { endStopped(gen, current, error = null) }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { endStopped(gen, current, error = e.message ?: e.toString()) }
        }
    }

    /** Puts [current] back to Pending, releases the model and ends in Stopped. Never throws. */
    private suspend fun endStopped(gen: Int, current: ItemEntity?, error: String?) {
        current?.let { runCatching { store.setStatus(it.id, ItemStatus.Pending) } }
        runCatching { model.release() }
        runCatching { refreshCounts(gen) }
        update(gen) {
            it.copy(
                phase = PipelinePhase.Stopped,
                currentItemId = null,
                downloadFraction = null,
                error = error,
            )
        }
    }

    private suspend fun refreshCounts(gen: Int) {
        val counts = store.counts()
        update(gen) { it.copy(pending = counts.pending, analyzed = counts.analyzed, total = counts.total) }
    }

    private inline fun update(gen: Int, crossinline change: (PipelineState) -> PipelineState) {
        _state.update { if (gen == generation) change(it) else it }
    }
}
