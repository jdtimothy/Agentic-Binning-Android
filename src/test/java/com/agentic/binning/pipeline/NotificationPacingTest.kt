package com.agentic.binning.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPacingTest {
    @Test fun burst_postsAtMostOncePerGap() = runTest {
        val state = MutableStateFlow(0)
        val posted = mutableListOf<Int>()
        val job = launch { state.collectPaced(gapMs = 500) { posted += it } }
        runCurrent()
        repeat(100) { i ->
            state.value = i + 1
            delay(20) // a file moved every 20 ms: 50 updates a second
        }
        job.cancel()
        assertTrue("2 s of updates should post about 5 times, posted ${posted.size}", posted.size <= 6)
    }

    /** The bug: the Pause after a burst was shed, leaving a paused run showing a Pause button. */
    @Test fun lastValueOfABurst_isAlwaysPosted() = runTest {
        val state = MutableStateFlow("moving 0")
        val posted = mutableListOf<String>()
        val job = launch { state.collectPaced(gapMs = 500) { posted += it } }
        runCurrent()
        for (i in 1..10) {
            state.value = "moving $i"
            delay(20)
        }
        state.value = "paused"
        advanceUntilIdle()
        job.cancel()
        assertEquals("paused", posted.last())
    }

    @Test fun slowUpdates_areEachPostedAtOnce() = runTest {
        val state = MutableStateFlow(0)
        val posted = mutableListOf<Int>()
        val job = launch { state.collectPaced(gapMs = 500) { posted += it } }
        runCurrent()
        state.value = 1
        advanceTimeBy(600)
        runCurrent()
        state.value = 2
        advanceTimeBy(600)
        runCurrent()
        job.cancel()
        assertEquals(listOf(0, 1, 2), posted)
    }
}
