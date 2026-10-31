package com.agentic.binning.pipeline

import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.db.ItemEntity
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
import kotlinx.coroutines.joinAll
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
    /** Failed and Missing items: done for progress purposes (`analyzed + failed` reaches `total`). */
    val failed: Int = 0,
    /** Frames extracted so far from the current video, of [framesTotal] (0 of 0 until the first one). */
    val framesReady: Int = 0,
    val framesTotal: Int = 0,
)

/** Where the pipeline reports problems it recovers from; the app logs them. */
fun interface PipelineLog {
    fun warn(message: String, error: Throwable?)

    companion object {
        val None = PipelineLog { _, _ -> }
    }
}

/**
 * Runs analysis batches for one project at a time in a single coroutine loop:
 * reset interrupted items → load the model (downloading it first if needed) → analyze each Pending
 * item → release the model → [BatchCompleteHandler] → (more Pending items? another batch) → Idle.
 *
 * A failure that is not the item's fault ([StopRunException], or the model failing to load) ends
 * the run in Stopped with an error and the item back in Pending, as do [MAX_FAILURES_IN_A_ROW]
 * Failed items in a row. An item the process died on [FAIL_INTERRUPTED_AFTER] times is failed.
 *
 * [pause] lets the current item finish and holds with the model loaded. [stop] cancels the current
 * generation, returns that item to Pending, releases the model and ends in Stopped; [resume] after
 * a stop starts a new run. [start] for the project already running asks for another pass, so items
 * registered while a batch is being released are not left behind.
 */
class PipelineController(
    private val scope: CoroutineScope,
    private val engineFactory: () -> AnalysisEngine,
    private val model: VisionModel,
    private val store: AnalysisStore,
    private val batchComplete: BatchCompleteHandler,
    /** Makes sure the model's weights are on the phone, reporting download progress (0..1). */
    private val ensureModel: suspend (onProgress: (Float) -> Unit) -> Unit = {},
    private val log: PipelineLog = PipelineLog.None,
    /** Whether [ProjectRef] is the project whose database [store] currently works on; [start] refuses any other. */
    private val isCurrent: (ProjectRef) -> Boolean = { true },
) {
    private enum class Control { Run, Pause, Stop }

    private val _state = MutableStateFlow(idleState(project = null))
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val control = MutableStateFlow(Control.Run)
    private val lock = Any()

    // Guarded by [lock].
    private var job: Job? = null
    private val jobs = mutableSetOf<Job>()
    private var lastProject: ProjectRef? = null

    /** Set while [stopAndReset] winds the pipeline down; every [start] is refused meanwhile. */
    private var closed = false

    /** True until the current run has decided to end; until then a [start] is absorbed as a rerun. */
    private var acceptingReruns = false
    private var rerunRequested = false

    /** Bumped by every [start]; state updates from an older run are dropped. */
    @Volatile
    private var generation = 0

    /**
     * Starts a run for [project]. While a run for the same project is in progress this asks it to look
     * for Pending items once more before it ends (it does not lift a pause); a run for another project
     * is left alone (stop it first). Refused while [stopAndReset] runs and for a project that is not
     * the open one ([isCurrent]), so a stale start cannot analyze another project's database.
     */
    fun start(project: ProjectRef) {
        synchronized(lock) {
            if (closed || !isCurrent(project)) {
                log.warn("Analysis start for ${project.name} refused: it is not the open project", null)
                return
            }
            val previous = job
            if (previous?.isActive == true && acceptingReruns) {
                if (project == lastProject) rerunRequested = true
                return
            }
            lastProject = project
            control.value = Control.Run
            acceptingReruns = true
            rerunRequested = false
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
                    failed = if (sameProject) it.failed else 0,
                )
            }
            val launched = scope.launch {
                // A stopped run may still be cleaning up (releasing the model); never overlap it.
                withContext(NonCancellable) { previous?.join() }
                run(project, gen)
            }
            jobs += launched
            job = launched
            // Covers a run cancelled before it got going: run()'s own cleanup never executed.
            launched.invokeOnCompletion { cause ->
                synchronized(lock) { jobs -= launched }
                if (cause != null) {
                    update(gen) {
                        if (it.phase.isRunning) it.copy(phase = PipelinePhase.Stopped, currentItemId = null, downloadFraction = null) else it
                    }
                }
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

    /**
     * Stops, waits until every run has finished cleaning up (the model is released and nothing touches
     * the store any more), then forgets the project so [resume] cannot start it against another
     * project's database. Call before the open project changes. Starts are refused until it returns.
     */
    suspend fun stopAndReset() {
        synchronized(lock) {
            closed = true
            lastProject = null
        }
        try {
            stop()
            synchronized(lock) { jobs.toList() }.joinAll()
        } finally {
            synchronized(lock) {
                generation++
                _state.value = idleState(project = null)
                closed = false
            }
        }
    }

    private suspend fun run(project: ProjectRef, gen: Int) {
        var current: ItemEntity? = null
        try {
            currentCoroutineContext().ensureActive()
            store.recoverInterrupted(FAIL_INTERRUPTED_AFTER)
            refreshCounts(gen)
            while (true) {
                if (store.nextPending() != null) {
                    runBatch(project, gen) { current = it }
                    current = null
                }
                // Media registered meanwhile asks for another pass; decide under the lock so a later
                // start() either lands here or starts a fresh run.
                val again = synchronized(lock) {
                    if (rerunRequested) {
                        rerunRequested = false
                        true
                    } else {
                        closeToReruns(gen)
                        false
                    }
                }
                if (!again) break
            }
            update(gen) { it.copy(phase = PipelinePhase.Idle, currentItemId = null) }
        } catch (e: CancellationException) {
            closeToReruns(gen)
            withContext(NonCancellable) { endStopped(gen, current, error = null) }
            throw e
        } catch (t: Throwable) {
            closeToReruns(gen)
            log.warn("Analysis run for ${project.name} failed", t)
            withContext(NonCancellable) { endStopped(gen, current, error = t.message ?: t.toString()) }
            if (t !is Exception) throw t
        }
    }

    /** Loads the model, analyzes every Pending item, releases the model and calls the handler. */
    private suspend fun runBatch(project: ProjectRef, gen: Int, setCurrent: (ItemEntity?) -> Unit) {
        update(gen) { it.copy(phase = PipelinePhase.LoadingModel) }
        ensureModel { fraction -> update(gen) { it.copy(downloadFraction = fraction) } }
        update(gen) { it.copy(downloadFraction = null) }
        model.load()
        val engine = engineFactory()
        var failuresInARow = 0

        while (true) {
            if (control.value == Control.Pause) {
                update(gen) { it.copy(phase = PipelinePhase.Paused, currentItemId = null) }
                control.first { it != Control.Pause }
            }
            if (control.value == Control.Stop) throw CancellationException("Stopped")
            currentCoroutineContext().ensureActive()

            val item = store.nextPending() ?: break
            setCurrent(item)
            store.markAnalyzing(item.id)
            update(gen) {
                it.copy(phase = PipelinePhase.Preparing, currentItemId = item.id, partial = null, framesReady = 0, framesTotal = 0)
            }
            val outcome = engine.analyze(
                item,
                onDescribing = { update(gen) { it.copy(phase = PipelinePhase.Describing) } },
                onFrame = { ready, total -> update(gen) { it.copy(framesReady = ready, framesTotal = total) } },
            ) { partial -> update(gen) { it.copy(partial = partial) } }
            // A user-stopped reply: the item goes back to Pending in the cleanup.
            if (outcome == ItemOutcome.Cancelled) throw CancellationException("Generation was stopped")
            setCurrent(null)
            update(gen) { it.copy(lastProfile = engine.lastProfile ?: it.lastProfile) }
            refreshCounts(gen)
            when (outcome) {
                is ItemOutcome.Analyzed -> failuresInARow = 0
                is ItemOutcome.Failed -> if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                    // Something is wrong beyond these files; stop rather than fail the whole queue.
                    throw StopRunException("Analysis stopped after repeated failures: ${FailureReasons.describe(outcome.reason)}")
                }
                else -> Unit // A missing file says nothing about the rest.
            }
        }

        // Release always comes before the handler: only one model may be loaded at a time.
        update(gen) { it.copy(phase = PipelinePhase.Releasing, currentItemId = null) }
        model.release()
        batchComplete.onBatchComplete(project)
    }

    /** From here on a [start] launches a fresh run (which waits for this one) instead of being absorbed. */
    private fun closeToReruns(gen: Int) {
        synchronized(lock) { if (gen == generation) acceptingReruns = false }
    }

    /** Puts [current] back to Pending, releases the model and ends in Stopped. Never throws. */
    private suspend fun endStopped(gen: Int, current: ItemEntity?, error: String?) {
        current?.let { item ->
            try {
                store.returnToPending(item.id)
            } catch (t: Throwable) {
                log.warn("Could not return item ${item.id} to Pending", t)
            }
        }
        try {
            model.release()
        } catch (t: Throwable) {
            log.warn("Could not release the vision model", t)
        }
        try {
            refreshCounts(gen)
        } catch (t: Throwable) {
            log.warn("Could not refresh item counts", t)
        }
        update(gen) {
            it.copy(phase = PipelinePhase.Stopped, currentItemId = null, downloadFraction = null, error = error)
        }
    }

    private suspend fun refreshCounts(gen: Int) {
        val c = store.counts()
        update(gen) { it.copy(pending = c.pending, analyzed = c.analyzed, total = c.total, failed = c.failed) }
    }

    private inline fun update(gen: Int, crossinline change: (PipelineState) -> PipelineState) {
        _state.update { if (gen == generation) change(it) else it }
    }

    companion object {
        /** Attempts after which an item left Analyzing by a dead process is failed instead of retried. */
        const val FAIL_INTERRUPTED_AFTER = 2

        /** Failed items in a row that stop the run. */
        const val MAX_FAILURES_IN_A_ROW = 3

        private fun idleState(project: ProjectRef?) =
            PipelineState(PipelinePhase.Idle, project, null, null, null, pending = 0, analyzed = 0, total = 0)
    }
}
