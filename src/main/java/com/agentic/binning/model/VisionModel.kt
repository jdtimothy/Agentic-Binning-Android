package com.agentic.binning.model

import com.agentic.binning.analysis.PreparedMedia

/** Numbers from GenieX's `ProfilingData` for one reply; log them for every job. */
data class VisionProfile(
    /** `eos`, `length`, `user` or `stop_sequence`. */
    val stopReason: String,
    val ttftMs: Double,
    val promptTokens: Int,
    val generatedTokens: Int,
    val decodingSpeed: Double,
)

data class VisionReply(val text: String, val profile: VisionProfile)

class VisionModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A vision-language model that analyses one prepared photo or video per call. */
interface VisionModel {
    /** Stored with each analysis, e.g. `LiquidAI/LFM3-VL-2B-GGUF:Q4_0`. */
    val modelId: String

    /** Square edge the vision encoder was trained at; valid after [load]. */
    val imageEdge: Int

    /**
     * Loads the model with a context sized for clips of up to [maxFrames] frames. A model already
     * loaded with a different context (larger or smaller) is reloaded; one with the same context stays.
     */
    suspend fun load(maxFrames: Int)

    /** Single-turn analysis of [media]; [onToken] receives each piece of the reply as it streams. */
    suspend fun generate(media: PreparedMedia, onToken: (String) -> Unit): VisionReply

    /** Asks a running [generate] to stop early (GenieX `stopStream`); its reply is then partial. */
    suspend fun cancel()

    /** Stops any generation and frees the model. Safe to call more than once. */
    suspend fun release()
}
