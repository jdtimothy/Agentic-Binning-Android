package com.agentic.binning.analysis

import java.util.Locale

/** Sizes the vision model's context for the user's frame cap, and describes what a cap means for a clip. */
object VisionBudget {
    /** What a 16-frame cap needed in the field (Instructions.md); never go below it. */
    const val MIN_CONTEXT = 16384

    private const val CONTEXT_STEP = 4096

    /** Each frame's timestamp text and media marker, on top of the image tokens. */
    private const val TOKENS_PER_FRAME_EXTRA = 12

    /** The system prompt, the instruction and a 520-token reply, with margin. */
    private const val FIXED_TOKENS = 2048

    /** The clip length [hint] describes, in seconds. */
    private const val HINT_CLIP_SECONDS = 60

    /**
     * `nCtx` for a model that reads up to [maxFrames] frames of [tokensPerFrame] tokens each:
     * `maxFrames * (tokensPerFrame + 12) + 2048` rounded up to a multiple of 4096, at least [MIN_CONTEXT].
     */
    fun contextFor(maxFrames: Int, tokensPerFrame: Int): Int {
        val needed = maxFrames.toLong() * (tokensPerFrame + TOKENS_PER_FRAME_EXTRA) + FIXED_TOKENS
        val rounded = (needed + CONTEXT_STEP - 1) / CONTEXT_STEP * CONTEXT_STEP
        return maxOf(MIN_CONTEXT.toLong(), rounded).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** What the cap does to a 1-minute clip: 2 fps would give 120 frames, so a lower cap spaces them out. */
    fun hint(maxFrames: Int): String {
        val frames = minOf(HINT_CLIP_SECONDS * VideoTiming.FPS.toInt(), maxFrames).coerceAtLeast(1)
        val interval = String.format(Locale.US, "%.1f", HINT_CLIP_SECONDS.toDouble() / frames)
        return "1-minute clip: $frames frames, one every $interval s"
    }
}
