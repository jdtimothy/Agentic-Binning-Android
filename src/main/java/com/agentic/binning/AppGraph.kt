package com.agentic.binning

import android.content.Context
import android.os.Environment
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.agentic.binning.db.ProjectMetaEntity
import com.agentic.binning.db.openProjectDatabase
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import com.agentic.binning.project.requireProjectFolder
import com.agentic.binning.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
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

    private companion object {
        const val PROJECT_SCHEMA_VERSION = 1
    }
}
