package com.agentic.binning

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.agentic.binning.settings.AppSettings
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.glass.GlassIconButton
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.settings.AmbientBlobColors
import com.agentic.binning.ui.settings.SettingsScreen
import com.agentic.binning.ui.theme.AgenticTheme
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState
import java.io.IOException
import kotlinx.coroutines.launch

private enum class Screen { Start, Settings }

/** Runs a settings write, logging instead of crashing if the store cannot be written. */
private suspend fun safeWrite(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        Log.e("MainActivity", "Failed to persist setting", e)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as BinningApp).graph
        setContent {
            val settings by graph.settings.settings.collectAsState(initial = AppSettings())
            val scope = rememberCoroutineScope()
            var screen by remember { mutableStateOf(Screen.Start) }

            // The pipeline-running condition arrives in Task 11; until then the setting alone decides.
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            AgenticTheme(settings.themeMode) {
                when (screen) {
                    Screen.Start -> StartPlaceholder(onOpenSettings = { screen = Screen.Settings })
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

/** Temporary start screen; replaced by the Analyze screen in Task 12. */
@Composable
private fun StartPlaceholder(onOpenSettings: () -> Unit) {
    val hazeState = remember { HazeState() }
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop(AmbientBlobColors, hazeState)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
            ) {
                GlassIconButton(onClick = onOpenSettings, contentDescription = "Settings") {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = LocalGlassTokens.current.text)
                }
            }
        }
    }
}
