package com.agentic.binning.analysis

/**
 * Builds the interleaved media text the model expects. GenieX's chat template
 * hoists all media markers ahead of text, so the prompt is templated with
 * [PLACEHOLDER] and the interleaved string is spliced into the formatted text
 * afterwards.
 */
object PromptAssembly {
    const val MEDIA_MARKER = "<__media__>"
    const val PLACEHOLDER = "@@GENIEX_MEDIA_TURN@@0@@"

    /** Text llama.cpp's video helper emits before the first frame. */
    private const val VIDEO_PROMPT_START = "Video:"

    /** `Video:` then one marker per frame, with a timestamp after the labelled frames. */
    fun videoMediaText(timesMs: List<Long>): String {
        val stamped = VideoTiming.timestampedIndices(timesMs)
        return buildString {
            append(VIDEO_PROMPT_START)
            timesMs.forEachIndexed { i, t ->
                append(MEDIA_MARKER)
                if (i in stamped) append(VideoTiming.format(t))
            }
        }
    }

    fun photoMediaText(): String = MEDIA_MARKER

    /** Replaces the single [PLACEHOLDER] in [formattedText] with [mediaText], a newline and [instruction]. */
    fun splice(
        formattedText: String,
        mediaText: String,
        instruction: String,
    ): String {
        val first = formattedText.indexOf(PLACEHOLDER)
        check(first >= 0) { "Placeholder missing from formatted prompt" }
        check(formattedText.indexOf(PLACEHOLDER, first + PLACEHOLDER.length) < 0) {
            "Placeholder appears more than once in formatted prompt"
        }
        return formattedText.substring(0, first) + mediaText + "\n" + instruction +
            formattedText.substring(first + PLACEHOLDER.length)
    }
}
