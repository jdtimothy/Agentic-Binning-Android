package com.agentic.binning.analysis

import java.util.Locale

/**
 * Frame sampling and timestamp rules for video input, matching llama.cpp's
 * video helper (mtmd-helper.cpp) for LFM3-VL: frames at 2 fps, a timestamp
 * text after the first frame at or past each 5 s mark, starting with frame 0.
 */
object VideoTiming {
    /** LFM3-VL was trained on video sampled at 2 fps (model card). */
    const val FPS = 2f

    private const val TIMESTAMP_INTERVAL_MS = 5000L

    /**
     * Sample times for a clip: every 1/[FPS] s from the start. Clips that would
     * exceed [maxFrames] are spread evenly over their full length instead of
     * being cut short. A non-positive duration yields a single frame at 0.
     */
    fun sampleTimesMs(
        durationMs: Long,
        maxFrames: Int,
    ): List<Long> {
        if (durationMs <= 0) return listOf(0L)
        val stepMs = (1000f / FPS).toLong()
        // Frames at 0, step, 2*step, ... strictly before the end of the clip.
        val atFps = ((durationMs + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
        if (atFps <= maxFrames) return (0 until atFps).map { it * stepMs }
        return (0 until maxFrames).map { i -> durationMs * i / maxFrames }
    }

    /**
     * Indices of [timesMs] that get a timestamp after them, reproducing
     * llama.cpp's rule: the first frame whose time reaches each multiple of
     * the 5 s interval is labelled, starting with frame 0.
     */
    fun timestampedIndices(timesMs: List<Long>): Set<Int> {
        val labelled = mutableSetOf<Int>()
        var next = 0L
        timesMs.forEachIndexed { i, t ->
            if (t >= next) {
                labelled.add(i)
                next += TIMESTAMP_INTERVAL_MS
            }
        }
        return labelled
    }

    /** Same format as llama.cpp's video helper: `[%dm%.2fs]`. */
    fun format(timeMs: Long): String {
        val seconds = timeMs / 1000f
        val minutes = (seconds / 60).toInt()
        return String.format(Locale.US, "[%dm%.2fs]", minutes, seconds - minutes * 60)
    }
}
