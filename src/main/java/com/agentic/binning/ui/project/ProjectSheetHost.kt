package com.agentic.binning.ui.project

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agentic.binning.AppGraph
import com.agentic.binning.db.ItemTotals
import com.agentic.binning.media.MediaUris
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.RegistrationSummary
import com.agentic.binning.project.ProjectExistsException
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProjectSheet"
private const val ADDING_IN_PROGRESS = "Wait for adding to finish"

private fun createErrorMessage(t: Throwable): String = when (t) {
    is ProjectExistsException -> "A project folder with that name already exists"
    is IllegalArgumentException -> "Enter a project name"
    else -> "Could not create the project"
}

/** Media picked on the New project page: the picker's result plus what the sheet shows for it. */
private sealed interface HeldMedia {
    val choice: MediaChoice

    class Folder(val uri: Uri) : HeldMedia {
        override val choice = MediaChoice.Folder(
            MediaUris.treeUriToDir(uri)?.path?.let(::sharedStoragePath) ?: "Selected folder",
        )
    }

    class Files(val uris: List<Uri>) : HeldMedia {
        override val choice = MediaChoice.Files(uris.size)
    }
}

/**
 * The project sheet's state and what its buttons do. Outlives the sheet, so a registration keeps
 * reporting progress after the sheet is closed and reopened. Create one per activity with
 * [rememberProjectSheetController] and show it with [ProjectSheetHost].
 */
@Stable
class ProjectSheetController(private val graph: AppGraph, private val scope: CoroutineScope) : ProjectSheetActions {
    var visible by mutableStateOf(false)
        private set
    private var page by mutableStateOf(SheetPage.Main)
    private var projects by mutableStateOf(emptyList<ProjectRef>())
    private var name by mutableStateOf("")
    private var held by mutableStateOf<HeldMedia?>(null)
    private var createError by mutableStateOf<String?>(null)
    private var switchError by mutableStateOf<String?>(null)
    private var registration by mutableStateOf<RegistrationProgress?>(null)
    private var registrationSummary by mutableStateOf<String?>(null)
    private var registering by mutableStateOf(false)
    private var creating by mutableStateOf(false)

    /** Set by [ProjectSheetHost], which owns the system picker launchers. */
    internal var launchFolderPicker: () -> Unit = {}
    internal var launchFilesPicker: () -> Unit = {}

    internal val currentProject get() = graph.currentProject

    /** Opens the sheet on the main page, or on New project when there is no project yet. */
    fun open() {
        createError = null
        switchError = null
        if (!registering) registrationSummary = null
        page = startPage(graph.currentProject.value?.ref)
        resetNewProject()
        refreshProjects()
        visible = true
    }

    internal fun state(current: ProjectRef?, counts: ItemTotals?) = ProjectSheetState(
        page = page,
        current = current,
        counts = counts,
        projects = projects,
        name = name,
        selection = held?.choice,
        createError = createError,
        switchError = switchError,
        registration = registration,
        registrationSummary = registrationSummary,
        creating = creating,
    )

    /** A folder from the system picker: held on the New project page, otherwise added to the current project. */
    internal fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (page == SheetPage.NewProject) {
            held = HeldMedia.Folder(uri)
        } else {
            runRegistration { progress -> graph.addFolder(uri, progress) }
        }
    }

    /** Photos and clips from the system picker: held on the New project page, otherwise added to the current project. */
    internal fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (page == SheetPage.NewProject) {
            held = HeldMedia.Files(uris)
        } else {
            runRegistration { progress -> graph.addFiles(uris, progress) }
        }
    }

    override fun onPickFolder() = launchFolderPicker()

    override fun onPickFiles() = launchFilesPicker()

    override fun onNewProject() {
        resetNewProject()
        page = SheetPage.NewProject
    }

    override fun onBack() {
        page = SheetPage.Main
    }

    override fun onNameChange(name: String) {
        this.name = name
        createError = null
    }

    override fun onDismiss() {
        visible = false
    }

    override fun onSwitch(ref: ProjectRef) {
        switchError = null
        if (registering) {
            switchError = ADDING_IN_PROGRESS
            return
        }
        scope.launch {
            try {
                graph.openProject(ref)
                visible = false
            } catch (e: ProjectMissingException) {
                switchError = "That project's folder is missing"
                refreshProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open project", e)
                switchError = "Could not open that project"
            }
        }
    }

    /**
     * Creates and opens the project, then adds the held media the same way Add a folder / Pick photos
     * and clips do (so analysis starts). Errors keep the New project page and its selection.
     */
    override fun onCreate() {
        switchError = null
        if (name.isBlank() || creating) return
        if (registering) {
            createError = ADDING_IN_PROGRESS
            return
        }
        creating = true
        val requested = name
        val media = held
        scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.registry.create(requested) }
                    .onSuccess { ref ->
                        try {
                            graph.openProject(ref)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open new project", e)
                            createError = "Could not open the new project"
                            return@onSuccess
                        }
                        createError = null
                        resetNewProject()
                        page = SheetPage.Main
                        when (media) {
                            is HeldMedia.Folder -> runRegistration { progress -> graph.addFolder(media.uri, progress) }
                            is HeldMedia.Files -> runRegistration { progress -> graph.addFiles(media.uris, progress) }
                            null -> Unit
                        }
                    }
                    .onFailure { createError = createErrorMessage(it) }
                refreshProjects()
            } finally {
                creating = false
            }
        }
    }

    private fun resetNewProject() {
        name = ""
        held = null
        createError = null
    }

    private fun refreshProjects() {
        scope.launch { projects = withContext(Dispatchers.IO) { graph.registry.list() } }
    }

    /** Runs one registration, mirroring its progress and result into the sheet state. */
    private fun runRegistration(add: suspend ((RegistrationProgress) -> Unit) -> RegistrationSummary) {
        if (registering) return
        registering = true
        registrationSummary = null
        scope.launch {
            try {
                registrationSummary = add { registration = it }.text()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add media", e)
                registrationSummary = "Could not add media"
            } finally {
                registration = null
                registering = false
            }
        }
    }
}

@Composable
fun rememberProjectSheetController(graph: AppGraph, scope: CoroutineScope): ProjectSheetController =
    remember(graph, scope) { ProjectSheetController(graph, scope) }

/** Owns the system pickers and shows [controller]'s sheet while it is open. */
@Composable
fun ProjectSheetHost(controller: ProjectSheetController) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        controller.onFolderPicked(it)
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        controller.onFilesPicked(it)
    }
    SideEffect {
        controller.launchFolderPicker = { folderPicker.launch(null) }
        controller.launchFilesPicker = { filesPicker.launch(arrayOf("image/*", "video/*")) }
    }
    if (!controller.visible) return

    val open by controller.currentProject.collectAsState()
    val counts by remember(open) {
        val project = open
        project?.db?.items()?.observeTotals()
            // The database closes on a project switch, possibly before this collector moves on.
            ?.catch { e -> Log.w(TAG, "Item counts of ${project.ref.name} stopped", e) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    ProjectSheet(controller.state(open?.ref, counts), controller)
}
