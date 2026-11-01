package com.agentic.binning.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelArbiterTest {
    @Test
    fun arbiter_runsOneJobAtATime_firstComeFirstServed() = runTest {
        val arbiter = ModelArbiter()
        val log = mutableListOf<String>()
        val firstDone = CompletableDeferred<Unit>()

        launch { arbiter.withJob(JobKind.Analysis) { log += "analysis:start"; firstDone.await(); log += "analysis:end" } }
        runCurrent()
        launch { arbiter.withJob(JobKind.Sorting) { log += "sorting" } }
        runCurrent()
        launch { arbiter.withJob(JobKind.Binning) { log += "binning" } }
        runCurrent()

        assertEquals(listOf("analysis:start"), log)
        assertEquals(JobKind.Analysis, arbiter.active.value)

        firstDone.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("analysis:start", "analysis:end", "sorting", "binning"), log)
        assertNull(arbiter.active.value)
    }

    @Test
    fun aFailingJob_letsTheNextOneRun() = runTest {
        val arbiter = ModelArbiter()

        runCatching { arbiter.withJob(JobKind.Sorting) { error("boom") } }

        assertNull(arbiter.active.value)
        assertEquals(7, arbiter.withJob(JobKind.Analysis) { 7 })
    }
}
