package com.agentic.binning.pipeline

import com.agentic.binning.project.ProjectRef
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
    private var stops = 0
    private val launcher = AnalysisLauncher(
        state = { state },
        startService = { started += it; true },
        stopPipeline = { stops++ },
    )

    private class FakeFlags(var waiting: Boolean = true, var stoppedByUser: Boolean = false) : ProjectRunFlags {
        override suspend fun hasWaitingItems() = waiting
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

    /** Adding media also goes through userStart; with nothing new it still lifts the stop. */
    @Test
    fun userStart_withNothingWaiting_liftsTheStopWithoutStarting() = runTest {
        val flags = FakeFlags(waiting = false, stoppedByUser = true)

        assertFalse(launcher.userStart(project, flags))

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
        assertTrue(launcher.retryDeferred(project, FakeFlags()))
        assertFalse(launcher.retryDeferred(project, FakeFlags(stoppedByUser = true)))
        assertEquals(listOf(project), started)
    }
}
