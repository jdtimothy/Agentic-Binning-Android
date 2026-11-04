package com.agentic.binning.ui.project

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.agentic.binning.AppGraph
import com.agentic.binning.db.ItemTotals
import com.agentic.binning.media.MediaUris
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.RegistrationSummary
import com.agentic.binning.project.OpenRefusal
import com.agentic.binning.project.ProjectExistsException
import com.agentic.binning.project.ProjectListUnreadableException
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.RemovedProject
import com.agentic.binning.settings.MediaPickerChoice
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProjectSheet"
private const val ADDING_IN_PROGRESS = "Wait for adding to finish"

private fun createErrorMessage(t: Throwable): String = when (t) {
    is ProjectExistsException -> "A project folder with that name already exists"
    is ProjectListUnreadableException -> OpenRefusal.ListUnreadable.message
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

    // Current projects (plan 2b Task 6A).
    private var editing by mutableStateOf(false)
    private var missing by mutableStateOf(emptySet<String>())
    private var confirmRemove by mutableStateOf<ProjectRef?>(null)
    private var undo by mutableStateOf<PendingUndo?>(null)
    private var undoIds = 0L

    /** The last removal, offered for Undo until [UNDO_MILLIS] pass or another action replaces it. */
    private class PendingUndo(val id: Long, val removed: RemovedProject)

    /** Set by [ProjectSheetHost], which owns the system picker launchers and records where the result goes. */
    internal var launchFolderPicker: (PickFor) -> Unit = {}
    internal var launchFilesPicker: (PickFor) -> Unit = {}

    internal val currentProject get() = graph.currentProject
    internal val mediaPicker = graph.settings.settings.map { it.mediaPicker }

    /** Opens the sheet on its main page (Current projects, with Open project and New project). */
    fun open() {
        createError = null
        switchError = null
        editing = false
        confirmRemove = null
        undo = null
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
        editing = editing,
        missing = missing,
        confirmRemove = confirmRemove,
        undoText = undo?.let { removedToastText(it.removed.ref.name) },
        undoId = undo?.id ?: 0L,
    )

    /** A folder from the system picker, for the [target] recorded when the picker was launched. */
    internal fun onFolderPicked(uri: Uri?, target: PickFor?) {
        val route = routePick(target, hasResult = uri != null)
        if (uri == null) return
        when (route) {
            PickRoute.Ignore -> Unit
            PickRoute.AddToCurrent -> runRegistration { progress -> graph.addFolder(uri, progress) }
            PickRoute.HoldForNewProject -> holdForNewProject(HeldMedia.Folder(uri))
            PickRoute.OpenProject -> openExisting(uri)
        }
    }

    /**
     * Open project: the picked folder is checked and opened by [AppGraph.openExistingProject]; a
     * refusal is one line under the two buttons. The sheet reopens on its main page if the activity
     * was recreated while the picker was open.
     */
    private fun openExisting(uri: Uri) {
        page = SheetPage.Main
        editing = false
        undo = null
        switchError = null
        visible = true
        val dir: File = MediaUris.treeUriToDir(uri) ?: run {
            switchError = OpenRefusal.NotOnDevice.message
            return
        }
        scope.launch {
            try {
                when (val result = graph.openExistingProject(dir)) {
                    is AppGraph.OpenExistingResult.Opened -> visible = false
                    is AppGraph.OpenExistingResult.Refused -> switchError = result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Open project failed", e)
                switchError = OpenRefusal.CantOpen.message
            }
            refreshProjects()
        }
    }

    /** Photos and videos from the picker, for the [target] recorded when the picker was launched. */
    internal fun onFilesPicked(uris: List<Uri>, target: PickFor?) {
        when (routePick(target, hasResult = uris.isNotEmpty())) {
            PickRoute.Ignore -> Unit
            PickRoute.AddToCurrent -> runRegistration { progress -> graph.addFiles(uris, progress) }
            PickRoute.HoldForNewProject -> holdForNewProject(HeldMedia.Files(uris))
            // Open project only ever launches the folder picker.
            PickRoute.OpenProject -> Unit
        }
    }

    /**
     * Holds [media] on the New project page. If the activity was recreated while the picker was open,
     * this controller is fresh (sheet closed, main page), so the sheet reopens on New project.
     */
    private fun holdForNewProject(media: HeldMedia) {
        held = media
        if (!visible || page != SheetPage.NewProject) {
            page = SheetPage.NewProject
            switchError = null
            refreshProjects()
            visible = true
        }
    }

    private fun pickTarget(): PickFor = if (page == SheetPage.NewProject) PickFor.NewProject else PickFor.Current

    override fun onPickFolder() {
        if (!creating) launchFolderPicker(pickTarget())
    }

    override fun onPickFiles() {
        if (!creating) launchFilesPicker(pickTarget())
    }

    override fun onNewProject() {
        resetNewProject()
        switchError = null
        undo = null
        page = SheetPage.NewProject
    }

    override fun onOpenProject() {
        if (creating) return
        switchError = null
        undo = null
        if (registering) {
            switchError = ADDING_IN_PROGRESS
            return
        }
        launchFolderPicker(PickFor.OpenProject)
    }

    override fun onToggleEdit() {
        if (!editing && (registering || creating)) return
        editing = !editing
        switchError = null
    }

    /** × on a row: removes at once, or asks first when that would stop running analysis (plan 2b P7c). */
    override fun onRemove(ref: ProjectRef) {
        if (registering || creating) return
        switchError = null
        if (graph.isRunning(ref)) confirmRemove = ref else remove(ref)
    }

    override fun onConfirmRemove() {
        val ref = confirmRemove ?: return
        confirmRemove = null
        remove(ref)
    }

    override fun onCancelRemove() {
        confirmRemove = null
    }

    /** Takes [ref] off the list; only the registry entry goes (see [AppGraph.removeProject]). */
    private fun remove(ref: ProjectRef) {
        undo = null
        scope.launch {
            try {
                when (val result = graph.removeProject(ref)) {
                    is AppGraph.RemoveResult.Removed -> undo = PendingUndo(++undoIds, result.removed)
                    AppGraph.RemoveResult.NotListed -> Unit
                    is AppGraph.RemoveResult.Refused -> switchError = result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove project", e)
                switchError = "Could not remove that project"
            }
            refreshProjects()
        }
    }

    override fun onUndo() {
        val pending = undo ?: return
        undo = null
        scope.launch {
            switchError = try {
                graph.restoreProject(pending.removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Undo failed", e)
                "Could not put that project back"
            }
            refreshProjects()
        }
    }

    override fun onUndoExpired(id: Long) {
        if (undo?.id == id) undo = null
    }

    override fun onBack() {
        if (!creating) page = SheetPage.Main
    }

    override fun onNameChange(name: String) {
        this.name = name
        createError = null
    }

    override fun onDismiss() {
        visible = false
    }

    override fun onSwitch(ref: ProjectRef) {
        if (creating || editing) return
        switchError = null
        undo = null
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
     * and videos do (so analysis starts). Errors keep the New project page and its selection.
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

    /** Re-reads the list and which folders are missing; Edit ends once the list is empty. */
    private fun refreshProjects() {
        scope.launch {
            val (listed, gone) = withContext(Dispatchers.IO) {
                val listed = graph.registry.list()
                listed to listed.filter { !File(it.path).isDirectory }.map { it.path }.toSet()
            }
            projects = listed
            missing = gone
            editing = editingAfterRemoval(editing, listed.size)
        }
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

private const val SAMSUNG_GALLERY = "com.sec.android.gallery3d"
private val PICKED_MIME_TYPES = arrayOf("image/*", "video/*")

/** Samsung Gallery's own picker; launching throws if the app is not there. */
private class PickFromGallery : ActivityResultContracts.GetMultipleContents() {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input)
            .setPackage(SAMSUNG_GALLERY)
            .putExtra(Intent.EXTRA_MIME_TYPES, PICKED_MIME_TYPES)
}

/** Owns the pickers and shows [controller]'s sheet while it is open. */
@Composable
fun ProjectSheetHost(controller: ProjectSheetController) {
    // Saved with the activity: a picker result can reach a recreated activity and a fresh controller,
    // so where it goes is recorded at launch rather than read from the controller's page.
    var pickFor by rememberSaveable { mutableStateOf<PickFor?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = pickFor
        pickFor = null
        controller.onFolderPicked(uri, target)
    }
    val onFiles = { uris: List<Uri> ->
        val target = pickFor
        pickFor = null
        controller.onFilesPicked(uris, target)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), onFiles)
    val galleryPicker = rememberLauncherForActivityResult(PickFromGallery(), onFiles)
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onFiles)
    val mediaPicker by controller.mediaPicker.collectAsState(initial = MediaPickerChoice.AndroidPhotoPicker)
    SideEffect {
        controller.launchFolderPicker = { target ->
            pickFor = target
            folderPicker.launch(null)
        }
        controller.launchFilesPicker = { target ->
            pickFor = target
            try {
                when (mediaPicker) {
                    MediaPickerChoice.AndroidPhotoPicker ->
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    MediaPickerChoice.SamsungGallery -> galleryPicker.launch("*/*")
                }
            } catch (_: ActivityNotFoundException) {
                documentPicker.launch(PICKED_MIME_TYPES)
            }
        }
    }
    if (!controller.visible) return

    val open by controller.currentProject.collectAsState()
    // Keyed by project, so a switch starts from no counts instead of showing the previous project's.
    val counts = key(open?.ref?.path) {
        remember(open) {
            val project = open
            project?.db?.items()?.observeTotals()
                // The database closes on a project switch, possibly before this collector moves on.
                ?.catch { e -> Log.w(TAG, "Item counts of ${project.ref.name} stopped", e) }
                ?: flowOf(null)
        }.collectAsState(initial = null).value
    }
    ProjectSheet(controller.state(open?.ref, counts), controller)
}
