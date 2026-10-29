package com.agentic.binning.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.settings.AppSettings
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.glass.GlassIconButton
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.theme.LocalGlassTokens
import com.agentic.binning.ui.theme.ThemeMode
import dev.chrisbanes.haze.HazeState

/** Backdrop blob colours shared by the top-level screens. */
val AmbientBlobColors = listOf(Color(0xFF6CB6FF), Color(0xFF9B7BFF), Color(0xFF3DD6B0))

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeMode: (ThemeMode) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop(AmbientBlobColors, hazeState)
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(onClick = onBack, contentDescription = "Back") {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = tokens.text)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Settings", color = tokens.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }

                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Theme", color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        ThemeSegmentedControl(settings.themeMode, onThemeMode)
                    }
                }

                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .toggleable(
                                value = settings.keepScreenOn,
                                role = Role.Switch,
                                onValueChange = onKeepScreenOn,
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Keep screen on", color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Keeps the screen on while the app is open and analysis is running",
                                color = tokens.sub,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.keepScreenOn,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tokens.onAccent,
                                checkedTrackColor = tokens.accent,
                                uncheckedThumbColor = tokens.text,
                                uncheckedTrackColor = tokens.track,
                                uncheckedBorderColor = tokens.border,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSegmentedControl(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.chip, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (isSelected) tokens.accent else Color.Transparent, CircleShape)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.name,
                    color = if (isSelected) tokens.onAccent else tokens.text,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
