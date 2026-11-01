package com.agentic.binning.ui.analyze

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

/** Width of the mockup frame; the layout's dp values scale with the window width relative to it. */
private const val MOCKUP_WIDTH_DP = 390f

/** The main screen: carousel of the project's items, streamed analysis output and run controls. */
@Composable
fun AnalyzeScreen(vm: AnalyzeViewModel, onOpenSettings: () -> Unit, onOpenProjectSheet: () -> Unit) {
    val header by vm.header.collectAsState()
    val carousel by vm.carousel.collectAsState()
    // Read only where needed, so a streamed token recomposes the output card and the live card alone.
    val progress = vm.progress.collectAsState()
    val controls by vm.controls.collectAsState()
    val hazeState = remember { HazeState() }

    // The mockup's text runs at the font's natural line height without Material's extra tracking.
    val textStyle = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)
    CompositionLocalProvider(LocalHazeState provides hazeState, LocalTextStyle provides textStyle) {
        Box(Modifier.fillMaxSize()) {
            ThumbnailBackdrop(
                projectKey = carousel.projectPath,
                hasFocus = carousel.focus != null,
                thumbnail = carousel.focus?.thumbnail,
                // Drift only while items are being analyzed: a moving backdrop re-blurs every panel.
                moving = controls.canStop && !controls.showResume,
                hazeState = hazeState,
            )
            MockupScale {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    TopBar(header, onOpenProjectSheet, onOpenSettings, Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(14.dp))
                    StatusRow(header, Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(14.dp))
                    CardCarousel(carousel, progress, onOpenProjectSheet, Modifier.fillMaxWidth().height(290.dp))
                    Spacer(Modifier.height(12.dp))
                    LiveOutputCard(vm.output, Modifier.padding(horizontal = 16.dp).fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(14.dp))
                    BottomBar(controls, vm::stop, vm::togglePause, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** Collects the per-token output state in its own scope. */
@Composable
private fun LiveOutputCard(output: StateFlow<OutputState>, modifier: Modifier) {
    val state by output.collectAsState()
    OutputCard(state, modifier)
}

/** Scales dp and sp so the 390 dp mockup frame fills the window's width. */
@Composable
private fun MockupScale(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = (maxWidth.value / MOCKUP_WIDTH_DP).coerceIn(0.8f, 1.25f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            content()
        }
    }
}

@Composable
private fun StatusRow(header: HeaderState, modifier: Modifier = Modifier) {
    val tokens = LocalGlassTokens.current
    val dot = when (header.statusTone) {
        StatusTone.Active -> tokens.accent
        StatusTone.Paused -> tokens.warn
        StatusTone.Quiet -> tokens.sub
    }
    val numbers = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum")
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Text(
                    header.statusText,
                    modifier = Modifier.padding(start = 7.dp).weight(1f, fill = false),
                    color = tokens.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Two lines rather than cutting a status short on a narrow phone.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(header.speedText, color = tokens.sub, style = numbers, maxLines = 1)
            Text(header.countText, color = tokens.sub, style = numbers, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) { measurables, constraints ->
        val (status, speed, count) = measurables
        val width = constraints.maxWidth
        val gap = 10.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val countP = count.measure(loose.copy(maxWidth = width / 2))
        val speedP = speed.measure(loose)
        // The speed sits in the middle of the row, nudged left if the count reaches past the middle;
        // the phase text takes what is left and ellipsizes.
        val speedX = if (speedP.width == 0) {
            width - countP.width
        } else {
            minOf((width - speedP.width) / 2, width - countP.width - gap - speedP.width)
        }
        val statusP = status.measure(loose.copy(maxWidth = (speedX - gap).coerceAtLeast(0)))
        val height = maxOf(statusP.height, speedP.height, countP.height)
        layout(width, height) {
            statusP.place(0, (height - statusP.height) / 2)
            speedP.place(speedX, (height - speedP.height) / 2)
            countP.place(width - countP.width, (height - countP.height) / 2)
        }
    }
}
