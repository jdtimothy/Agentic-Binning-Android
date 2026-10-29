// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.agentic.binning.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.util.Locale

/**
 * Turns picked media into bitmaps the VLM can consume. GenieX 0.10.0 has no
 * video input, so a video is sent the way llama.cpp's own video helper sends
 * it to LFM3-VL (which has no temporal frame merging): a "Video:" text chunk,
 * then frames at 2 fps, each followed by a timestamp every 5 s.
 */
object MediaUtil {
    private const val TAG = "MediaUtil"

    /** Decoded frames are capped at this edge; squareCrop shrinks further per model. */
    private const val MAX_DECODE_EDGE = 1536

    /** LFM3-VL was trained on video sampled at 2 fps (model card). */
    const val VIDEO_FPS = 2f

    /**
     * llama.cpp's video helper (mtmd-helper.cpp) emits a timestamp text chunk
     * after the first frame at or past each multiple of this interval.
     */
    private const val TIMESTAMP_INTERVAL_MS = 5000L

    /** Text llama.cpp's video helper emits before the first frame. */
    const val VIDEO_PROMPT_START = "Video:"

    data class VideoFrame(
        val bitmap: Bitmap,
        val timeMs: Long,
    )

    data class VideoFrames(
        val frames: List<VideoFrame>,
        val durationMs: Long,
    )

    /** Same format as llama.cpp's video helper: `[%dm%.2fs]`. */
    fun formatTimestamp(timeMs: Long): String {
        val seconds = timeMs / 1000f
        val minutes = (seconds / 60).toInt()
        return String.format(Locale.US, "[%dm%.2fs]", minutes, seconds - minutes * 60)
    }

    /**
     * Indices of [frameTimesMs] that get a timestamp after them, reproducing
     * llama.cpp's rule: the first frame whose time reaches each multiple of
     * [TIMESTAMP_INTERVAL_MS] is labelled, starting with frame 0.
     */
    fun timestampedFrameIndices(frameTimesMs: List<Long>): Set<Int> {
        val labelled = mutableSetOf<Int>()
        var next = 0L
        frameTimesMs.forEachIndexed { i, t ->
            if (t >= next) {
                labelled.add(i)
                next += TIMESTAMP_INTERVAL_MS
            }
        }
        return labelled
    }

    fun isVideo(
        context: Context,
        uri: Uri,
    ): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true

    /** Decodes an image with EXIF rotation applied, downsampled so huge photos don't OOM. */
    fun decodeImage(
        context: Context,
        uri: Uri,
    ): Bitmap? =
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DECODE_EDGE) {
                    val scale = MAX_DECODE_EDGE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt(),
                        (info.size.height * scale).toInt(),
                    )
                }
            }
        }.onFailure { Log.e(TAG, "decodeImage failed for $uri", it) }.getOrNull()

    /**
     * Sample times for a clip: every 1/[VIDEO_FPS] s from the start, like
     * llama.cpp's helper. Clips that would exceed [maxFrames] are spread
     * evenly over their full length instead of being cut short.
     */
    fun sampleTimesMs(
        durationMs: Long,
        maxFrames: Int,
    ): List<Long> {
        val stepMs = (1000f / VIDEO_FPS).toLong()
        // Frames at 0, step, 2*step, ... strictly before the end of the clip.
        val atFps = ((durationMs + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
        if (atFps <= maxFrames) return (0 until atFps).map { it * stepMs }
        return (0 until maxFrames).map { i -> durationMs * i / maxFrames }
    }

    fun extractVideoFrames(
        context: Context,
        uri: Uri,
        maxFrames: Int,
    ): VideoFrames? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            val frames =
                sampleTimesMs(durationMs, maxFrames).mapNotNull { timeMs ->
                    // OPTION_CLOSEST decodes the exact frame; CLOSEST_SYNC snaps to
                    // keyframes, which collapses every sample onto frame 0 for
                    // clips with a long GOP.
                    retriever
                        .getScaledFrameAtTime(
                            timeMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            MAX_DECODE_EDGE,
                            MAX_DECODE_EDGE,
                        )?.let { VideoFrame(padToSquare(it), timeMs) }
                }
            if (frames.isEmpty()) null else VideoFrames(frames, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "extractVideoFrames failed for $uri", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Letterboxes [src] onto a black square. The VLM preprocessing centre-crops
     * to a square, which would cut the sides off a 16:9 frame — and motion
     * across the frame is exactly what a video question tends to be about.
     */
    private fun padToSquare(src: Bitmap): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (src.width == src.height) return src
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, (edge - src.width) / 2f, (edge - src.height) / 2f, null)
        }
        return out
    }
}
