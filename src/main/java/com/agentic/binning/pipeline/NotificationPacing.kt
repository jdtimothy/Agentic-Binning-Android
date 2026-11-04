package com.agentic.binning.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * Android drops a package's notification updates above 5 a second, and it may drop the one that
 * matters: moving small files can change the count every few milliseconds, and the Pause that follows
 * a burst was lost that way, leaving a paused run with a stale count and a Pause button. This runs
 * [action] at most once per [gapMs], and always for the latest value.
 */
internal suspend fun <T> Flow<T>.collectPaced(gapMs: Long, action: suspend (T) -> Unit) {
    conflate().collect {
        action(it)
        delay(gapMs)
    }
}

/** This flow at most once per [gapMs], always ending on the latest value; the UI reads a move through it. */
fun <T> Flow<T>.paced(gapMs: Long): Flow<T> = flow { collectPaced(gapMs) { emit(it) } }
