package com.agentic.binning.pipeline

import com.agentic.binning.project.ProjectRef
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3: a Stop outlives the process and project switches; Resume and adding media lift it. */
class AnalysisLauncherTest {
    private val project = ProjectRef("Snapdragon Summit", "/sdcard/Agentic Binning/Snapdragon Summit")
    private var state = idle(null)
    private val started = mutableListOf<ProjectRef>()
    private val kinds = mutableListOf<RunKind>()
    private var stops = 0
    private val launcher = AnalysisLauncher(
        state = { state },
        startService = { p, kind -> started += p; kinds += kind; true },
        stopPipeline = { stops++ },
    )

    private class FakeFlags(
        var waiting: Boolean = true,
        var stoppedByUser: Boolean = false,
        var uncategorized: Boolean = false,
    ) : ProjectRunFlags {
        override suspend fun hasWaitingItems() = waiting
        override suspend fun hasUncategorizedItems() = uncategorized
        override suspend fun isStoppedByUser() = stoppedByUser
        override suspend fun setStoppedByUser(stopped: Boolean) {
            stoppedByUser = stopped
        }
    }

    private fun idle(project: ProjectRef?, phase: PipelinePhase = PipelinePhase.Idle) =
        PipelineState(phase, project, null, null, null, pending = 0, analyzed = 0, total = 0)

    @Test
    fun autoStart_startsWaitingItems() = runTest {
        assertTrue(launcher.autoStart(project, FakeFlags()))
        assertEquals(listOf(project), started)
    }

    @Test
    fun autoStart_withNothingWaiting_doesNothing() = runTest {
        assertFalse(launcher.autoStart(project, FakeFlags(waiting = false)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun userStop_isRemembered_soALaterLaunchDoesNotRestart() = runTest {
        val flags = FakeFlags()
        state = idle(project, PipelinePhase.Describing)

        launcher.userStop(flags)

        assertEquals(1, stops)
        assertTrue(flags.stoppedByUser)
        // A cold launch or a switch back: the pipeline no longer knows about the stop.
        state = idle(null)
        assertFalse(launcher.autoStart(project, flags))
        assertTrue(started.isEmpty())
    }

    @Test
    fun userStart_liftsTheStop_andStarts() = runTest {
        val flags = FakeFlags(stoppedByUser = true)

        assertTrue(launcher.userStart(project, flags))

        assertFalse(flags.stoppedByUser)
        assertEquals(listOf(project), started)
        // Later launches start on their own again.
        state = idle(null)
        assertTrue(launcher.autoStart(project, flags))
    }

    @Test
    fun userStart_withNothingWaiting_liftsTheStopWithoutStarting() = runTest {
        val flags = FakeFlags(waiting = false, stoppedByUser = true)

        assertFalse(launcher.userStart(project, flags))

        assertFalse(flags.stoppedByUser)
        assertTrue(started.isEmpty())
    }

    /** Adding media analyzes what it registered; with nothing new it still lifts the stop. */
    @Test
    fun userAdd_startsWaitingItems_andLiftsTheStop() = runTest {
        val flags = FakeFlags(waiting = true, stoppedByUser = true)

        assertTrue(launcher.userAdd(project, flags))

        assertFalse(flags.stoppedByUser)
        assertEquals(listOf(RunKind.Analyze), kinds)
    }

    /** An add that registered nothing new (all duplicates) never starts a categorization nobody asked for. */
    @Test
    fun userAdd_withOnlyUncategorizedItems_doesNotCategorize() = runTest {
        val flags = FakeFlags(waiting = false, stoppedByUser = true, uncategorized = true)

        assertFalse(launcher.userAdd(project, flags))

        assertFalse(flags.stoppedByUser)
        assertTrue(started.isEmpty())
    }

    @Test
    fun autoStart_leavesARunningOrStoppedRunAlone() = runTest {
        state = idle(project, PipelinePhase.Describing)
        assertFalse(launcher.autoStart(project, FakeFlags()))
        state = idle(project, PipelinePhase.Stopped)
        assertFalse(launcher.autoStart(project, FakeFlags()))
        assertTrue(started.isEmpty())
    }

    /** A start the system refused in the background is retried, unless the user has stopped since. */
    @Test
    fun retryDeferred_honoursAStop() = runTest {
        state = idle(project, PipelinePhase.Stopped)
        assertTrue(launcher.retryDeferred(project, FakeFlags(), RunKind.Analyze))
        assertFalse(launcher.retryDeferred(project, FakeFlags(stoppedByUser = true), RunKind.Analyze))
        assertEquals(listOf(project), started)
    }

    /** Writes take longer for a Stop than a Resume, as when the Stop has to wait for the pipeline: the order must still hold. */
    @Test
    fun stopThenResume_flagWrittenInOrder() = runTest {
        val writes = mutableListOf<Boolean>()
        val flags = object : ProjectRunFlags {
            override suspend fun hasWaitingItems() = true
            override suspend fun hasUncategorizedItems() = false
            override suspend fun isStoppedByUser() = false
            override suspend fun setStoppedByUser(stopped: Boolean) {
                delay(if (stopped) 100 else 1)
                writes += stopped
            }
        }
        // Launched undispatched, as AppGraph does, so each call queues for the lock in call order.
        launch(start = CoroutineStart.UNDISPATCHED) { launcher.userStop(flags) }
        launch(start = CoroutineStart.UNDISPATCHED) { launcher.userStart(project, flags) }
        advanceUntilIdle()

        assertEquals(listOf(true, false), writes)
    }

    @Test
    fun userStart_withOnlyUncategorizedItems_categorizes() = runTest {
        val flags = FakeFlags(waiting = false, stoppedByUser = true, uncategorized = true)

        assertTrue(launcher.userStart(project, flags))

        assertFalse(flags.stoppedByUser)
        assertEquals(listOf(project), started)
        assertEquals(listOf(RunKind.CategorizeOnly), kinds)
    }

    /** Waiting items go first: the analysis run categorizes at its end anyway. */
    @Test
    fun userStart_withWaitingAndUncategorizedItems_analyzes() = runTest {
        assertTrue(launcher.userStart(project, FakeFlags(waiting = true, uncategorized = true)))
        assertEquals(listOf(RunKind.Analyze), kinds)
    }

    /** A Claude run costs money: opening a project never starts categorizing on its own. */
    @Test
    fun autoStart_withOnlyUncategorizedItems_doesNotStart() = runTest {
        assertFalse(launcher.autoStart(project, FakeFlags(waiting = false, uncategorized = true)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun retryDeferred_categorizesOnlyWhenTheDeferredStartDid() = runTest {
        val flags = FakeFlags(waiting = false, uncategorized = true)
        assertFalse(launcher.retryDeferred(project, flags, RunKind.Analyze))
        assertTrue(launcher.retryDeferred(project, flags, RunKind.CategorizeOnly))
        assertFalse(launcher.retryDeferred(project, FakeFlags(waiting = false, uncategorized = true, stoppedByUser = true), RunKind.CategorizeOnly))
        assertEquals(listOf(RunKind.CategorizeOnly), kinds)
    }

    /** Stop during Categorizing, then Resume: the flag order holds and Resume categorizes again. */
    @Test
    fun stopThenResume_whileCategorizing_flagWrittenInOrder_andCategorizes() = runTest {
        val writes = mutableListOf<Boolean>()
        val flags = object : ProjectRunFlags {
            override suspend fun hasWaitingItems() = false
            override suspend fun hasUncategorizedItems() = true
            override suspend fun isStoppedByUser() = writes.lastOrNull() ?: false
            override suspend fun setStoppedByUser(stopped: Boolean) {
                delay(if (stopped) 100 else 1)
                writes += stopped
            }
        }
        state = idle(project, PipelinePhase.Categorizing)
        launch(start = CoroutineStart.UNDISPATCHED) { launcher.userStop(flags) }
        launch(start = CoroutineStart.UNDISPATCHED) { launcher.userStart(project, flags) }
        advanceUntilIdle()

        assertEquals(1, stops)
        assertEquals(listOf(true, false), writes)
        assertEquals(listOf(RunKind.CategorizeOnly), kinds)
    }
}
