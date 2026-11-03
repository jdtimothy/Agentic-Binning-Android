package com.agentic.binning.ui.glass

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Width of the mockup frame; the layout's dp values scale with the window width relative to it. */
private const val MOCKUP_WIDTH_DP = 390f

/** Scales dp and sp so the 390 dp mockup frame fills the window's width. */
@Composable
fun MockupScale(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = (maxWidth.value / MOCKUP_WIDTH_DP).coerceIn(0.8f, 1.25f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            content()
        }
    }
}
