package com.agentic.binning.pipeline

/** The codes stored in `item.failureReason`, and how the app words them for people. */
object FailureReasons {
    /** The user's file could not be decoded. */
    const val DECODE = "decode"

    /** The reply hit the token cap twice. */
    const val LENGTH = "length"

    /** The reply could not be parsed twice. */
    const val INVALID_JSON = "invalid-json"

    /** Prompt assembly or the media-marker check failed. */
    const val PROMPT = "prompt"

    /** Written before model errors stopped the run instead (older databases still hold it). */
    const val MODEL = "model"

    /** The process died while analyzing the item, more than once. */
    const val INTERRUPTED = "interrupted"

    /** A short sentence for [code]; unknown or missing codes get a generic one. */
    fun describe(code: String?): String = when (code) {
        DECODE -> "Couldn't read this file."
        LENGTH -> "The model's reply was cut off."
        INVALID_JSON -> "The model's reply wasn't readable."
        PROMPT -> "Couldn't build the model's prompt for this file."
        MODEL -> "The vision model failed on this file."
        INTERRUPTED -> "Stopped here last time the app closed unexpectedly."
        else -> "Couldn't analyze this file."
    }
}
