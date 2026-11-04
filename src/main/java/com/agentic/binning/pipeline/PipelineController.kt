package com.agentic.binning.pipeline

import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.describe
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.move.MoveControl
import com.agentic.binning.move.MoveOutcome
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.projectmedia.RESORT_BUSY
import com.agentic.binning.projectmedia.RESORT_NOT_OPEN
import com.agentic.binning.projectmedia.ResortRequest
import com.agentic.binning.projectmedia.ResortStart
import com.agentic.binning.settings.FRAME_CAP_OPTIONS
import com.agentic.binning.settings.SortingModelChoice
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
    Idle, LoadingModel, Preparing, Describing, Paused, Stopped, Releasing,

    /** The vision model is released and the sorting model files analyzed items into categories. */
    Categorizing,

    /** Bin Files or a Save is moving files (Plan 3); no model is loaded. */
    Moving,

    /** A move paused between files (B2); it still owns the project, so nothing else starts. */
    MovingPaused;

    /** True while a run is in progress (including Paused); the service, wake lock and keep-screen-on follow this. */
    val isRunning: Boolean get() = this != Idle && this != Stopped

    /**
     * Pause holds the vision model between items, or a move between files; categorizing has nothing
     * to hold, so it can't pause.
     */
    val canPause: Boolean get() = isRunning && this != Paused && this != Categorizing && this != MovingPaused

    /** Files are moving, or a move is paused: other jobs and edits wait (Q11). */
    val isMoving: Boolean get() = this == Moving || this == MovingPaused
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
    /** Items categorized (or tried) so far of [sortTotal], while [PipelinePhase.Categorizing]. */
    val sortDone: Int = 0,
    val sortTotal: Int = 0,
    /**
     * Why the last categorization left items uncategorized (the reason's sentence, or a Partial
     * message); cleared once a later one finishes them all or finds nothing to do. Kept across a Stop.
     */
    val sortNotice: String? = null,
    /** The move in progress (phases [PipelinePhase.Moving] and [PipelinePhase.MovingPaused]); null otherwise. */
    val moveProgress: MoveProgress? = null,
    /** How the last move ended (or why it didn't start), for the report; kept until the next move. */
    val moveOutcome: MoveOutcome? = null,
    /** Counts finished moves: a change means one just ended, even one too quick to be seen running. */
    val moveEnded: Int = 0,
    /** Files that had moved when a resumed move picked up (B3); null for a move started here. */
    val moveResumedFrom: Int? = null,
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
 * item → release the model → (more Pending items? another batch) → categorize → Idle.
 *
 * Categorizing runs once per run, after the last batch, over every Analyzed item without a category
 * (also leftovers from earlier runs, so a missing key or network is retried by the next run). It
 * never sets [PipelineState.error]; why items stay uncategorized goes to [PipelineState.sortNotice].
 * Each batch and the categorization hold the [ModelArbiter], so only one model is ever loaded.
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
    /** Makes the engine for a batch that reads at most this many frames per clip. */
    private val engineFactory: (maxFrames: Int) -> AnalysisEngine,
    private val model: VisionModel,
    private val store: AnalysisStore,
    private val categorizer: Categorizer,
    /** Shared with every other job that loads a model; one job at a time. */
    private val arbiter: ModelArbiter = ModelArbiter(),
    /** Makes sure the model's weights are on the phone, reporting download progress (0..1). */
    private val ensureModel: suspend (onProgress: (Float) -> Unit) -> Unit = {},
    private val log: PipelineLog = PipelineLog.None,
    /** Whether [ProjectRef] is the project whose database [store] currently works on; [start] refuses any other. */
    private val isCurrent: (ProjectRef) -> Boolean = { true },
    /** The user's max frames per clip; read once at the start of each batch, so a change applies to the next one. */
    private val maxFramesPerClip: suspend () -> Int = { FRAME_CAP_OPTIONS.first() },
    /** Makes the unlocked Sorted items waiting again before a Re-sort sorts them; returns how many. */
    private val prepareResort: suspend (keepEdits: Boolean) -> Int = { 0 },
    /** Null if [SortingModelChoice] can run now (a key is saved, the model is downloaded); otherwise why not. */
    private val sortingAvailable: suspend (SortingModelChoice) -> String? = { null },
    /** Moves files for Bin Files (Plan 3); one job at a time, under [JobKind.Binning]. */
    private val mover: FileMover = FileMover { _, _, _, _ -> MoveOutcome.Done },
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

    /** Pause and Stop of the move in progress; null when no move runs. Guarded by [lock]. */
    private var moveControl: MoveControl? = null

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
    fun start(project: ProjectRef) = launchRun(project, analyze = true)

    /**
     * Starts a run that skips analysis and only categorizes [project]'s uncategorized items. Any run
     * still active for [project] (analyzing, or already categorizing) categorizes anyway, so this adds
     * nothing to it: a second tap never sends the items a run left out a second time. Otherwise the
     * same rules as [start] apply (refused for a project that is not open, and while [stopAndReset]
     * runs; a stopped run still winding down is waited for).
     */
    fun categorizeNow(project: ProjectRef) = launchRun(project, analyze = false)

    /**
     * Re-sorts [project] with [request]'s model: refused while a run is in progress (analyzing, paused
     * or categorizing; never queued) and for a project that is not the open one. Otherwise it starts a
     * categorize-only run which, holding the [ModelArbiter]'s Sorting job, first checks that the model
     * can run ([sortingAvailable]; if not, nothing is cleared and the reason becomes the sort notice),
     * then clears the unlocked assignments ([prepareResort]) and sorts with that model, never
     * Settings'. Stop and a project switch end it as any run; cleared items then stay waiting.
     */
    fun resort(project: ProjectRef, request: ResortRequest): ResortStart {
        synchronized(lock) {
            if (closed || !isCurrent(project)) return ResortStart.Refused(RESORT_NOT_OPEN)
            if (_state.value.phase.isRunning) return ResortStart.Refused(RESORT_BUSY)
            launchRun(project, analyze = false, resort = request)
        }
        return ResortStart.Started
    }

    /**
     * Bin Files for [project] (Plan 3): refused while any run is in progress (never queued) and for a
     * project that is not open. Runs under [JobKind.Binning]; [pause], [resume] and [stop] then act
     * between files (B2).
     */
    fun binFiles(project: ProjectRef): MoveStart = launchMove(project, MoveJob.BinFiles, paused = false)

    /** Runs the plan a Save wrote (Plan 3 Task 14); refused like [binFiles]. */
    fun saveMoves(project: ProjectRef, runId: Long): MoveStart = launchMove(project, MoveJob.Save(runId), paused = false)

    /** Finishes the open move run [runId] (B3), paused if it was paused; refused like [binFiles]. */
    fun resumeMove(project: ProjectRef, runId: Long, paused: Boolean): MoveStart = launchMove(project, MoveJob.Resume(runId), paused)

    private fun launchMove(project: ProjectRef, job: MoveJob, paused: Boolean): MoveStart {
        synchronized(lock) {
            if (closed || !isCurrent(project)) return MoveStart.Refused(MOVE_NOT_OPEN)
            if (_state.value.phase.isRunning) return MoveStart.Refused(MOVE_BUSY)
            val previous = job()
            lastProject = project
            acceptingReruns = false
            rerunRequested = false
            val control = MoveControl(startPaused = paused)
            moveControl = control
            val gen = ++generation
            _state.update {
                val sameProject = it.project == project
                PipelineState(
                    phase = PipelinePhase.Moving,
                    project = project,
                    currentItemId = null,
                    partial = null,
                    lastProfile = if (sameProject) it.lastProfile else null,
                    pending = if (sameProject) it.pending else 0,
                    analyzed = if (sameProject) it.analyzed else 0,
                    total = if (sameProject) it.total else 0,
                    failed = if (sameProject) it.failed else 0,
                    sortNotice = if (sameProject) it.sortNotice else null,
                    moveEnded = it.moveEnded,
                )
            }
            val launched = scope.launch {
                withContext(NonCancellable) { previous?.join() }
                runMove(project, gen, job, control)
            }
            jobs += launched
            this.job = launched
            launched.invokeOnCompletion { cause ->
                synchronized(lock) {
                    jobs -= launched
                    if (moveControl === control) moveControl = null
                }
                if (cause != null) {
                    update(gen) { if (it.phase.isRunning) it.copy(phase = PipelinePhase.Stopped, moveProgress = null) else it }
                }
            }
        }
        return MoveStart.Started
    }

    private fun job(): Job? = job

    private suspend fun runMove(project: ProjectRef, gen: Int, job: MoveJob, control: MoveControl) {
        val outcome = try {
            arbiter.withJob(JobKind.Binning) {
                mover.run(project, job, control) { progress ->
                    update(gen) {
                        it.copy(
                            phase = if (progress.paused) PipelinePhase.MovingPaused else PipelinePhase.Moving,
                            moveProgress = progress,
                            moveResumedFrom = it.moveResumedFrom ?: progress.done.takeIf { job is MoveJob.Resume },
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Moving files for ${project.name} failed", e)
            MoveOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        synchronized(lock) { if (moveControl === control) moveControl = null }
        update(gen) { it.copy(phase = PipelinePhase.Idle, moveProgress = null, moveOutcome = outcome, moveEnded = it.moveEnded + 1, moveResumedFrom = null) }
    }

    private fun launchRun(project: ProjectRef, analyze: Boolean, resort: ResortRequest? = null) {
        synchronized(lock) {
            if (moveControl != null) {
                // Q11: nothing starts while files move, and nothing queues behind the move either.
                log.warn("${if (analyze) "Analysis" else "Categorizing"} start for ${project.name} refused: files are moving", null)
                return
            }
            if (closed || !isCurrent(project)) {
                log.warn("${if (analyze) "Analysis" else "Categorizing"} start for ${project.name} refused: it is not the open project", null)
                return
            }
            val previous = job
            // Every run for this project categorizes (or is categorizing): a second categorize-only start
            // (a Resume double-tap) would only send the items it left out to the model a second time.
            // A Re-sort is never absorbed: [resort] refused it if a run was in progress, so [previous]
            // is a stopped run still cleaning up, waited for below.
            if (!analyze && resort == null && previous?.isActive == true && project == lastProject) return
            if (previous?.isActive == true && acceptingReruns) {
                // A rerun only matters for analysis: the run categorizes at its end either way.
                if (project == lastProject && analyze) rerunRequested = true
                return
            }
            lastProject = project
            control.value = Control.Run
            // A categorize-only run takes no reruns: a start meanwhile launches a fresh run after it.
            acceptingReruns = analyze
            rerunRequested = false
            val gen = ++generation
            // Running at once, so an observer (the service) never sees the old Idle or Stopped.
            _state.update {
                val sameProject = it.project == project
                PipelineState(
                    phase = if (analyze) PipelinePhase.LoadingModel else PipelinePhase.Categorizing,
                    project = project,
                    currentItemId = null,
                    partial = null,
                    lastProfile = if (sameProject) it.lastProfile else null,
                    pending = if (sameProject) it.pending else 0,
                    analyzed = if (sameProject) it.analyzed else 0,
                    total = if (sameProject) it.total else 0,
                    failed = if (sameProject) it.failed else 0,
                    sortNotice = if (sameProject) it.sortNotice else null,
                )
            }
            val launched = scope.launch {
                // A stopped run may still be cleaning up (releasing a model); never overlap it.
                withContext(NonCancellable) { previous?.join() }
                run(project, gen, analyze, resort)
            }
            jobs += launched
            job = launched
            // Covers a run cancelled before it got going: run()'s own cleanup never executed.
            launched.invokeOnCompletion { cause ->
                synchronized(lock) { jobs -= launched }
                if (cause != null) {
                    update(gen) {
                        if (it.phase.isRunning) {
                            it.copy(phase = PipelinePhase.Stopped, currentItemId = null, downloadFraction = null, sortDone = 0, sortTotal = 0)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    /** Holds after the current item; the model stays loaded. Ignored while categorizing ([PipelinePhase.canPause]). */
    fun pause() {
        synchronized(lock) {
            moveControl?.let {
                it.pause()
                return
            }
            if (job?.isActive == true && control.value == Control.Run && _state.value.phase.canPause) control.value = Control.Pause
        }
    }

    /** Continues a paused run, or starts a new run for the last project after a stop. */
    fun resume() {
        val project = synchronized(lock) {
            moveControl?.let {
                it.resume()
                return
            }
            if (job?.isActive == true) {
                if (control.value == Control.Pause) control.value = Control.Run
                return
            }
            lastProject
        } ?: return
        start(project)
    }

    /**
     * Cancels every active run and the current generation; each run then cleans up and ends in
     * Stopped. Every run in [jobs] is for the current project ([stopAndReset] waits for them all before
     * a switch), and more than one can be active: a start during Categorizing launches a fresh run that
     * waits for the categorizing one, so cancelling only [job] would leave the sorting model running.
     */
    fun stop() {
        synchronized(lock) {
            // A move never stops half way through a file (B2): it is asked to stop between files.
            moveControl?.let {
                it.stop()
                return
            }
        }
        val running = synchronized(lock) {
            jobs.filter { it.isActive }.also { if (it.isNotEmpty()) control.value = Control.Stop }
        }
        if (running.isEmpty()) return
        scope.launch { model.cancel() }
        running.forEach { it.cancel() }
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

    private suspend fun run(project: ProjectRef, gen: Int, analyze: Boolean, resort: ResortRequest? = null) {
        var current: ItemEntity? = null
        try {
            currentCoroutineContext().ensureActive()
            if (analyze) {
                store.recoverInterrupted(FAIL_INTERRUPTED_AFTER)
                refreshCounts(gen)
                while (true) {
                    if (store.nextPending() != null) {
                        arbiter.withJob(JobKind.Analysis) {
                            try {
                                runBatch(gen) { current = it }
                            } catch (t: Throwable) {
                                // Released before the next job may load its model, not later in the cleanup.
                                withContext(NonCancellable) { releaseVision() }
                                throw t
                            }
                        }
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
            } else {
                refreshCounts(gen)
            }
            val notice = categorize(project, gen, resort)
            refreshCounts(gen)
            update(gen) { it.copy(phase = PipelinePhase.Idle, currentItemId = null, sortNotice = notice, sortDone = 0, sortTotal = 0) }
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

    /** Loads the model, analyzes every Pending item and releases the model. */
    private suspend fun runBatch(gen: Int, setCurrent: (ItemEntity?) -> Unit) {
        update(gen) { it.copy(phase = PipelinePhase.LoadingModel) }
        try {
            ensureModel { fraction -> update(gen) { it.copy(downloadFraction = fraction) } }
        } catch (e: VisionModelException) {
            log.warn("Could not fetch the vision model", e)
            throw StopRunException(FailureReasons.MODEL_NOT_DOWNLOADED, e)
        }
        update(gen) { it.copy(downloadFraction = null) }
        val frameCap = maxFramesPerClip()
        try {
            model.load(frameCap)
        } catch (e: VisionModelException) {
            log.warn("Could not load the vision model", e)
            throw StopRunException(FailureReasons.MODEL_NOT_STARTED, e)
        }
        val engine = engineFactory(frameCap)
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

        // Release always comes before categorizing: only one model may be loaded at a time.
        update(gen) { it.copy(phase = PipelinePhase.Releasing, currentItemId = null) }
        model.release()
    }

    /**
     * Sorts the uncategorized items and returns the new [PipelineState.sortNotice]. Cancellation
     * (Stop, a project switch) propagates and ends the run Stopped with the notice unchanged; any
     * other failure only leaves a notice.
     */
    private suspend fun categorize(project: ProjectRef, gen: Int, resort: ResortRequest?): String? {
        val outcome = try {
            arbiter.withJob(JobKind.Sorting) {
                // A Re-sort clears assignments only once its model is known to run (P1), and only while it
                // owns the arbiter, so nothing else sorts between the clearing and its own sorting.
                if (resort != null) {
                    sortingAvailable(resort.model)?.let { reason -> return@withJob CategorizeOutcome.Partial(0, 0, reason) }
                    prepareResort(resort.keepEdits)
                }
                categorizer.categorize(project, resort?.model) { done, total ->
                    update(gen) { it.copy(phase = PipelinePhase.Categorizing, currentItemId = null, sortDone = done, sortTotal = total) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Categorizing ${project.name} failed (${e.javaClass.simpleName}): ${e.message}", null)
            CategorizeOutcome.Partial(0, 0, "Categorizing ran into a problem; it will try again after the next run")
        }
        return when (outcome) {
            CategorizeOutcome.NothingToDo, is CategorizeOutcome.Done -> null
            is CategorizeOutcome.Partial -> outcome.message
            is CategorizeOutcome.Unavailable -> describe(outcome.reason)
        }
    }

    /** From here on a [start] launches a fresh run (which waits for this one) instead of being absorbed. */
    private fun closeToReruns(gen: Int) {
        synchronized(lock) { if (gen == generation) acceptingReruns = false }
    }

    /**
     * Puts [current] back to Pending and ends in Stopped. Never throws. The models are released
     * already: the vision model when its batch failed (still holding the [arbiter]), the sorting
     * model by the categorizer.
     */
    private suspend fun endStopped(gen: Int, current: ItemEntity?, error: String?) {
        current?.let { item ->
            try {
                store.returnToPending(item.id)
            } catch (t: Throwable) {
                log.warn("Could not return item ${item.id} to Pending", t)
            }
        }
        try {
            refreshCounts(gen)
        } catch (t: Throwable) {
            log.warn("Could not refresh item counts", t)
        }
        update(gen) {
            it.copy(phase = PipelinePhase.Stopped, currentItemId = null, downloadFraction = null, error = error, sortDone = 0, sortTotal = 0)
        }
    }

    /** Safe to call more than once. Never throws. */
    private suspend fun releaseVision() {
        try {
            model.release()
        } catch (t: Throwable) {
            log.warn("Could not release the vision model", t)
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
