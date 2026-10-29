package com.agentic.binning

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.media.RegistrationProgress
import com.agentic.binning.media.RegistrationSummary
import com.agentic.binning.project.ProjectExistsException
import com.agentic.binning.project.ProjectMissingException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.AppSettings
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.glass.GlassIconButton
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.permission.StorageAccessGate
import com.agentic.binning.ui.project.ProjectSheet
import com.agentic.binning.ui.project.ProjectSheetState
import com.agentic.binning.ui.settings.AmbientBlobColors
import com.agentic.binning.ui.settings.SettingsScreen
import com.agentic.binning.ui.theme.AgenticTheme
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Start, Settings }

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as BinningApp).graph
        setContent {
            val settings by graph.settings.settings.collectAsState(initial = AppSettings())
            val scope = rememberCoroutineScope()
            var screen by remember { mutableStateOf(Screen.Start) }

            val pipelineState by graph.pipeline.state.collectAsState()
            // Keep the screen on only while analysis runs (Paused counts), and only if the setting allows it.
            val keepScreenOn = settings.keepScreenOn && pipelineState.phase.isRunning
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

                    when (screen) {
                        Screen.Start -> {
                            StartPlaceholder(
                                projectLabel = openProject?.ref?.name ?: "Create project",
                                onOpenProjects = {
                                    createError = null
                                    switchError = null
                                    if (!registering) registrationSummary = null
                                    refreshProjects()
                                    showSheet = true
                                },
                                onOpenSettings = { screen = Screen.Settings },
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
                            BackHandler { screen = Screen.Start }
                            SettingsScreen(
                                settings = settings,
                                onThemeMode = { mode -> scope.launch { safeWrite { graph.settings.setThemeMode(mode) } } },
                                onKeepScreenOn = { on -> scope.launch { safeWrite { graph.settings.setKeepScreenOn(on) } } },
                                onBack = { screen = Screen.Start },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Temporary start screen; replaced by the Analyze screen in Task 12. */
@Composable
private fun StartPlaceholder(projectLabel: String, onOpenProjects: () -> Unit, onOpenSettings: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val hazeState = remember { HazeState() }
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop(AmbientBlobColors, hazeState)
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSurface(
                    Modifier
                        .widthIn(max = 260.dp)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClick = onOpenProjects),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(
                        projectLabel,
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 11.dp)
                            .align(Alignment.Center),
                        color = tokens.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.weight(1f))
                GlassIconButton(onClick = onOpenSettings, contentDescription = "Settings") {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = tokens.text)
                }
            }
        }
    }
}
