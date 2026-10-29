package com.agentic.binning.ui.analyze

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.settings.AmbientBlobColors
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded edge of the backdrop image: tiny, since it is blurred to abstract colour anyway. */
private const val BACKDROP_DECODE_PX = 96

/** The blurred backdrop's long edge in pixels; the GPU only upscales it, so no per-frame blur. */
private const val ABSTRACT_EDGE_PX = 20
private const val BLUR_RADIUS_PX = 3
private const val BLUR_PASSES = 3
private const val CROSSFADE_MILLIS = 2300
private const val DRIFT_MILLIS = 20_000

/**
 * The screen's backdrop: [thumbnail] scaled up to fill the screen, heavily blurred and covered by a
 * theme scrim so the glass on top stays legible. A new thumbnail crossfades in slowly, and the image
 * drifts and breathes while one item is processed. The last image stays until the next one has
 * loaded; before any thumbnail exists the colour-orb backdrop shows instead. It is the Haze source
 * that the glass panels blur.
 */
@Composable
fun ThumbnailBackdrop(thumbnail: File?, hazeState: HazeState) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumbnail) {
        val file = thumbnail ?: return@LaunchedEffect
        try {
            val request = ImageRequest.Builder(context)
                .data(file)
                .size(BACKDROP_DECODE_PX)
                .allowHardware(false) // Pixels are read for the blur.
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            val bitmap = result?.image?.toBitmap() ?: return@LaunchedEffect
            shown = withContext(Dispatchers.Default) { abstractColour(bitmap) }.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ThumbnailBackdrop", "Cannot load ${file.name}", e)
        }
    }
    val image = shown
    if (image == null) {
        AmbientBackdrop(AmbientBlobColors, hazeState)
        return
    }

    val drift = rememberInfiniteTransition(label = "drift")
    val scale by drift.animateFloat(
        initialValue = 1.08f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(tween(DRIFT_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )
    val pan by drift.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DRIFT_MILLIS * 3 / 2, easing = LinearEasing), RepeatMode.Reverse),
        label = "pan",
    )
    // Dark: darken towards the background; light: wash towards it.
    val scrim = tokens.bg.copy(alpha = if (tokens.bg.luminance() < 0.5f) 0.5f else 0.42f)

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.bg)
            .hazeSource(hazeState),
    ) {
        Crossfade(image, animationSpec = tween(CROSSFADE_MILLIS), label = "backdrop") { bitmap ->
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // Read in the draw phase, so the drift does not recompose.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan * size.width * 0.03f
                        translationY = -pan * size.height * 0.02f
                    },
                // Bilinear upscaling of the tiny blurred bitmap keeps it smooth.
                filterQuality = FilterQuality.High,
            )
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

/**
 * Shrinks [src] to [ABSTRACT_EDGE_PX] on its long edge and box-blurs it ([BLUR_PASSES] passes,
 * close to a Gaussian), leaving only soft colour fields. Done once per item on the CPU, so drawing
 * the backdrop costs no blur on the GPU.
 */
private fun abstractColour(src: Bitmap): Bitmap {
    val scale = ABSTRACT_EDGE_PX.toFloat() / maxOf(src.width, src.height)
    val w = (src.width * scale).roundToInt().coerceAtLeast(2)
    val h = (src.height * scale).roundToInt().coerceAtLeast(2)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== src) small.recycle()
    repeat(BLUR_PASSES) {
        boxBlur(px, w, h, horizontal = true)
        boxBlur(px, w, h, horizontal = false)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** One box-blur pass along rows or columns, clamping at the edges. */
private fun boxBlur(px: IntArray, w: Int, h: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val line = IntArray(len)
    for (l in 0 until lines) {
        for (i in 0 until len) line[i] = px[if (horizontal) l * w + i else i * w + l]
        for (i in 0 until len) {
            var r = 0
            var g = 0
            var b = 0
            for (k in -BLUR_RADIUS_PX..BLUR_RADIUS_PX) {
                val c = line[(i + k).coerceIn(0, len - 1)]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val n = 2 * BLUR_RADIUS_PX + 1
            px[if (horizontal) l * w + i else i * w + l] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
}

