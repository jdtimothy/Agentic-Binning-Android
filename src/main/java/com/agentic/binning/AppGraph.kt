package com.agentic.binning

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.AnalysisPrompt
import com.agentic.binning.analysis.AnalysisResult
import com.agentic.binning.analysis.MediaPreparer
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.AnalysisEntity
import com.agentic.binning.db.Assignment
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.CategoryInfo
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.PROJECT_DB_IDENTITY_HASHES
import com.agentic.binning.db.PROJECT_DB_VERSION
import com.agentic.binning.db.ProjectDatabase
import com.agentic.binning.db.ProjectMetaEntity
import com.agentic.binning.db.SortRunEntity
import com.agentic.binning.db.UncategorizedItem
import com.agentic.binning.db.openProjectDatabase
import com.agentic.binning.media.Candidate
import com.agentic.binning.media.ItemStoreLookup
import com.agentic.binning.media.MediaMetadataReader
import com.agentic.binning.media.MediaScanner
import com.agentic.binning.media.MediaUris
import com.agentic.binning.media.ProjectItemLookup
import com.agentic.binning.media.Registrar
import com.agentic.binning.media.RegistrationOutcome
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.RegistrationSummary
import com.agentic.binning.model.ClaudeTextModel
import com.agentic.binning.model.DownloadState
import com.agentic.binning.model.GenieXTextModel
import com.agentic.binning.model.GenieXVisionModel
import com.agentic.binning.model.ModelCatalog
import com.agentic.binning.model.ModelDownloader
import com.agentic.binning.model.ModelSpec
import com.agentic.binning.model.TextModel
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.db.RoomBinStore
import com.agentic.binning.move.BinFilesJob
import com.agentic.binning.move.MediaStoreNotifier
import com.agentic.binning.move.MoveNotifier
import com.agentic.binning.move.formatBytes
import com.agentic.binning.pipeline.SAVE_BUSY
import com.agentic.binning.projectmedia.SaveMoveHooks
import com.agentic.binning.ui.media.BinActions
import com.agentic.binning.ui.media.UnknownFileFacts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.agentic.binning.move.RealMoveFiles
import com.agentic.binning.pipeline.AnalysisEngine
import com.agentic.binning.pipeline.FileMover
import com.agentic.binning.pipeline.MOVE_BUSY
import com.agentic.binning.pipeline.MOVE_NOT_OPEN
import com.agentic.binning.pipeline.MOVE_NOT_STARTED
import com.agentic.binning.pipeline.MoveJob
import com.agentic.binning.pipeline.MoveStart
import com.agentic.binning.pipeline.AnalysisLauncher
import com.agentic.binning.pipeline.AnalysisService
import com.agentic.binning.pipeline.AnalysisStore
import com.agentic.binning.pipeline.FailureReasons
import com.agentic.binning.pipeline.ItemCounts
import com.agentic.binning.pipeline.ModelArbiter
import com.agentic.binning.pipeline.PipelineController
import com.agentic.binning.pipeline.PipelineLog
import com.agentic.binning.pipeline.Preparer
import com.agentic.binning.pipeline.ProjectRunFlags
import com.agentic.binning.pipeline.RunKind
import com.agentic.binning.pipeline.SortStore
import com.agentic.binning.pipeline.SortingCategorizer
import com.agentic.binning.project.DatabaseDecision
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.OpenRefusal
import com.agentic.binning.project.Placement
import com.agentic.binning.project.ProjectDatabaseProbe
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectListUnreadableException
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectOpenRules
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import com.agentic.binning.project.RemovedProject
import com.agentic.binning.project.requireProjectFolder
import com.agentic.binning.projectmedia.RESORT_BUSY
import com.agentic.binning.projectmedia.RESORT_NOT_OPEN
import com.agentic.binning.projectmedia.RESORT_NOT_STARTED
import com.agentic.binning.projectmedia.ResortRequest
import com.agentic.binning.projectmedia.ResortStart
import com.agentic.binning.settings.AndroidKeyCipher
import com.agentic.binning.settings.ApiKeyStore
import com.agentic.binning.settings.ApiKeys
import com.agentic.binning.settings.SettingsStore
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.sorting.SortBudgets
import com.agentic.binning.ui.settings.NEED_KEY_TEXT
import com.agentic.binning.ui.settings.specOf
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Hand-rolled dependency container; later tasks add properties. */
class AppGraph(val context: Context) {
    val settings: SettingsStore by lazy { SettingsStore(context.settingsDataStore) }

    /** The Claude API key (Keystore-encrypted); save it through here so the sorting model default follows. */
    val apiKeys: ApiKeys by lazy { ApiKeys(ApiKeyStore(context.settingsDataStore, AndroidKeyCipher()), settings) }

    val projectsRoot: File = File(Environment.getExternalStorageDirectory(), "Agentic Binning")

    val registry: ProjectRegistry by lazy {
        ProjectRegistry(File(context.filesDir, "projects.json"), projectsRoot)
    }

    private val _currentProject = MutableStateFlow<OpenProject?>(null)
    val currentProject: StateFlow<OpenProject?> = _currentProject.asStateFlow()
    private val openLock = Mutex()

    /** Outlives activities; the analysis pipeline runs here (kept alive by [AnalysisService]). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelDownloader: ModelDownloader by lazy { ModelDownloader(context) }

    val visionModel: VisionModel by lazy { GenieXVisionModel(ModelCatalog.LFM3_VL_2B) }

    private val modelPulls = mutableMapOf<String, Job>()

    /**
     * Downloads [spec] (an on-device sorting model) in the app scope, so leaving Settings doesn't
     * stop it; [ModelDownloader.state] shows its progress. Does nothing while [spec] is already pulling.
     */
    fun pullModel(spec: ModelSpec) {
        synchronized(modelPulls) {
            if (modelPulls[spec.id]?.isActive == true) return
            modelPulls[spec.id] = appScope.launch { modelDownloader.pull(spec).collect {} }
        }
    }

    /** Claude reads the saved key on every call, so a key changed in Settings applies to the next one. */
    private val claudeTextModel: TextModel by lazy {
        ClaudeTextModel(apiKey = apiKeys::load, log = { Log.d("ClaudeTextModel", it) })
    }

    private val lfmTextModel: TextModel by lazy {
        GenieXTextModel(ModelCatalog.LFM3_1_7B, nCtx = SortBudgets.LFM3_1_7B.contextTokens)
    }

    /** The sorting backend for [choice]; the categorizer loads it and releases it when done. */
    fun textModelFor(choice: SortingModelChoice): TextModel = when (choice) {
        SortingModelChoice.Claude -> claudeTextModel
        SortingModelChoice.Lfm3_1_7B -> lfmTextModel
    }

    /** Frames and padded images go to the app's own cache, never next to the user's files. */
    private val mediaPreparer by lazy { MediaPreparer(File(context.cacheDir, "frames")) }

    /** The open project's database; the pipeline is stopped before a switch closes it (see [openProject]). */
    private fun pipelineProject(): OpenProject = checkNotNull(_currentProject.value) { "No project is open" }

    private val analysisStore = object : AnalysisStore {
        override suspend fun nextPending(): ItemEntity? = pipelineProject().db.items().nextPending()

        override suspend fun setStatus(id: Long, status: ItemStatus, reason: String?) =
            pipelineProject().db.items().setStatus(id, status, reason)

        override suspend fun markAnalyzing(id: Long) = pipelineProject().db.items().markAnalyzing(id)

        override suspend fun returnToPending(id: Long) = pipelineProject().db.items().returnToPending(id)

        override suspend fun recoverInterrupted(failAfter: Int): Int =
            pipelineProject().db.items().recoverInterrupted(failAfter, FailureReasons.INTERRUPTED)

        override suspend fun save(itemId: Long, result: AnalysisResult, raw: String, modelId: String, profile: VisionProfile) {
            val entity = AnalysisEntity(
                itemId = itemId,
                description = result.description,
                actionsJson = Json.encodeToString(ListSerializer(ActionEntry.serializer()), result.actions),
                suggestedName = result.suggestedName,
                shotType = result.shotType,
                rawJson = raw,
                modelId = modelId,
                promptVersion = AnalysisPrompt.PROMPT_VERSION,
                stopReason = profile.stopReason,
                ttftMs = profile.ttftMs,
                generatedTokens = profile.generatedTokens,
                analyzedAt = System.currentTimeMillis(),
            )
            pipelineProject().db.analyses().saveAnalysis(entity, result.tags)
        }

        override suspend fun counts(): ItemCounts {
            val items = pipelineProject().db.items()
            val by = ItemStatus.entries.associateWith { items.countByStatus(it) }
            return ItemCounts(
                pending = by.getValue(ItemStatus.Pending) + by.getValue(ItemStatus.Analyzing),
                analyzed = by.getValue(ItemStatus.Analyzed) + by.getValue(ItemStatus.Sorted) + by.getValue(ItemStatus.Binned),
                total = by.values.sum(),
                failed = by.getValue(ItemStatus.Failed) + by.getValue(ItemStatus.Missing),
            )
        }
    }

    /** The open project's categories and uncategorized items, for the categorizer. */
    private val sortStore = object : SortStore {
        override suspend fun categories(): List<CategoryInfo> = pipelineProject().db.sort().categories()

        override suspend fun uncategorized(): List<UncategorizedItem> = pipelineProject().db.sort().uncategorized()

        override suspend fun itemCount(): Int {
            val items = pipelineProject().db.items()
            return ItemStatus.entries.sumOf { items.countByStatus(it) }
        }

        override suspend fun applyChunk(newCategories: List<CategoryEntity>, assignments: List<Assignment>): Int =
            pipelineProject().db.sort().applyChunk(newCategories, assignments)

        override suspend fun startRun(run: SortRunEntity): Long = pipelineProject().db.sort().startRun(run)

        override suspend fun finishRun(id: Long, finishedAt: Long, sorted: Int, newCategories: Int, outcome: String, message: String?) =
            pipelineProject().db.sort().finishRun(id, finishedAt, sorted, newCategories, outcome, message)

        override suspend fun closeOpenRuns(finishedAt: Long, message: String): Int =
            pipelineProject().db.sort().closeOpenRuns(finishedAt, message)

        override suspend fun prepareResort(keepEdits: Boolean): Int = pipelineProject().db.sort().prepareResort(keepEdits)
    }

    /** Null if [choice] can sort now; otherwise the line Settings shows for it (P1). */
    suspend fun sortingUnavailable(choice: SortingModelChoice): String? = when (val spec = specOf(choice)) {
        null -> if (apiKeys.info.first() == null) NEED_KEY_TEXT else null
        else -> if (withContext(Dispatchers.IO) { modelDownloader.isReady(spec) }) null else "Download ${spec.displayName} in Settings first"
    }

    /** One model job at a time: analysis, sorting and (later) Re-sort and Bin Files all go through it. */
    val modelArbiter = ModelArbiter()

    private val preparer = object : Preparer {
        override fun prepare(item: ItemEntity, maxFrames: Int, onFrame: (ready: Int, total: Int) -> Unit): PreparedMedia = mediaPreparer.prepare(
            item,
            ProjectFolders.thumbsDir(File(pipelineProject().ref.path)),
            visionModel.imageEdge,
            maxFrames,
            onFrame,
        )

        override fun cleanup(itemId: Long) = mediaPreparer.cleanup(itemId)
    }

    /** Analyzes the open project's Pending items; drive it through [startAnalysis] so it runs in the foreground service. */
    val pipeline: PipelineController by lazy {
        PipelineController(
            scope = appScope,
            engineFactory = { cap -> AnalysisEngine(visionModel, preparer, analysisStore, cap, Dispatchers.IO, pipelineLog) },
            model = visionModel,
            store = analysisStore,
            categorizer = SortingCategorizer(
                store = sortStore,
                modelFor = ::textModelFor,
                choice = { settings.settings.first().sortingModel },
                log = pipelineLog,
            ),
            arbiter = modelArbiter,
            ensureModel = ::ensureVisionModel,
            log = pipelineLog,
            isCurrent = { it == pipelineTarget },
            maxFramesPerClip = { settings.settings.first().maxFramesPerClip },
            prepareResort = sortStore::prepareResort,
            sortingAvailable = ::sortingUnavailable,
            mover = FileMover { _, job, control, onProgress ->
                withContext(Dispatchers.IO) {
                    val bin = binFilesJob(pipelineProject())
                    when (job) {
                        MoveJob.BinFiles -> bin.binFiles(control, onProgress)
                        is MoveJob.Save -> bin.resume(job.runId, control, onProgress)
                        is MoveJob.Resume -> bin.resume(job.runId, control, onProgress)
                    }
                }
            },
        )
    }

    /** Files on the phone's storage; a volume is a device number, so a rename never crosses one (B1). */
    private val moveFiles by lazy { RealMoveFiles { android.system.Os.stat(it.path).st_dev } }

    /** Tells MediaStore about each move (B11, Plan 3 Task 10). */
    private val moveNotifier: MoveNotifier by lazy { MediaStoreNotifier(context) }

    /** One job per open project, so a report keeps what the database doesn't (in-place count, the check). */
    @Volatile
    private var binJob: Pair<OpenProject, BinFilesJob>? = null

    private fun binFilesJob(project: OpenProject): BinFilesJob {
        binJob?.takeIf { it.first === project }?.let { return it.second }
        val job = BinFilesJob(
            store = RoomBinStore(project.db, ::sharedWithOtherProjects),
            files = moveFiles,
            notifier = moveNotifier,
            projectDir = project.ref.path,
        )
        binJob = project to job
        return job
    }

    /**
     * Which of [paths] another listed project also holds (P8's follow-on ruling: copied, never moved).
     * Opens each other project's database that exists and closes it again.
     */
    private suspend fun sharedWithOtherProjects(paths: List<String>): Set<String> = withContext(Dispatchers.IO) {
        val current = pipelineProject().ref
        val opened = mutableListOf<ProjectDatabase>()
        try {
            val others = registry.list().filter { !ProjectRegistry.samePath(it.path, current.path) }.mapNotNull { ref ->
                val dbFile = ProjectFolders.databaseFile(File(ref.path))
                if (!dbFile.isFile) null else openProjectDatabase(context, dbFile).also { opened += it }
            }
            if (others.isEmpty()) return@withContext emptySet()
            paths.chunked(500).flatMap { chunk -> others.flatMap { db -> db.items().findByPaths(chunk).map { it.currentPath } } }.toSet()
        } finally {
            withContext(NonCancellable) { opened.forEach { it.close() } }
        }
    }

    /**
     * Bin Files for the open project [ref] (Plan 3) in [AnalysisService]. Refused while any run is in
     * progress (never queued) or for a project that is not open; the controller checks again when the
     * service starts it.
     */
    fun binFiles(ref: ProjectRef): MoveStart {
        if (pipelineTarget != ref) return MoveStart.Refused(MOVE_NOT_OPEN)
        if (pipeline.state.value.phase.isRunning) return MoveStart.Refused(MOVE_BUSY)
        return if (startAnalysis(ref, RunKind.BinFiles)) MoveStart.Started else MoveStart.Refused(MOVE_NOT_STARTED)
    }

    /** A Save's file moves for [project] (Plan 3 Task 14): the plan is written with the edit, then run here. */
    fun saveMoveHooks(project: OpenProject): SaveMoveHooks = object : SaveMoveHooks {
        override fun blockedReason() = if (pipeline.state.value.phase.isRunning) SAVE_BUSY else null

        override suspend fun plan(ids: Collection<Long>) = binFilesJob(project).planSave(ids)

        override suspend fun started(runId: Long) {
            try {
                AnalysisService.start(context, project.ref, RunKind.SaveMoves, moveRunId = runId)
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
                Log.w(TAG, "Save's moves deferred until the app is back in the foreground", e)
                deferredStart = DeferredStart(project.ref, RunKind.ResumeMove)
            } catch (e: SecurityException) {
                Log.w(TAG, "Save's moves refused", e)
                deferredStart = DeferredStart(project.ref, RunKind.ResumeMove)
            }
        }
    }

    /** Bin Files for [project], as the UI drives it (Plan 3 Task 16). */
    fun binActions(project: OpenProject): BinActions = object : BinActions {
        override val projectName = project.ref.name
        override val projectPath = project.ref.path

        private fun isOpen() = _currentProject.value === project

        override suspend fun preview() = if (isOpen()) binFilesJob(project).preview() else null

        override fun start() = binFiles(project.ref)

        override fun pause() = pipeline.pause()

        override fun resume() = pipeline.resume()

        override fun stop() = stopAnalysis()

        override suspend fun unreadReport() = if (isOpen()) binFilesJob(project).unreadReport() else null

        override suspend fun closeReport(runId: Long) {
            if (isOpen()) binFilesJob(project).closeReport(runId)
        }

        override suspend fun facts(path: String): UnknownFileFacts {
            val file = File(path)
            val added = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()))
            return UnknownFileFacts(added, formatBytes(file.length()))
        }

        override suspend fun leave(paths: List<String>) {
            if (isOpen()) binFilesJob(project).leave(paths)
        }

        override suspend fun add(paths: List<String>) {
            if (!isOpen()) return
            val candidates = paths.map(::File).filter { it.isFile }.mapNotNull { file -> MediaScanner.kindOf(file)?.let { Candidate(file, it) } }
            register(project, candidates, paths.size - candidates.size, {}, ::startAnalysisAfterAdd)
        }
    }

    /**
     * B3: a move run the app didn't finish is resumed (paused if it was paused) before anything else
     * starts. True if there was one.
     */
    private suspend fun resumeOpenMove(project: OpenProject): Boolean {
        val run = withContext(Dispatchers.IO) { project.db.moves().openRun() } ?: return false
        val state = pipeline.state.value
        if (state.project == project.ref && state.phase.isMoving) return true
        val paused = run.state == com.agentic.binning.move.RunState.Paused.name
        try {
            AnalysisService.start(context, project.ref, RunKind.ResumeMove, moveRunId = run.id, movePaused = paused)
            deferredStart = null
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Move resume deferred until the app is back in the foreground", e)
            deferredStart = DeferredStart(project.ref, RunKind.ResumeMove)
        } catch (e: SecurityException) {
            Log.w(TAG, "Move resume refused", e)
            deferredStart = DeferredStart(project.ref, RunKind.ResumeMove)
        }
        return true
    }

    /**
     * The project the pipeline may run for: the open project, but null from the moment a switch
     * begins until the new project's database is in place, so a stale start is refused.
     */
    @Volatile
    private var pipelineTarget: ProjectRef? = null

    private val pipelineLog = PipelineLog { message, error -> Log.w(TAG, message, error) }

    /** A start the system refused because the app was in the background (and what it was); retried on the next resume. */
    private class DeferredStart(val ref: ProjectRef, val kind: RunKind)

    @Volatile
    private var deferredStart: DeferredStart? = null

    /**
     * Starts (or resumes) analysis of [ref] in [AnalysisService], or with [kind] CategorizeOnly only
     * its categorization. Never throws: if the app is in the background the system refuses to start a
     * foreground service, so the start waits for [onAppResumed]. Returns whether the service was started.
     */
    fun startAnalysis(ref: ProjectRef, kind: RunKind = RunKind.Analyze): Boolean = try {
        AnalysisService.start(context, ref, kind)
        deferredStart = null
        true
    } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
        Log.w(TAG, "Analysis start deferred until the app is back in the foreground", e)
        deferredStart = DeferredStart(ref, kind)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Analysis start refused", e)
        deferredStart = DeferredStart(ref, kind)
        false
    }

    /**
     * Re-sorts the open project [ref] with [request] (plan 2b Task 9) in [AnalysisService]. Refused,
     * clearing nothing, while a run is in progress, for a project that is not open, when the chosen
     * model can't run, or when the system won't start the service; never queued or deferred. Settings
     * are not changed. The controller checks the run and the model again when the service starts it.
     */
    suspend fun resort(ref: ProjectRef, request: ResortRequest): ResortStart {
        if (pipelineTarget != ref) return ResortStart.Refused(RESORT_NOT_OPEN)
        if (pipeline.state.value.phase.isRunning) return ResortStart.Refused(RESORT_BUSY)
        sortingUnavailable(request.model)?.let { return ResortStart.Refused(it) }
        return try {
            AnalysisService.start(context, ref, RunKind.Resort, request)
            ResortStart.Started
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Re-sort start refused", e)
            ResortStart.Refused(RESORT_NOT_STARTED)
        } catch (e: SecurityException) {
            Log.w(TAG, "Re-sort start refused", e)
            ResortStart.Refused(RESORT_NOT_STARTED)
        }
    }

    private val launcher by lazy { AnalysisLauncher({ pipeline.state.value }, ::startAnalysis, pipeline::stop) }

    /** [project]'s waiting items and its remembered Stop, for [launcher]. */
    private fun runFlags(project: OpenProject) = object : ProjectRunFlags {
        override suspend fun hasWaitingItems(): Boolean {
            val items = project.db.items()
            return items.countByStatus(ItemStatus.Pending) + items.countByStatus(ItemStatus.Analyzing) > 0
        }

        override suspend fun hasUncategorizedItems(): Boolean = project.db.sort().observeUncategorizedCount().first() > 0

        override suspend fun isStoppedByUser(): Boolean = project.db.meta().isStoppedByUser() == true

        override suspend fun setStoppedByUser(stopped: Boolean) = project.db.meta().setStoppedByUser(stopped)
    }

    /**
     * Picks up [project]'s Pending or Analyzing items on launch, after a switch and on resume (a run is
     * not persisted, so a process death would otherwise leave them waiting). Leaves a run alone that
     * is already going, or that the user stopped (remembered across launches).
     */
    private suspend fun resumeAnalysisIfPending(project: OpenProject) {
        // An interrupted move comes first; analysis waits until it has finished (B3, Q11).
        if (resumeOpenMove(project)) return
        launcher.autoStart(project.ref, runFlags(project))
    }

    /** After media is added: lifts a Stop and analyzes anything waiting (never a categorize-only run). */
    private suspend fun startAnalysisAfterAdd(project: OpenProject) {
        launcher.userAdd(project.ref, runFlags(project))
    }

    /** After a failed item's Retry (plan 2b P6): like an add, it lifts a Stop and analyzes what is waiting. */
    suspend fun startAnalysisAfterRetry(project: OpenProject) = startAnalysisAfterAdd(project)

    /** The Stop button (screen or notification): stops the run and remembers it for the open project. */
    fun stopAnalysis() {
        // A move's Stop only stops the move, after the file in progress (B2); it is not analysis' Stop.
        if (pipeline.state.value.phase.isMoving) {
            pipeline.stop()
            return
        }
        val open = _currentProject.value
        if (open == null) {
            pipeline.stop()
            return
        }
        // Undispatched so the call queues for the launcher's lock in call order (see AnalysisLauncher).
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                launcher.userStop(runFlags(open))
            } catch (e: Exception) {
                Log.w(TAG, "Could not remember the stop for ${open.ref.name}", e)
            }
        }
    }

    /**
     * The Resume button after a stop (or with nothing running): lifts the Stop and starts [ref]'s
     * waiting items, or with none categorizes its analyzed items that have no category yet.
     */
    fun resumeAnalysis(ref: ProjectRef) {
        val open = _currentProject.value?.takeIf { it.ref == ref } ?: return
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                launcher.userStart(ref, runFlags(open))
            } catch (e: Exception) {
                Log.w(TAG, "Could not resume analysis of ${ref.name}", e)
            }
        }
    }

    /**
     * Call from the activity's onResume: retries a start that was refused while the app was in the
     * background, and otherwise picks up Pending items that nothing is analyzing.
     */
    fun onAppResumed() {
        appScope.launch {
            val open = _currentProject.value ?: return@launch
            try {
                val deferred = deferredStart?.takeIf { it.ref == open.ref }
                deferredStart = null
                when {
                    deferred == null -> resumeAnalysisIfPending(open)
                    // A refused move start: the open run (if any) is resumed; a Bin Files tap is not repeated.
                    deferred.kind == RunKind.BinFiles || deferred.kind == RunKind.SaveMoves || deferred.kind == RunKind.ResumeMove -> resumeAnalysisIfPending(open)
                    else -> launcher.retryDeferred(open.ref, runFlags(open), deferred.kind)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start analysis on resume", e)
            }
        }
    }

    /** Downloads the vision model if it is not on the phone yet. */
    private suspend fun ensureVisionModel(onProgress: (Float) -> Unit) {
        val spec = ModelCatalog.LFM3_VL_2B
        if (withContext(Dispatchers.IO) { modelDownloader.isReady(spec) }) return
        var last: DownloadState? = null
        modelDownloader.pull(spec).collect { state ->
            last = state
            if (state is DownloadState.Downloading) onProgress(state.fraction)
        }
        when (val end = last) {
            DownloadState.Ready -> Unit
            is DownloadState.Failed -> throw VisionModelException(end.message)
            else -> throw VisionModelException("${spec.displayName} download did not finish")
        }
    }

    /**
     * Opens [ref]'s database (writing its [ProjectMetaEntity] on first open), makes it the registry's
     * current project and closes the previously open project's database.
     *
     * Then starts analysis if the project has items waiting.
     *
     * @throws ProjectMissingException if the project folder no longer exists; nothing is touched on
     * disk in that case, because opening the database would recreate the folder.
     */
    suspend fun openProject(ref: ProjectRef): OpenProject = openProject(ref, existingOnly = false)

    /**
     * [existingOnly]: the database must already exist (Open project); it is never created and no
     * `project_meta` row is written, and a missing file throws [ProjectMissingException].
     */
    private suspend fun openProject(ref: ProjectRef, existingOnly: Boolean): OpenProject {
        val opened = openLock.withLock {
            try {
                openLocked(ref, existingOnly)
            } finally {
                // Whatever happened, the pipeline may run for exactly the project that is open now.
                pipelineTarget = _currentProject.value?.ref
            }
        }
        try {
            resumeAnalysisIfPending(opened)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not start analysis of ${ref.name}", e)
        }
        return opened
    }

    private suspend fun openLocked(ref: ProjectRef, existingOnly: Boolean = false): OpenProject {
        withContext(Dispatchers.IO) { requireProjectFolder(ref) }
        _currentProject.value?.takeIf { ProjectRegistry.samePath(it.ref.path, ref.path) }?.let { return it }
        val dbFile = ProjectFolders.databaseFile(File(ref.path))
        if (existingOnly && !withContext(Dispatchers.IO) { dbFile.isFile }) throw ProjectMissingException(dbFile.path)
        // The pipeline works on the open database, which is about to be closed: refuse new starts,
        // stop it, wait for its cleanup and make it forget the old project.
        if (_currentProject.value != null) {
            pipelineTarget = null
            pipeline.stopAndReset()
            deferredStart = null
        }
        val db = withContext(Dispatchers.IO) { openProjectDatabase(context, dbFile) }
        val stored = try {
            if (db.meta().get() == null) {
                check(!existingOnly) { "An existing project database has no project_meta row" }
                db.meta().insert(
                    ProjectMetaEntity(
                        name = ref.name,
                        createdAt = System.currentTimeMillis(),
                        schemaVersion = PROJECT_DB_VERSION,
                    ),
                )
            }
            // Listed only once the database opened; an existing entry for the folder keeps its name.
            withContext(Dispatchers.IO) { registry.setCurrent(ref) }
        } catch (t: Throwable) {
            db.close()
            throw t
        }
        val previous = _currentProject.value
        val opened = OpenProject(stored, db)
        _currentProject.value = opened
        previous?.db?.close()
        return opened
    }

    /**
     * Reopens the registry's current project; call once storage permission has been granted.
     * A current project whose folder no longer exists is cleared instead of opened.
     */
    suspend fun restoreCurrentProject() {
        val ref = withContext(Dispatchers.IO) { registry.current() } ?: return
        try {
            openProject(ref)
        } catch (_: ProjectMissingException) {
            try {
                withContext(Dispatchers.IO) { registry.clearCurrent() }
            } catch (e: ProjectListUnreadableException) {
                Log.w(TAG, "Could not clear the missing current project", e)
            }
        }
    }

    /** What Open project did with a picked folder. */
    sealed interface OpenExistingResult {
        data class Opened(val project: OpenProject) : OpenExistingResult

        /** One short line for the sheet; nothing on disk was changed. */
        data class Refused(val message: String) : OpenExistingResult
    }

    /**
     * Open project (plan 2b Task 6A): opens the project in [picked] (or in its parent when [picked]
     * is the `.binning` folder). A listed folder just switches to its entry. Otherwise the database
     * is probed read-only first ([ProjectDatabaseProbe]); a missing, unreadable, foreign or newer one
     * is refused with nothing changed on disk, an older one is copied to `project.db.v<N>.bak` before
     * Room upgrades it ([openProjectDatabase] does that on every open), and the saved analysis opens
     * as it is, never recreated.
     */
    suspend fun openExistingProject(picked: File): OpenExistingResult {
        val dir = ProjectOpenRules.projectDir(picked)
        val listed = withContext(Dispatchers.IO) { registry.list() }
        when (val placement = ProjectOpenRules.placement(dir.path, listed)) {
            is Placement.Listed -> return openRefusingFailures { openProject(placement.ref) }
            is Placement.Inside, is Placement.Contains ->
                return OpenExistingResult.Refused(checkNotNull(ProjectOpenRules.placementMessage(placement)))
            Placement.Unlisted -> Unit
        }
        val dbFile = ProjectFolders.databaseFile(dir)
        val facts = withContext(Dispatchers.IO) { ProjectDatabaseProbe.probe(dbFile) }
        return when (val decision = ProjectOpenRules.decide(facts, PROJECT_DB_VERSION, PROJECT_DB_IDENTITY_HASHES)) {
            is DatabaseDecision.Refused -> OpenExistingResult.Refused(decision.reason.message)
            is DatabaseDecision.Open -> openRefusingFailures {
                val ref = ProjectRef(ProjectOpenRules.listedName(facts, dir), ProjectRegistry.canonical(dir.path))
                openProject(ref, existingOnly = true)
            }
        }
    }

    private suspend fun openRefusingFailures(open: suspend () -> OpenProject): OpenExistingResult = try {
        OpenExistingResult.Opened(open())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProjectListUnreadableException) {
        Log.w(TAG, "Open project: the project list can't be read", e)
        OpenExistingResult.Refused(OpenRefusal.ListUnreadable.message)
    } catch (e: ProjectMissingException) {
        OpenExistingResult.Refused(OpenRefusal.NoProject.message)
    } catch (e: Exception) {
        Log.w(TAG, "Open project failed", e)
        OpenExistingResult.Refused(OpenRefusal.CantOpen.message)
    }

    /** What removing a project from Current projects did. */
    sealed interface RemoveResult {
        /** [opened] is the project opened in place of a removed open one, or null (none, or it wasn't open). */
        data class Removed(val removed: RemovedProject, val wasOpen: Boolean, val opened: OpenProject?) : RemoveResult

        data object NotListed : RemoveResult

        data class Refused(val message: String) : RemoveResult
    }

    /** Whether analysis or sorting is running for [ref] right now (removing it would stop that). */
    fun isRunning(ref: ProjectRef): Boolean {
        val state = pipeline.state.value
        val project = state.project ?: return false
        return state.phase.isRunning && ProjectRegistry.samePath(project.path, ref.path)
    }

    /**
     * Takes [ref] off Current projects (plan 2b Task 6A): only its registry entry goes; its folder,
     * media, database and thumbnails are never touched, and a missing folder is fine. If it is the
     * open project, its run is stopped and the models released exactly as a switch does, its database
     * is closed, and the first remaining project that opens cleanly is opened instead (or none).
     * Refused, with nothing changed, if the list file can't be read.
     */
    suspend fun removeProject(ref: ProjectRef): RemoveResult {
        val (removed, wasOpen) = openLock.withLock {
            try {
                val removed = try {
                    withContext(Dispatchers.IO) { registry.remove(ref.path) }
                } catch (e: ProjectListUnreadableException) {
                    Log.w(TAG, "Remove: the project list can't be read", e)
                    return RemoveResult.Refused(OpenRefusal.ListUnreadable.message)
                } ?: return RemoveResult.NotListed
                val open = _currentProject.value
                val wasOpen = open != null && ProjectRegistry.samePath(open.ref.path, removed.ref.path)
                if (open != null && wasOpen) {
                    withContext(NonCancellable) {
                        pipelineTarget = null
                        pipeline.stopAndReset()
                        deferredStart = null
                        _currentProject.value = null
                        withContext(Dispatchers.IO) { open.db.close() }
                    }
                }
                removed to wasOpen
            } finally {
                pipelineTarget = _currentProject.value?.ref
            }
        }
        val opened = if (wasOpen) openNextAfterRemoval() else null
        return RemoveResult.Removed(removed, wasOpen, opened)
    }

    /**
     * Opens the first listed project whose folder exists and whose database opens as it is (current
     * schema, no backup needed); null if none does.
     */
    private suspend fun openNextAfterRemoval(): OpenProject? {
        val next = withContext(Dispatchers.IO) {
            ProjectOpenRules.nextAfterRemoval(registry.list()) { candidate ->
                val dir = File(candidate.path)
                dir.isDirectory && ProjectOpenRules.decide(
                    ProjectDatabaseProbe.probe(ProjectFolders.databaseFile(dir)),
                    PROJECT_DB_VERSION,
                    PROJECT_DB_IDENTITY_HASHES,
                ) == DatabaseDecision.Open(backupFromVersion = null)
            }
        } ?: return null
        return try {
            openProject(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${next.name} after a removal", e)
            null
        }
    }

    /**
     * Undo for [removeProject]: puts the entry back where it was and, if it was the open project,
     * reopens it. Returns a short message if that failed, else null.
     */
    suspend fun restoreProject(removed: RemovedProject): String? {
        try {
            withContext(Dispatchers.IO) { registry.restore(removed) }
        } catch (e: ProjectListUnreadableException) {
            Log.w(TAG, "Undo: the project list can't be read", e)
            return OpenRefusal.ListUnreadable.message
        }
        if (!removed.wasCurrent) return null
        return try {
            openProject(removed.ref)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: ProjectMissingException) {
            "That project's folder is missing"
        } catch (e: Exception) {
            Log.w(TAG, "Undo: could not reopen ${removed.ref.name}", e)
            "Could not open that project"
        }
    }

    /**
     * Registers every supported photo and video under the folder behind [treeUri] with the current
     * project. Other projects' folders and the current project's `.binning` folder are not entered.
     * [onProgress] is called once per file; [onRegistered] after the run completes (by default it
     * lifts a Stop and starts analysis if the project has Pending items; it never fails the registration).
     *
     * @throws IllegalStateException if no project is open.
     */
    suspend fun addFolder(
        treeUri: Uri,
        onProgress: (RegistrationProgress) -> Unit,
        onRegistered: suspend (OpenProject) -> Unit = ::startAnalysisAfterAdd,
    ): RegistrationSummary {
        val project = checkNotNull(_currentProject.value) { "No project is open" }
        val (candidates, skipped) = withContext(Dispatchers.IO) {
            val dir = MediaUris.treeUriToDir(treeUri)?.takeIf { it.isDirectory }
                ?: return@withContext emptyList<Candidate>() to 1
            val skip = scanExclusions(project.ref)
            val found = MediaScanner.scan(dir) { it.canonicalPath in skip }
                .mapNotNull { file -> MediaScanner.kindOf(file)?.let { Candidate(file, it) } }
                .toList()
            found to 0
        }
        return register(project, candidates, skipped, onProgress, onRegistered)
    }

    /**
     * Registers the files behind [uris] (from the photos and videos picker) with the current project.
     * Items that cannot be resolved to a supported media file count as unreadable in the summary.
     *
     * @throws IllegalStateException if no project is open.
     */
    suspend fun addFiles(
        uris: List<Uri>,
        onProgress: (RegistrationProgress) -> Unit,
        onRegistered: suspend (OpenProject) -> Unit = ::startAnalysisAfterAdd,
    ): RegistrationSummary {
        val project = checkNotNull(_currentProject.value) { "No project is open" }
        val (candidates, skipped) = withContext(Dispatchers.IO) {
            val files = uris.map { MediaUris.pickedUriToFile(context, it) }
            val found = files.filterNotNull().distinct().mapNotNull { file ->
                if (!file.isFile) null else MediaScanner.kindOf(file)?.let { Candidate(file, it) }
            }
            found to (uris.size - found.size)
        }
        return register(project, candidates, skipped, onProgress, onRegistered)
    }

    private fun scanExclusions(current: ProjectRef): Set<String> {
        val otherFolders = registry.list().filter { it.path != current.path }.map { File(it.path).canonicalPath }
        return (otherFolders + ProjectFolders.binningDir(File(current.path)).canonicalPath).toSet()
    }

    private suspend fun register(
        project: OpenProject,
        candidates: List<Candidate>,
        skipped: Int,
        onProgress: (RegistrationProgress) -> Unit,
        onRegistered: suspend (OpenProject) -> Unit,
    ): RegistrationSummary {
        val opened = mutableListOf<ProjectDatabase>()
        try {
            val lookups = withContext(Dispatchers.IO) { openOtherProjects(project.ref, opened) }
            val registrar = Registrar(
                thisProject = project.ref,
                itemDao = project.db.items(),
                otherProjects = lookups,
                registry = registry,
                metadata = MediaMetadataReader::read,
                clock = System::currentTimeMillis,
            )
            var summary = RegistrationSummary(skipped = skipped)
            val added = mutableListOf<String>()
            registrar.register(candidates).flowOn(Dispatchers.IO).collect { progress ->
                summary += progress.last
                (progress.last as? RegistrationOutcome.Added)?.let { added += it.path }
                onProgress(progress)
            }
            // A removed (or left) file added again is an item once more; the folder check needn't skip it (Q2).
            if (added.isNotEmpty()) withContext(Dispatchers.IO) { added.chunked(500).forEach { project.db.moves().dropRemoved(it) } }
            try {
                onRegistered(project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The items are registered; a failed follow-up must not report the add as failed.
                Log.w(TAG, "After-registration step failed", e)
            }
            return summary
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened.forEach { it.close() } }
        }
    }

    /** Opens every other registered project's database that exists on disk; [opened] collects them for closing. */
    private fun openOtherProjects(current: ProjectRef, opened: MutableList<ProjectDatabase>): List<ProjectItemLookup> =
        registry.list().filter { it.path != current.path }.mapNotNull { ref ->
            val dbFile = ProjectFolders.databaseFile(File(ref.path))
            if (!dbFile.isFile) return@mapNotNull null
            val db = openProjectDatabase(context, dbFile)
            opened += db
            ItemStoreLookup(ref.name, db.items())
        }

    private companion object {
        const val TAG = "AppGraph"
    }
}
