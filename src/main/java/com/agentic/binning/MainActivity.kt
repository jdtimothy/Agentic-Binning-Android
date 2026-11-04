package com.agentic.binning

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.agentic.binning.model.ModelCatalog
import com.agentic.binning.pipeline.RunKind
import com.agentic.binning.projectmedia.RESORT_NOT_OPEN
import com.agentic.binning.projectmedia.ResortRequest
import com.agentic.binning.projectmedia.ResortStart
import com.agentic.binning.settings.AppSettings
import com.agentic.binning.settings.MediaPickerChoice
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.ui.analyze.AnalyzeScreen
import com.agentic.binning.ui.analyze.AnalyzeViewModel
import com.agentic.binning.pipeline.paced
import com.agentic.binning.ui.media.BinFilesViewModel
import com.agentic.binning.ui.media.BinPipelineView
import com.agentic.binning.ui.media.ProjectMediaScreen
import com.agentic.binning.ui.media.ProjectMediaViewModel
import com.agentic.binning.ui.media.RepositoryMediaSource
import com.agentic.binning.ui.media.ResortActions
import com.agentic.binning.ui.permission.StorageAccessGate
import com.agentic.binning.ui.project.ProjectSheetHost
import com.agentic.binning.ui.project.rememberProjectSheetController
import com.agentic.binning.ui.settings.KeyRow
import com.agentic.binning.ui.settings.SettingsActions
import com.agentic.binning.ui.settings.SettingsScreen
import com.agentic.binning.ui.settings.specOf
import com.agentic.binning.ui.theme.AgenticTheme
import com.agentic.binning.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Screen { Analyze, Media, Settings }

/** Runs a settings write, logging instead of crashing if the store cannot be written. */
private suspend fun safeWrite(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        Log.e("MainActivity", "Failed to persist setting", e)
    }
}

/**
 * Runs an API key write. Keystore and store failures are logged by type only: the message of a
 * crypto exception could carry key material, and the key itself is never logged.
 */
private suspend fun saveApiKey(block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e("MainActivity", "Failed to save or remove the API key (${e.javaClass.simpleName})")
    false
}

/** A move can change its file every few milliseconds: the UI reads it at most this often, so a name can be read. */
private const val MOVE_UI_GAP_MS = 250L

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
            // Saved, so a configuration change (rotation, a theme switch) keeps the screen.
            var screen by rememberSaveable { mutableStateOf(Screen.Analyze) }
            // Settings goes back to whichever screen opened it.
            var beforeSettings by rememberSaveable { mutableStateOf(Screen.Analyze) }
            val openSettings = { from: Screen ->
                beforeSettings = from
                screen = Screen.Settings
            }

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
                    val projectSheet = rememberProjectSheetController(graph, scope)

                    val analyzeViewModel = viewModel {
                        AnalyzeViewModel(
                            pipeline = graph.pipeline,
                            currentProject = graph.currentProject,
                            modelState = graph.modelDownloader.state(ModelCatalog.LFM3_VL_2B),
                            resumeAnalysis = graph::resumeAnalysis,
                            stopAnalysis = graph::stopAnalysis,
                            maxFramesPerClip = graph.settings.settings.map { it.maxFramesPerClip },
                            sortingModel = graph.settings.settings.map { it.sortingModel },
                        )
                    }

                    val binViewModel = viewModel {
                        BinFilesViewModel(
                            view = graph.pipeline.state
                                .map { BinPipelineView(it.phase, it.moveProgress, it.moveEnded, it.moveOutcome, it.moveResumedFrom) }
                                .distinctUntilChanged()
                                .paced(MOVE_UI_GAP_MS),
                            actions = graph.currentProject.map { open -> open?.let(graph::binActions) },
                        )
                    }

                    val mediaViewModel = viewModel {
                        ProjectMediaViewModel(
                            source = graph.currentProject.map { open ->
                                open?.let { RepositoryMediaSource(it) { graph.startAnalysisAfterRetry(it) } }
                            },
                            phase = graph.pipeline.state.map { it.phase },
                            move = graph.pipeline.state.map { it.moveProgress }.distinctUntilChanged().paced(MOVE_UI_GAP_MS),
                            onCategorizeNow = {
                                graph.currentProject.value?.let { graph.startAnalysis(it.ref, RunKind.CategorizeOnly) }
                            },
                            onBinFiles = binViewModel::onBinFiles,
                            resort = object : ResortActions {
                                override suspend fun settingsChoice() = graph.settings.settings.first().sortingModel
                                override suspend fun unavailable(choice: SortingModelChoice) = graph.sortingUnavailable(choice)
                                override suspend fun start(request: ResortRequest): ResortStart {
                                    val open = graph.currentProject.value ?: return ResortStart.Refused(RESORT_NOT_OPEN)
                                    return graph.resort(open.ref, request)
                                }
                            },
                        )
                    }

                    when (screen) {
                        Screen.Analyze -> {
                            AnalyzeScreen(
                                vm = analyzeViewModel,
                                onOpenSettings = { openSettings(Screen.Analyze) },
                                onOpenProjectSheet = projectSheet::open,
                                onOpenMedia = { screen = Screen.Media },
                            )
                            ProjectSheetHost(projectSheet)
                        }
                        Screen.Media -> {
                            ProjectMediaScreen(
                                vm = mediaViewModel,
                                bin = binViewModel,
                                onBack = { screen = Screen.Analyze },
                                onOpenSettings = { openSettings(Screen.Media) },
                                // The empty project's buttons are the project sheet's own pickers.
                                onAddFolder = projectSheet::onPickFolder,
                                onPickFiles = projectSheet::onPickFiles,
                            )
                            ProjectSheetHost(projectSheet)
                        }
                        Screen.Settings -> {
                            BackHandler { screen = beforeSettings }
                            // Decrypting the key to show its last 4 is Keystore work: off the main thread.
                            val keyRow by remember {
                                graph.apiKeys.info.map { KeyRow(it) }.flowOn(Dispatchers.IO)
                            }.collectAsState(initial = null)
                            val sortingDownload by remember {
                                graph.modelDownloader.state(ModelCatalog.LFM3_1_7B).flowOn(Dispatchers.IO)
                            }.collectAsState(initial = null)
                            val actions = remember {
                                object : SettingsActions {
                                    override fun onThemeMode(mode: ThemeMode) {
                                        scope.launch { safeWrite { graph.settings.setThemeMode(mode) } }
                                    }

                                    override fun onKeepScreenOn(on: Boolean) {
                                        scope.launch { safeWrite { graph.settings.setKeepScreenOn(on) } }
                                    }

                                    override fun onSortingModel(choice: SortingModelChoice) {
                                        scope.launch { safeWrite { graph.settings.setSortingModel(choice) } }
                                    }

                                    override fun onDownloadSortingModel(choice: SortingModelChoice) {
                                        specOf(choice)?.let(graph::pullModel)
                                    }

                                    override fun onSaveKey(key: String, onResult: (Boolean) -> Unit) {
                                        scope.launch { onResult(saveApiKey { graph.apiKeys.save(key) }) }
                                    }

                                    override fun onRemoveKey() {
                                        scope.launch { saveApiKey { graph.apiKeys.clear() } }
                                    }

                                    override fun onMaxFramesPerClip(cap: Int) {
                                        scope.launch { safeWrite { graph.settings.setMaxFramesPerClip(cap) } }
                                    }

                                    override fun onMediaPicker(choice: MediaPickerChoice) {
                                        scope.launch { safeWrite { graph.settings.setMediaPicker(choice) } }
                                    }

                                    override fun onBack() {
                                        screen = beforeSettings
                                    }
                                }
                            }
                            SettingsScreen(
                                settings = settings,
                                keyRow = keyRow,
                                sortingDownload = sortingDownload,
                                actions = actions,
                            )
                        }
                    }
                }
            }
        }
    }
}
