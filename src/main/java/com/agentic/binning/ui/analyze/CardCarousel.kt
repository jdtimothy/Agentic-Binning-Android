package com.agentic.binning.ui.analyze

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.LocalGlassTokens
import kotlin.math.PI
import kotlin.math.cos

private val CardWidth = 250.dp
private val CardHeight = 284.dp
private val MediaWidth = 228.dp
private val MediaHeight = 196.dp
private val BadgeBg = Color(0x73000000) // rgba(0,0,0,0.45), both themes

/** Coil request for a card's picture (a frame is decoded for a video without a thumbnail yet). */
private fun cardImageRequest(context: Context, card: CardItem): ImageRequest =
    ImageRequest.Builder(context)
        .data(card.image)
        .apply { if (card.imageIsVideo) decoderFactory(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()

/**
 * The 3D card carousel: the current item in the centre, the next one tilted and dimmed on the right,
 * the finished one on the left. Cards glide between [cardPose]s when the current item changes.
 */
@Composable
fun CardCarousel(
    state: CarouselState,
    progress: CurrentProgress,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalGlassTokens.current
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        if (state.cards.isEmpty()) {
            EmptyCard(onAddMedia)
            return@Box
        }
        val first = (state.current - 2).coerceAtLeast(0)
        val last = (state.current + 2).coerceAtMost(state.cards.lastIndex)
        for (i in first..last) {
            val card = state.cards[i]
            key(card.id) {
                val live = progress.takeIf { it.itemId == card.id }
                PosedCard(cardPose(i - state.current, state.cards.size, tokens)) {
                    CardBody(card, live)
                }
            }
        }
    }
}

@Composable
private fun PosedCard(pose: CardPose, content: @Composable () -> Unit) {
    val spec = tween<Float>(CARD_POSE_MILLIS, easing = CardPoseEasing)
    val tx by animateFloatAsState(pose.translationXDp, spec, label = "tx")
    val ry by animateFloatAsState(pose.rotationYDeg, spec, label = "ry")
    val sc by animateFloatAsState(pose.scale, spec, label = "scale")
    val al by animateFloatAsState(pose.alpha, spec, label = "alpha")
    val br by animateFloatAsState(pose.brightness, spec, label = "brightness")
    Box(
        Modifier
            .zIndex(pose.z)
            .size(CardWidth, CardHeight)
            .graphicsLayer {
                translationX = tx * density
                rotationY = ry
                scaleX = sc
                scaleY = sc
                alpha = al
                cameraDistance = 12 * density
                renderEffect = brightnessEffect(br)
            },
    ) { content() }
}

/** Scales the card's RGB by [brightness] (the mockup's `filter: brightness()`). */
private fun brightnessEffect(brightness: Float): RenderEffect? {
    if (brightness >= 0.999f) return null
    val matrix = ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) }
    return android.graphics.RenderEffect
        .createColorFilterEffect(ColorFilter.colorMatrix(matrix).asAndroidColorFilter())
        .asComposeRenderEffect()
}

@Composable
private fun CardBody(card: CardItem, live: CurrentProgress?) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    val request = remember(card.image, card.imageIsVideo) { cardImageRequest(context, card) }
    GlassSurface(Modifier.fillMaxSize(), shape = RoundedCornerShape(30.dp), strong = true) {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier
                    .size(MediaWidth, MediaHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(tokens.chip),
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (live?.scanning == true) ScanLine()
                if (card.kind == ItemKind.Video && card.duration != null) {
                    Badge(Modifier.align(Alignment.BottomEnd).padding(8.dp), AnalyzeIcons.Play, 10, card.duration)
                }
                when (card.status) {
                    ItemStatus.Analyzed, ItemStatus.Sorted, ItemStatus.Binned ->
                        Badge(Modifier.align(Alignment.TopStart).padding(8.dp), AnalyzeIcons.Check, 11, "Analyzed")
                    ItemStatus.Failed ->
                        Badge(Modifier.align(Alignment.TopStart).padding(8.dp), AnalyzeIcons.Alert, 11, "Failed")
                    ItemStatus.Missing ->
                        Badge(Modifier.align(Alignment.TopStart).padding(8.dp), AnalyzeIcons.Alert, 11, "Missing")
                    ItemStatus.Pending, ItemStatus.Analyzing -> Unit
                }
            }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)) {
                Text(
                    card.name,
                    color = tokens.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.meta,
                    modifier = Modifier.padding(top = 2.dp),
                    color = tokens.sub,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (card.kind == ItemKind.Video) {
                    val ticks = card.frameCount
                    when {
                        card.isAnalyzed || live?.stage == FrameStage.Read -> FrameStrip(ticks, ticks)
                        live?.stage == FrameStage.Encoding -> FrameStrip(ticks, ticks, shimmer = true)
                        live != null -> FrameStrip(ticks, live.framesReady.coerceAtMost(ticks))
                        else -> FrameStrip(ticks, 0)
                    }
                } else {
                    ProgressBar(fraction = if (card.isAnalyzed) 1f else live?.fraction ?: 0f)
                }
            }
        }
    }
}

@Composable
private fun Badge(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, iconDp: Int, text: String) {
    Row(
        modifier
            .background(BadgeBg, RoundedCornerShape(10.dp))
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconDp.dp))
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp)
    }
}

/**
 * One tick per frame the model reads ([count], at most 16). Ticks turn blue as frames are extracted;
 * with [shimmer] a soft pulse sweeps across them while the model encodes them all in one pass.
 */
@Composable
private fun FrameStrip(count: Int, filled: Int, shimmer: Boolean = false) {
    val tokens = LocalGlassTokens.current
    val phase = if (shimmer) {
        rememberInfiniteTransition(label = "frames").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "shimmer",
        )
    } else {
        null
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count.coerceAtLeast(1)) { k ->
            val color by animateColorAsState(if (k < filled) tokens.accent else tokens.track, tween(220), label = "tick")
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .graphicsLayer {
                        // Read in the draw phase only: the pulse never recomposes the card.
                        if (phase != null) {
                            val wave = (1f + cos(2f * PI.toFloat() * (phase.value - k.toFloat() / count))) / 2f
                            alpha = 0.55f + 0.45f * wave * wave
                        }
                    }
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val tokens = LocalGlassTokens.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "progress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(tokens.track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(6.dp)
                .background(tokens.accent, RoundedCornerShape(3.dp)),
        )
    }
}

/** The mockup's scan line: a glowing accent line sweeping down the picture every 1.8 s. */
@Composable
private fun BoxScope.ScanLine() {
    val accent = LocalGlassTokens.current.accent
    val t by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "scanT",
    )
    val y = ScanEase.transform(t)
    val alpha = when {
        t < 0.15f -> t / 0.15f
        t > 0.85f -> (1f - t) / 0.15f
        else -> 1f
    }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(2.dp)
            .graphicsLayer {
                translationY = y * MediaHeight.toPx()
                this.alpha = alpha
            }
            .drawBehind {
                val glow = 14.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to accent.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                        startY = -glow,
                        endY = size.height + glow,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, -glow),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height + 2 * glow),
                )
                drawRect(accent)
            },
    )
}

/** CSS `ease-in-out`. */
private val ScanEase = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Shown when the project has no media: a card that opens the project sheet. */
@Composable
private fun EmptyCard(onAddMedia: () -> Unit) {
    val tokens = LocalGlassTokens.current
    GlassSurface(
        Modifier
            .size(CardWidth, CardHeight)
            .clip(RoundedCornerShape(30.dp))
            .clickable(role = Role.Button, onClickLabel = "Add media", onClick = onAddMedia),
        shape = RoundedCornerShape(30.dp),
        strong = true,
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(AnalyzeIcons.Folder, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(36.dp))
            Text("Add media", color = tokens.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Pick a folder or photos and clips", color = tokens.sub, fontSize = 13.sp)
        }
    }
}
