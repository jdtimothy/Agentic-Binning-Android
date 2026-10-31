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
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.ProjectDatabase
import com.agentic.binning.db.ProjectMetaEntity
import com.agentic.binning.db.openProjectDatabase
import com.agentic.binning.media.Candidate
import com.agentic.binning.media.ItemStoreLookup
import com.agentic.binning.media.MediaMetadataReader
import com.agentic.binning.media.MediaScanner
import com.agentic.binning.media.MediaUris
import com.agentic.binning.media.ProjectItemLookup
import com.agentic.binning.media.Registrar
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.RegistrationSummary
import com.agentic.binning.model.DownloadState
import com.agentic.binning.model.GenieXVisionModel
import com.agentic.binning.model.ModelCatalog
import com.agentic.binning.model.ModelDownloader
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.pipeline.AnalysisEngine
import com.agentic.binning.pipeline.AnalysisLauncher
import com.agentic.binning.pipeline.AnalysisService
import com.agentic.binning.pipeline.AnalysisStore
import com.agentic.binning.pipeline.BatchCompleteHandler
import com.agentic.binning.pipeline.FailureReasons
import com.agentic.binning.pipeline.ItemCounts
import com.agentic.binning.pipeline.PipelineController
import com.agentic.binning.pipeline.PipelineLog
import com.agentic.binning.pipeline.Preparer
import com.agentic.binning.pipeline.ProjectRunFlags
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import com.agentic.binning.project.requireProjectFolder
import com.agentic.binning.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val preparer = object : Preparer {
        override fun prepare(item: ItemEntity, onFrame: (ready: Int, total: Int) -> Unit): PreparedMedia = mediaPreparer.prepare(
            item,
            ProjectFolders.thumbsDir(File(pipelineProject().ref.path)),
            visionModel.imageEdge,
            onFrame,
        )

        override fun cleanup(itemId: Long) = mediaPreparer.cleanup(itemId)
    }

    /** Analyzes the open project's Pending items; drive it through [startAnalysis] so it runs in the foreground service. */
    val pipeline: PipelineController by lazy {
        PipelineController(
            scope = appScope,
            engineFactory = { AnalysisEngine(visionModel, preparer, analysisStore, Dispatchers.IO, pipelineLog) },
            model = visionModel,
            store = analysisStore,
            batchComplete = BatchCompleteHandler { }, // Plan 2 sorts the batch here.
            ensureModel = ::ensureVisionModel,
            log = pipelineLog,
            isCurrent = { it == pipelineTarget },
        )
    }

    /**
     * The project the pipeline may run for: the open project, but null from the moment a switch
     * begins until the new project's database is in place, so a stale start is refused.
     */
    @Volatile
    private var pipelineTarget: ProjectRef? = null

    private val pipelineLog = PipelineLog { message, error -> Log.w(TAG, message, error) }

    /** A start the system refused because the app was in the background; retried on the next resume. */
    @Volatile
    private var deferredStart: ProjectRef? = null

    /**
     * Starts (or resumes) analysis of [ref] in [AnalysisService]. Never throws: if the app is in the
     * background the system refuses to start a foreground service, so the start waits for
     * [onAppResumed]. Returns whether the service was started.
     */
    fun startAnalysis(ref: ProjectRef): Boolean = try {
        AnalysisService.start(context, ref)
        deferredStart = null
        true
    } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
        Log.w(TAG, "Analysis start deferred until the app is back in the foreground", e)
        deferredStart = ref
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Analysis start refused", e)
        deferredStart = ref
        false
    }

    private val launcher by lazy { AnalysisLauncher({ pipeline.state.value }, ::startAnalysis, pipeline::stop) }

    /** [project]'s waiting items and its remembered Stop, for [launcher]. */
    private fun runFlags(project: OpenProject) = object : ProjectRunFlags {
        override suspend fun hasWaitingItems(): Boolean {
            val items = project.db.items()
            return items.countByStatus(ItemStatus.Pending) + items.countByStatus(ItemStatus.Analyzing) > 0
        }

        override suspend fun isStoppedByUser(): Boolean = project.db.meta().isStoppedByUser() == true

        override suspend fun setStoppedByUser(stopped: Boolean) = project.db.meta().setStoppedByUser(stopped)
    }

    /**
     * Picks up [project]'s Pending or Analyzing items on launch, after a switch and on resume (a run is
     * not persisted, so a process death would otherwise leave them waiting). Leaves a run alone that
     * is already going, or that the user stopped (remembered across launches).
     */
    private suspend fun resumeAnalysisIfPending(project: OpenProject) {
        launcher.autoStart(project.ref, runFlags(project))
    }

    /** After media is added: lifts a Stop and starts anything waiting. */
    private suspend fun startAnalysisAfterAdd(project: OpenProject) {
        launcher.userStart(project.ref, runFlags(project))
    }

    /** The Stop button (screen or notification): stops the run and remembers it for the open project. */
    fun stopAnalysis() {
        val open = _currentProject.value
        if (open == null) {
            pipeline.stop()
            return
        }
        appScope.launch {
            try {
                launcher.userStop(runFlags(open))
            } catch (e: Exception) {
                Log.w(TAG, "Could not remember the stop for ${open.ref.name}", e)
            }
        }
    }

    /** The Resume button after a stop (or with nothing running): lifts the Stop and starts [ref]'s waiting items. */
    fun resumeAnalysis(ref: ProjectRef) {
        val open = _currentProject.value?.takeIf { it.ref == ref } ?: return
        appScope.launch {
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
                val deferred = deferredStart == open.ref
                deferredStart = null
                if (deferred) launcher.retryDeferred(open.ref, runFlags(open)) else resumeAnalysisIfPending(open)
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
    suspend fun openProject(ref: ProjectRef): OpenProject {
        val opened = openLock.withLock {
            try {
                openLocked(ref)
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

    private suspend fun openLocked(ref: ProjectRef): OpenProject {
        withContext(Dispatchers.IO) { requireProjectFolder(ref) }
        _currentProject.value?.takeIf { it.ref == ref }?.let { return it }
        // The pipeline works on the open database, which is about to be closed: refuse new starts,
        // stop it, wait for its cleanup and make it forget the old project.
        if (_currentProject.value != null) {
            pipelineTarget = null
            pipeline.stopAndReset()
            deferredStart = null
        }
        val db = openProjectDatabase(context, ProjectFolders.databaseFile(File(ref.path)))
        try {
            if (db.meta().get() == null) {
                db.meta().insert(
                    ProjectMetaEntity(
                        name = ref.name,
                        createdAt = System.currentTimeMillis(),
                        schemaVersion = PROJECT_SCHEMA_VERSION,
                    ),
                )
            }
        } catch (t: Throwable) {
            db.close()
            throw t
        }
        registry.setCurrent(ref)
        val previous = _currentProject.value
        val opened = OpenProject(ref, db)
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
            withContext(Dispatchers.IO) { registry.clearCurrent() }
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
     * Registers the files behind [uris] (from the system document picker) with the current project.
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
            val files = uris.map { MediaUris.documentUriToFile(context, it) }
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
                metadata = { MediaMetadataReader.read(context, it) },
                clock = System::currentTimeMillis,
            )
            var summary = RegistrationSummary(skipped = skipped)
            registrar.register(candidates).flowOn(Dispatchers.IO).collect { progress ->
                summary += progress.last
                onProgress(progress)
            }
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
        const val PROJECT_SCHEMA_VERSION = 1
    }
}
