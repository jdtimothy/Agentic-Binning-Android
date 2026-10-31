package com.agentic.binning.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ThemeMode { System, Dark, Light }

@Composable
fun AgenticTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val tokens = if (dark) DarkGlass else LightGlass
    val scheme = if (dark) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.onAccent,
            background = tokens.bg,
            onBackground = tokens.text,
            surface = tokens.bg,
            onSurface = tokens.text,
            onSurfaceVariant = tokens.sub,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.onAccent,
            background = tokens.bg,
            onBackground = tokens.text,
            surface = tokens.bg,
            onSurface = tokens.text,
            onSurfaceVariant = tokens.sub,
        )
    }
    CompositionLocalProvider(LocalGlassTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
