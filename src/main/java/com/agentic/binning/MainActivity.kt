package com.agentic.binning

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.RegistrationSummary
import com.agentic.binning.model.ModelCatalog
import com.agentic.binning.project.ProjectExistsException
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.AppSettings
import com.agentic.binning.ui.analyze.AnalyzeScreen
import com.agentic.binning.ui.analyze.AnalyzeViewModel
import com.agentic.binning.ui.permission.StorageAccessGate
import com.agentic.binning.ui.project.ProjectSheet
import com.agentic.binning.ui.project.ProjectSheetState
import com.agentic.binning.ui.settings.SettingsScreen
import com.agentic.binning.ui.theme.AgenticTheme
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Analyze, Settings }

/** Runs a settings write, logging instead of crashing if the store cannot be written. */
private suspend fun safeWrite(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        Log.e("MainActivity", "Failed to persist setting", e)
    }
}

private const val ADDING_IN_PROGRESS = "Wait for adding to finish"

private fun createErrorMessage(t: Throwable): String = when (t) {
    is ProjectExistsException -> "A project folder with that name already exists"
    is IllegalArgumentException -> "Enter a project name"
    else -> "Could not create the project"
}

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // A start refused while the app was in the background (e.g. media finished registering then).
        (application as BinningApp).graph.onAppResumed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as BinningApp).graph
        setContent {
            val settings by graph.settings.settings.collectAsState(initial = AppSettings())
            val scope = rememberCoroutineScope()
            var screen by remember { mutableStateOf(Screen.Analyze) }

            // Only the running flag: the full state changes on every streamed token.
            val pipelineRunning by remember {
                graph.pipeline.state.map { it.phase.isRunning }.distinctUntilChanged()
            }.collectAsState(initial = graph.pipeline.state.value.phase.isRunning)
            // Keep the screen on only while analysis runs (Paused counts), and only if the setting allows it.
            val keepScreenOn = settings.keepScreenOn && pipelineRunning
            LaunchedEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            AgenticTheme(settings.themeMode) {
                StorageAccessGate {
                    // Storage access is held here, so the current project's folder can be reopened.
                    LaunchedEffect(Unit) {
                        try {
                            graph.restoreCurrentProject()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to restore the current project", e)
                        }
                    }
                    val openProject by graph.currentProject.collectAsState()
                    var projects by remember { mutableStateOf(emptyList<ProjectRef>()) }
                    var showSheet by remember { mutableStateOf(false) }
                    var createError by remember { mutableStateOf<String?>(null) }
                    var switchError by remember { mutableStateOf<String?>(null) }
                    var registration by remember { mutableStateOf<RegistrationProgress?>(null) }
                    var registrationSummary by remember { mutableStateOf<String?>(null) }
                    var registering by remember { mutableStateOf(false) }

                    fun refreshProjects() {
                        scope.launch { projects = withContext(Dispatchers.IO) { graph.registry.list() } }
                    }

                    /** Runs one registration, mirroring its progress and result into the sheet state. */
                    fun runRegistration(add: suspend ((RegistrationProgress) -> Unit) -> RegistrationSummary) {
                        if (registering) return
                        registering = true
                        registrationSummary = null
                        scope.launch {
                            try {
                                registrationSummary = add { registration = it }.text()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to add media", e)
                                registrationSummary = "Could not add media"
                            } finally {
                                registration = null
                                registering = false
                            }
                        }
                    }

                    val folderPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri -> if (uri != null) runRegistration { progress -> graph.addFolder(uri, progress) } }
                    val filesPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenMultipleDocuments(),
                    ) { uris -> if (uris.isNotEmpty()) runRegistration { progress -> graph.addFiles(uris, progress) } }

                    val analyzeViewModel = viewModel {
                        AnalyzeViewModel(
                            pipeline = graph.pipeline,
                            currentProject = graph.currentProject,
                            modelState = graph.modelDownloader.state(ModelCatalog.LFM3_VL_2B),
                            startAnalysis = graph::startAnalysis,
                        )
                    }

                    when (screen) {
                        Screen.Analyze -> {
                            AnalyzeScreen(
                                vm = analyzeViewModel,
                                onOpenSettings = { screen = Screen.Settings },
                                onOpenProjectSheet = {
                                    createError = null
                                    switchError = null
                                    if (!registering) registrationSummary = null
                                    refreshProjects()
                                    showSheet = true
                                },
                            )
                            if (showSheet) {
                                ProjectSheet(
                                    state = ProjectSheetState(
                                        projects,
                                        openProject?.ref,
                                        createError,
                                        switchError,
                                        registration,
                                        registrationSummary,
                                    ),
                                    onCreate = { name ->
                                        switchError = null
                                        if (registering) {
                                            createError = ADDING_IN_PROGRESS
                                            return@ProjectSheet
                                        }
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { graph.registry.create(name) }
                                            result
                                                .onSuccess { ref ->
                                                    try {
                                                        graph.openProject(ref)
                                                        createError = null
                                                    } catch (e: Exception) {
                                                        Log.e("MainActivity", "Failed to open new project", e)
                                                        createError = "Could not open the new project"
                                                    }
                                                }
                                                .onFailure { createError = createErrorMessage(it) }
                                            refreshProjects()
                                        }
                                    },
                                    onSwitch = { ref ->
                                        switchError = null
                                        if (registering) {
                                            switchError = ADDING_IN_PROGRESS
                                            return@ProjectSheet
                                        }
                                        scope.launch {
                                            try {
                                                graph.openProject(ref)
                                                showSheet = false
                                            } catch (e: ProjectMissingException) {
                                                switchError = "That project's folder is missing"
                                                refreshProjects()
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "Failed to open project", e)
                                                switchError = "Could not open that project"
                                            }
                                        }
                                    },
                                    onAddFolder = { folderPicker.launch(null) },
                                    onAddFiles = { filesPicker.launch(arrayOf("image/*", "video/*")) },
                                    onDismiss = { showSheet = false },
                                    onNameChange = { createError = null },
                                )
                            }
                        }
                        Screen.Settings -> {
                            BackHandler { screen = Screen.Analyze }
                            SettingsScreen(
                                settings = settings,
                                onThemeMode = { mode -> scope.launch { safeWrite { graph.settings.setThemeMode(mode) } } },
                                onKeepScreenOn = { on -> scope.launch { safeWrite { graph.settings.setKeepScreenOn(on) } } },
                                onBack = { screen = Screen.Analyze },
                            )
                        }
                    }
                }
            }
        }
    }
}
