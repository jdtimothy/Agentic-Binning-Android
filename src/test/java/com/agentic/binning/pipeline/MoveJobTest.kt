package com.agentic.binning.pipeline

import com.agentic.binning.move.FakeMoveFiles
import com.agentic.binning.move.FakeMoveStore
import com.agentic.binning.move.MoveEngine
import com.agentic.binning.move.MoveKind
import com.agentic.binning.move.MoveNotifier
import com.agentic.binning.move.MoveOutcome
import com.agentic.binning.project.ProjectRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 3 Task 9: Bin Files as a Binning job of [PipelineController]. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveJobTest {
    private val project = ProjectRef("Trip", "/storage/emulated/0/Agentic Binning/Trip")
    private val p = project.path
    private val events = Events()
    private val model = FakeModel(events)
    private val arbiter = ModelArbiter()
    private val fs = FakeMoveFiles()
    private val moves = FakeMoveStore()
    private val jobs = mutableListOf<MoveJob>()
    private var activeDuringMove: JobKind? = null

    /** Runs the real engine over [moves] and [fs]. */
    private val engineMover = FileMover { _, job, control, onProgress ->
        jobs += job
        activeDuringMove = arbiter.active.value
        MoveEngine(moves, fs, MoveNotifier { _, _ -> }, p).run(1, MoveKind.Bin, control, onProgress)
    }

    private fun TestScope.controller(store: FakeStore = FakeStore(emptyList(), events), mover: FileMover = engineMover, isCurrent: (ProjectRef) -> Boolean = { true }) =
        PipelineController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            engineFactory = { cap -> AnalysisEngine(model, FakePreparer(missing = emptySet(), undecodable = emptySet()), store, maxFrames = cap) },
            model = model,
            store = store,
            categorizer = FakeCategorizer(events),
            arbiter = arbiter,
            isCurrent = isCurrent,
            mover = mover,
        )

    private fun twoFiles(): Pair<Long, Long> {
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        fs.put("/storage/emulated/0/DCIM/b.jpg", "B")
        return moves.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 1) to
            moves.addStep(2, "/storage/emulated/0/DCIM/b.jpg", "$p/Beach/B.jpg", 1)
    }

    /** Plan 3 Task 14: a Save's plan is written with its edit; this only runs it. */
    @Test fun saveMoves_runsTheWrittenPlanUnderTheBinningJob() = runTest {
        twoFiles()
        val controller = controller()

        assertEquals(MoveStart.Started, controller.saveMoves(project, runId = 7))
        advanceUntilIdle()

        assertEquals(listOf<MoveJob>(MoveJob.Save(7)), jobs)
        assertEquals(JobKind.Binning, activeDuringMove)
        assertEquals(1, controller.state.value.moveEnded)
    }

    @Test fun saveMoves_isNotAResume() = runTest {
        twoFiles()
        val controller = controller()
        controller.saveMoves(project, runId = 7)
        controller.pause()
        advanceUntilIdle()
        assertEquals(PipelinePhase.MovingPaused, controller.state.value.phase)
        assertNull(controller.state.value.moveResumedFrom)
        controller.stop()
        advanceUntilIdle()
    }

    /** The UI opens a report when this changes: one count per finished run, even an instant one. */
    @Test fun moveEnded_countsFinishedRuns() = runTest {
        twoFiles()
        val controller = controller()
        assertEquals(0, controller.state.value.moveEnded)
        controller.binFiles(project)
        advanceUntilIdle()
        assertEquals(1, controller.state.value.moveEnded)
        controller.binFiles(project)
        advanceUntilIdle()
        assertEquals(2, controller.state.value.moveEnded)
    }

    /** State 6: a resumed run says how many files had moved before the app closed. */
    @Test fun resumeMove_saysWhereItPickedUp() = runTest {
        fs.put("/storage/emulated/0/DCIM/b.jpg", "B")
        moves.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 1, com.agentic.binning.move.StepState.Done)
        moves.addStep(2, "/storage/emulated/0/DCIM/b.jpg", "$p/Beach/B.jpg", 1)
        val controller = controller()

        // Paused, so the run holds still: B3 brings a paused run back paused.
        controller.resumeMove(project, runId = 1, paused = true)
        advanceUntilIdle()
        assertEquals(1, controller.state.value.moveResumedFrom)

        controller.resume()
        advanceUntilIdle()
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.moveResumedFrom)
    }

    @Test fun binFiles_isNotAResume() = runTest {
        twoFiles()
        val controller = controller()
        controller.binFiles(project)
        controller.pause()
        advanceUntilIdle()
        assertEquals(PipelinePhase.MovingPaused, controller.state.value.phase)
        assertNull(controller.state.value.moveResumedFrom)
        controller.stop()
        advanceUntilIdle()
    }

    @Test fun binFiles_runsUnderTheBinningJob() = runTest {
        twoFiles()
        val controller = controller()

        assertEquals(MoveStart.Started, controller.binFiles(project))
        assertEquals(PipelinePhase.Moving, controller.state.value.phase)
        advanceUntilIdle()

        assertEquals(JobKind.Binning, activeDuringMove)
        assertEquals(listOf<MoveJob>(MoveJob.BinFiles), jobs)
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals(MoveOutcome.Done, controller.state.value.moveOutcome)
        assertNull(controller.state.value.moveProgress)
        assertEquals("A", fs.content("$p/Beach/A.jpg"))
        assertNull(arbiter.active.value)
    }

    @Test fun binFiles_isRefusedWhileARunIsInProgress() = runTest {
        val store = FakeStore(listOf(item(5)), events)
        val controller = controller(store)
        val gate = model.gate(5)
        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Describing, controller.state.value.phase)

        assertEquals(MoveStart.Refused(MOVE_BUSY), controller.binFiles(project))
        assertEquals(MoveStart.Refused(MOVE_BUSY), controller.resumeMove(project, runId = 1, paused = false))

        gate.complete(false)
        advanceUntilIdle()
        assertTrue(jobs.isEmpty())
    }

    @Test fun binFiles_isRefusedForAProjectThatIsNotOpen() = runTest {
        val controller = controller(isCurrent = { false })
        assertEquals(MoveStart.Refused(MOVE_NOT_OPEN), controller.binFiles(project))
        advanceUntilIdle()
        assertTrue(jobs.isEmpty())
    }

    /** Q11: while files move, analysis and categorizing are not started, and never queued behind the move. */
    @Test fun start_isRefusedWhileFilesMove() = runTest {
        val store = FakeStore(listOf(item(5)), events)
        val release = CompletableDeferred<Unit>()
        val controller = controller(store, mover = FileMover { _, _, _, _ -> release.await(); MoveOutcome.Done })

        controller.binFiles(project)
        runCurrent()
        controller.start(project)
        controller.categorizeNow(project)
        controller.resume()
        runCurrent()
        assertEquals(PipelinePhase.Moving, controller.state.value.phase)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals(0, events.count("load"))
        assertEquals(com.agentic.binning.db.ItemStatus.Pending, store.status(5))
    }

    /** B2: Pause finishes the file in progress, then holds; Resume carries on. */
    @Test fun pause_and_resume_actBetweenFiles() = runTest {
        val (a, b) = twoFiles()
        val controller = controller()
        fs.before = { op, path -> if (op == "rename" && path.endsWith("a.jpg")) controller.pause() }

        controller.binFiles(project)
        advanceUntilIdle()

        assertEquals(PipelinePhase.MovingPaused, controller.state.value.phase)
        assertTrue(controller.state.value.moveProgress!!.paused)
        assertEquals("Done", moves.step(a).state)
        assertEquals("Planned", moves.step(b).state)
        assertEquals(false, controller.state.value.phase.canPause)

        controller.resume()
        advanceUntilIdle()
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals("Done", moves.step(b).state)
    }

    /** B2: Stop finishes the file in progress and ends; it never cancels a file half moved. */
    @Test fun stop_endsAfterTheCurrentFile() = runTest {
        val (a, b) = twoFiles()
        val controller = controller()
        fs.before = { op, path -> if (op == "rename" && path.endsWith("a.jpg")) controller.stop() }

        controller.binFiles(project)
        advanceUntilIdle()

        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals(MoveOutcome.Stopped, controller.state.value.moveOutcome)
        assertEquals("Done", moves.step(a).state)
        assertEquals("Planned", moves.step(b).state)
    }

    /** A project switch waits for the file in progress, then the move stops. */
    @Test fun stopAndReset_waitsForTheCurrentFile() = runTest {
        val (a, b) = twoFiles()
        val controller = controller()
        // A switch begins while the first file is being renamed (undispatched: it asks at once, then waits).
        fs.before = { op, path ->
            if (op == "rename" && path.endsWith("a.jpg")) launch(start = CoroutineStart.UNDISPATCHED) { controller.stopAndReset() }
        }
        controller.binFiles(project)
        advanceUntilIdle()

        assertEquals("Done", moves.step(a).state)
        assertEquals("Planned", moves.step(b).state)
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.project)
    }

    /** B3: a paused run resumes paused. */
    @Test fun resumeMove_paused_staysPausedUntilResume() = runTest {
        val (a, _) = twoFiles()
        val controller = controller()

        assertEquals(MoveStart.Started, controller.resumeMove(project, runId = 7, paused = true))
        advanceUntilIdle()
        assertEquals(PipelinePhase.MovingPaused, controller.state.value.phase)
        assertEquals(listOf<MoveJob>(MoveJob.Resume(7)), jobs)
        assertEquals("Planned", moves.step(a).state)

        controller.resume()
        advanceUntilIdle()
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test fun moverFailure_endsIdleWithTheMessage() = runTest {
        val controller = controller(mover = FileMover { _, _, _, _ -> throw IllegalStateException("db gone") })
        controller.binFiles(project)
        advanceUntilIdle()
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals(MoveOutcome.Failed("db gone"), controller.state.value.moveOutcome)
        assertNull(arbiter.active.value)
    }
}
