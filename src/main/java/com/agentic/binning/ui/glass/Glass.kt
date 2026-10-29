package com.agentic.binning.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * The [HazeState] of the enclosing [AmbientBackdrop]. Screens provide it so that
 * [GlassSurface] can blur the backdrop behind it; without it surfaces fall back
 * to a plain translucent fill.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

private val GlassBlurRadius = 26.dp

/** A translucent, blurred panel with a thin light border and a top highlight. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val haze = LocalHazeState.current
    val fill = if (strong) tokens.glassStrong else tokens.glass
    val highlight = tokens.highlight

    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (haze != null) {
                    Modifier.hazeEffect(state = haze) { blurRadius = GlassBlurRadius }
                } else {
                    Modifier
                },
            )
            .background(fill)
            .drawWithContent {
                drawContent()
                // 1 dp top inner highlight, fading out towards the corners.
                val stroke = 1.dp.toPx()
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlight, highlight, Color.Transparent),
                        startX = 0f,
                        endX = size.width,
                    ),
                    start = Offset(0f, stroke / 2f),
                    end = Offset(size.width, stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .border(1.dp, tokens.border, shape),
        content = content,
    )
}

/** A 44 dp circular glass button. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Full-size ambient background: the theme base colour with three large blurred
 * colour blobs. It is the Haze source that [GlassSurface]s blur.
 */
@Composable
fun AmbientBackdrop(colors: List<Color>, hazeState: HazeState) {
    val tokens = LocalGlassTokens.current
    val c0 by animateColorAsState(colors.getOrElse(0) { tokens.accent }, tween(1400), label = "blob0")
    val c1 by animateColorAsState(colors.getOrElse(1) { colors.getOrElse(0) { tokens.accent } }, tween(1400), label = "blob1")
    val c2 by animateColorAsState(colors.getOrElse(2) { colors.getOrElse(1) { colors.getOrElse(0) { tokens.accent } } }, tween(1400), label = "blob2")

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.bg)
            .hazeSource(hazeState),
    ) {
        Blob(c0, 340.dp, 80.dp, Alignment.TopStart, (-90).dp, (-60).dp, tokens.blobAlpha)
        Blob(c1, 320.dp, 76.dp, Alignment.CenterEnd, 110.dp, 40.dp, tokens.blobAlpha)
        Blob(c2, 300.dp, 72.dp, Alignment.BottomStart, (-60).dp, 90.dp, tokens.blobAlpha)
    }
}

@Composable
private fun BoxScope.Blob(
    color: Color,
    size: Dp,
    blurRadius: Dp,
    align: Alignment,
    x: Dp,
    y: Dp,
    alpha: Float,
) {
    Box(
        Modifier
            .align(align)
            .offset(x, y)
            .size(size)
            .alpha(alpha)
            .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
            .background(color, CircleShape),
    )
}
