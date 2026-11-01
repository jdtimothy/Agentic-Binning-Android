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

    // Run-level errors (PipelineState.error): fixed sentences for people. The raw exception text
    // (paths, ENOSPC, SDK errors) goes to the log and the exception's cause only.

    /** The app's own work files or thumbnails could not be written. */
    const val OUT_OF_SPACE = "The phone ran out of space for analysis files"

    /** The model failed while reading an item. */
    const val MODEL_FAILED = "The vision model failed"

    /** The model could not be loaded onto the NPU. */
    const val MODEL_NOT_STARTED = "The vision model couldn't start"

    /** The model could not be fetched. */
    const val MODEL_NOT_DOWNLOADED = "The vision model couldn't be downloaded"

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
