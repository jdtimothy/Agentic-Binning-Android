package com.agentic.binning.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The kinds of work that load a model (or, for Binning, move files the models work on). */
enum class JobKind { Analysis, Sorting, Binning }

/**
 * Lets one model job run at a time, so the vision model and a sorting model are never loaded together
 * on the NPU. Waiting jobs run first come, first served (the [Mutex] is fair). The pipeline wraps
 * analysis batches and categorization in [withJob]; Re-sort (Plan 2b) and Bin Files (Plan 3) must too.
 */
class ModelArbiter {
    private val mutex = Mutex()
    private val _active = MutableStateFlow<JobKind?>(null)

    /** The job holding the models, or null when none is. */
    val active: StateFlow<JobKind?> = _active.asStateFlow()

    /** Runs [block] once every earlier job has finished; the next job starts when it returns or throws. */
    suspend fun <T> withJob(kind: JobKind, block: suspend () -> T): T = mutex.withLock {
        _active.value = kind
        try {
            block()
        } finally {
            _active.value = null
        }
    }
}
