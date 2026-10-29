package com.agentic.binning

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
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
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import com.agentic.binning.project.requireProjectFolder
import com.agentic.binning.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /**
     * Opens [ref]'s database (writing its [ProjectMetaEntity] on first open), makes it the registry's
     * current project and closes the previously open project's database.
     *
     * @throws ProjectMissingException if the project folder no longer exists; nothing is touched on
     * disk in that case, because opening the database would recreate the folder.
     */
    suspend fun openProject(ref: ProjectRef): OpenProject = openLock.withLock {
        withContext(Dispatchers.IO) { requireProjectFolder(ref) }
        _currentProject.value?.takeIf { it.ref == ref }?.let { return it }
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
        opened
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
     * [onProgress] is called once per file; [onRegistered] after the run completes.
     *
     * @throws IllegalStateException if no project is open.
     */
    suspend fun addFolder(
        treeUri: Uri,
        onProgress: (RegistrationProgress) -> Unit,
        onRegistered: (OpenProject) -> Unit = {},
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
        onRegistered: (OpenProject) -> Unit = {},
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
        onRegistered: (OpenProject) -> Unit,
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
            onRegistered(project)
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
        const val PROJECT_SCHEMA_VERSION = 1
    }
}
