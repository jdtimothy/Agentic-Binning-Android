package com.agentic.binning.ui.media

import com.agentic.binning.move.BinPreview
import com.agentic.binning.move.LayoutProblems
import com.agentic.binning.move.MoveKind
import com.agentic.binning.move.MoveOutcome
import com.agentic.binning.move.MovePhase
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.move.MoveReport
import com.agentic.binning.move.RunState
import com.agentic.binning.pipeline.MoveStart
import com.agentic.binning.pipeline.PipelinePhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BinFilesViewModelTest {
    private val p = "/storage/emulated/0/Agentic Binning/Trip"

    private class FakeActions : BinActions {
        var preview: BinPreview? = BinPreview(3, 1, emptyList(), 0, 0, 0, 0, 0, emptyList())
        var startResult: MoveStart = MoveStart.Started
        var starts = 0
        val log = ArrayList<String>()
        var unread: MoveReport? = null
        val closed = ArrayList<Long>()
        val left = ArrayList<List<String>>()
        val added = ArrayList<List<String>>()

        /** What Add to project really added; null adds everything it is given. */
        var addable: Set<String>? = null

        override val projectName = "Trip"
        override val projectPath = "/storage/emulated/0/Agentic Binning/Trip"
        override suspend fun preview() = preview
        override fun start(): MoveStart { starts++; return startResult }
        override fun pause() { log += "pause" }
        override fun resume() { log += "resume" }
        override fun stop() { log += "stop" }
        override suspend fun unreadReport() = unread
        override suspend fun closeReport(runId: Long) { closed += runId; unread = null }
        override suspend fun facts(path: String) = UnknownFileFacts("Nov 2", "3.4 MB")
        override suspend fun leave(paths: List<String>) { left += paths }
        override suspend fun add(paths: List<String>): Set<String> {
            added += paths
            return addable ?: paths.toSet()
        }
    }

    private val actions = FakeActions()
    private val view = MutableStateFlow(BinPipelineView(PipelinePhase.Idle, null, 0, null, null))
    private val project = MutableStateFlow<BinActions?>(actions)

    private fun TestScope.vm() = BinFilesViewModel(
        view = view,
        actions = project,
        io = StandardTestDispatcher(testScheduler),
        scope = backgroundScope,
    )

    private fun report(state: RunState = RunState.Done, runId: Long = 5, still: Int = 0) = MoveReport(
        runId, MoveKind.Bin, state, "3 files binned into 1 category folder", null, 3, 0, 1, emptyList(), emptyList(), 0, 0, "A.jpg", emptyList(), still, emptyList(), true,
    )

    private fun moving(done: Int = 1, current: String? = "A.jpg", paused: Boolean = false, next: String? = null) =
        MoveProgress(MoveKind.Bin, done, 3, current, MovePhase.Moving, paused, from = "Camera", to = "Beach", next = next)

    // ---------------------------------------------------------------- confirm

    @Test fun binFiles_opensTheConfirmSheetAndStartsNothing() = runTest {
        val vm = vm()
        runCurrent()
        vm.onBinFiles()
        runCurrent()
        assertTrue(vm.state.value.sheet is BinSheetUi.Confirm)
        assertEquals(0, actions.starts)
    }

    @Test fun confirm_startsAndCloses() = runTest {
        val vm = vm()
        runCurrent()
        vm.onBinFiles()
        runCurrent()
        vm.onConfirm()
        runCurrent()
        assertEquals(1, actions.starts)
        assertNull(vm.state.value.sheet)
    }

    @Test fun cancel_closesWithoutStarting() = runTest {
        val vm = vm()
        runCurrent()
        vm.onBinFiles()
        runCurrent()
        vm.onCloseSheet()
        runCurrent()
        assertEquals(0, actions.starts)
        assertNull(vm.state.value.sheet)
    }

    /** B9: Bin Files on with nothing to move just marks and reports; there is nothing to confirm. */
    @Test fun nothingToMove_skipsTheConfirmSheet() = runTest {
        actions.preview = BinPreview(0, 0, emptyList(), 0, 0, 0, 0, 12, emptyList())
        val vm = vm()
        runCurrent()
        vm.onBinFiles()
        runCurrent()
        assertNull(vm.state.value.sheet)
        assertEquals(1, actions.starts)
    }

    @Test fun refusedStart_saysWhy() = runTest {
        actions.startResult = MoveStart.Refused("Bin Files is available when analysis is stopped or done")
        val vm = vm()
        runCurrent()
        val messages = ArrayList<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        runCurrent()
        vm.onBinFiles()
        runCurrent()
        vm.onConfirm()
        runCurrent()
        assertEquals(listOf("Bin Files is available when analysis is stopped or done"), messages)
    }

    // ---------------------------------------------------------------- progress

    @Test fun card_showsWhileFilesMove() = runTest {
        val vm = vm()
        runCurrent()
        assertNull(vm.state.value.card)
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        runCurrent()
        assertEquals("A.jpg", vm.state.value.card?.file)
    }

    /** Between files the engine reports no current file: the card keeps the last one, never blanks. */
    @Test fun card_keepsTheLastFileBetweenFiles() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(done = 2, current = null).copy(from = null, to = null), 0, null, null)
        runCurrent()
        assertEquals("A.jpg", vm.state.value.card?.file)
        assertEquals("Camera → Beach", vm.state.value.card?.fileSub)
    }

    @Test fun pause_saysItIsPausingUntilItIs() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        runCurrent()
        vm.onPause()
        runCurrent()
        assertEquals(listOf("pause"), actions.log)
        assertEquals("Pausing after this file…", vm.state.value.card?.note)

        view.value = BinPipelineView(PipelinePhase.MovingPaused, moving(current = null, paused = true, next = "B.jpg"), 0, null, null)
        runCurrent()
        assertEquals("Paused", vm.state.value.card?.title)

        vm.onResume()
        runCurrent()
        assertEquals(listOf("pause", "resume"), actions.log)
        view.value = BinPipelineView(PipelinePhase.Moving, moving(done = 1, current = "B.jpg"), 0, null, null)
        runCurrent()
        assertEquals("Other edits wait. You can leave this screen.", vm.state.value.card?.note)
    }

    @Test fun stop_saysItIsStopping() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        runCurrent()
        vm.onStop()
        runCurrent()
        assertEquals(listOf("stop"), actions.log)
        assertEquals("Stopping after this file…", vm.state.value.card?.note)
    }

    /** A second run must not inherit the first one's Stopping note. */
    @Test fun aNewRun_forgetsTheLastRunsRequest() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        runCurrent()
        vm.onStop()
        runCurrent()
        actions.unread = report(RunState.Stopped, still = 2)
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Stopped, null)
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 1, null, null)
        runCurrent()
        assertEquals("Other edits wait. You can leave this screen.", vm.state.value.card?.note)
    }

    @Test fun resumedRun_showsTheBanner() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(done = 2), 0, null, 2)
        runCurrent()
        assertEquals("Finishing Bin Files", vm.state.value.card?.title)
        assertNotNull(vm.state.value.card?.banner)
    }

    // ---------------------------------------------------------------- report

    @Test fun aFinishedRun_opensItsReport() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        runCurrent()
        actions.unread = report()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Done, null)
        runCurrent()
        assertNull(vm.state.value.card)
        assertEquals("Files binned", (vm.state.value.sheet as BinSheetUi.Report).ui.title)
    }

    /** B8: a report the user never closed comes back when the project opens, after a restart too. */
    @Test fun anUnclosedReport_opensWithTheProject() = runTest {
        actions.unread = report()
        val vm = vm()
        runCurrent()
        assertTrue(vm.state.value.sheet is BinSheetUi.Report)
    }

    /** While a resumed run finishes, its old report stays out of the way of the progress card. */
    @Test fun noReportOpensWhileFilesMove() = runTest {
        actions.unread = report()
        view.value = BinPipelineView(PipelinePhase.Moving, moving(), 0, null, null)
        val vm = vm()
        runCurrent()
        assertNull(vm.state.value.sheet)
    }

    @Test fun closingTheReport_marksItClosed() = runTest {
        actions.unread = report()
        val vm = vm()
        runCurrent()
        vm.onCloseSheet()
        runCurrent()
        assertEquals(listOf(5L), actions.closed)
        assertNull(vm.state.value.sheet)
    }

    /** State 4: "Bin 211 files" carries on with no second confirm, and closes the report it replaces. */
    /** A report stays until it's closed, so more files may be ready by now: never move more than it says. */
    @Test fun stoppedReport_moreReadyThanItSays_asksFirst() = runTest {
        actions.unread = report(RunState.Stopped, still = 2)
        actions.preview = BinPreview(5, 1, emptyList(), 0, 0, 0, 0, 0, emptyList())
        val vm = vm()
        runCurrent()
        vm.onReportPrimary()
        runCurrent()
        assertEquals(0, actions.starts)
        assertEquals("Bin 5 files", (vm.state.value.sheet as BinSheetUi.Confirm).ui.startLabel)
    }

    /** The finding: a stopped Save's button ran a full Bin Files with no confirm. */
    @Test fun stoppedSave_primaryOpensTheConfirmSheetAndMovesNothing() = runTest {
        actions.unread = saveReport(RunState.Stopped)
        actions.preview = BinPreview(248, 14, emptyList(), 0, 0, 0, 0, 0, emptyList())
        val vm = vm()
        runCurrent()
        vm.onReportPrimary()
        runCurrent()
        assertEquals(0, actions.starts)
        assertEquals(listOf(9L), actions.closed)
        assertEquals("Bin 248 files", (vm.state.value.sheet as BinSheetUi.Confirm).ui.startLabel)
    }

    @Test fun stoppedReport_primaryClosesItAndStartsTheRest() = runTest {
        actions.unread = report(RunState.Stopped, still = 2)
        actions.preview = BinPreview(2, 1, emptyList(), 0, 0, 0, 0, 0, emptyList())
        val vm = vm()
        runCurrent()
        vm.onReportPrimary()
        runCurrent()
        assertEquals(listOf(5L), actions.closed)
        assertEquals(1, actions.starts)
        assertNull(vm.state.value.sheet)
    }

    @Test fun doneReport_primaryOnlyCloses() = runTest {
        actions.unread = report()
        val vm = vm()
        runCurrent()
        vm.onReportPrimary()
        runCurrent()
        assertEquals(0, actions.starts)
        assertEquals(listOf(5L), actions.closed)
    }

    @Test fun switchingProject_closesTheSheetWithoutMarkingIt() = runTest {
        actions.unread = report()
        val vm = vm()
        runCurrent()
        project.value = null
        runCurrent()
        assertNull(vm.state.value.sheet)
        assertTrue(actions.closed.isEmpty())
    }

    // ---------------------------------------------------------------- unknown files (B5)

    private fun saveReport(state: RunState = RunState.Done) = MoveReport(
        9, MoveKind.Save, state, "Saved. 2 files moved and checked.", null, 2, 0, 1, emptyList(), emptyList(), 0, 0, "A.jpg", emptyList(), 0, emptyList(), true,
    )

    /** 2b state 6: a Save that went well says so in a line; there is nothing to read in a report. */
    @Test fun aFinishedSave_saysSoAndClosesItsReport() = runTest {
        val vm = vm()
        runCurrent()
        val messages = ArrayList<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        runCurrent()
        actions.unread = saveReport()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Done, null)
        runCurrent()
        assertNull(vm.state.value.sheet)
        assertEquals(listOf("Saved. 2 files moved and checked."), messages)
        assertEquals(listOf(9L), actions.closed)
    }

    /** One that stopped, or left a file behind, needs the report. */
    @Test fun aStoppedSave_opensItsReport() = runTest {
        val vm = vm()
        runCurrent()
        actions.unread = saveReport(RunState.Stopped)
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Stopped, null)
        runCurrent()
        assertEquals("Saving stopped", (vm.state.value.sheet as BinSheetUi.Report).ui.title)
        assertTrue(actions.closed.isEmpty())
    }

    private val problems get() = LayoutProblems(missing = emptyList(), unexpected = listOf("$p/Beach/IMG.jpg"))

    @Test fun blockedRun_opensTheUnknownFilesSheet() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Blocked(problems), null)
        runCurrent()
        val sheet = vm.state.value.sheet as BinSheetUi.Unknown
        assertEquals("Beach/IMG.jpg", sheet.ui.unknown.single().path)
    }

    @Test fun leaveThem_recordsAndStarts() = runTest {
        val vm = vm()
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Blocked(problems), null)
        runCurrent()
        vm.onLeaveFiles()
        runCurrent()
        assertEquals(listOf(listOf("$p/Beach/IMG.jpg")), actions.left)
        assertEquals(1, actions.starts)
        assertNull(vm.state.value.sheet)
    }

    @Test fun addToProject_registersAndDoesNotStart() = runTest {
        val vm = vm()
        runCurrent()
        val messages = ArrayList<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Blocked(problems), null)
        runCurrent()
        vm.onAddFiles()
        runCurrent()
        assertEquals(listOf(listOf("$p/Beach/IMG.jpg")), actions.added)
        assertEquals(0, actions.starts)
        assertNull(vm.state.value.sheet)
        assertEquals(listOf("1 added · it's analyzed and sorted, then binned"), messages)
    }

    /** The finding: "1 added" while the next Bin Files blocked on the same file, for ever. */
    @Test fun addToProject_whatCantBeAdded_staysOnTheSheetWithOnlyLeaveThem() = runTest {
        val two = LayoutProblems(emptyList(), listOf("$p/Beach/IMG.jpg", "$p/Beach/notes.txt"))
        actions.addable = setOf("$p/Beach/IMG.jpg")
        val vm = vm()
        runCurrent()
        val messages = ArrayList<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Blocked(two), null)
        runCurrent()

        vm.onAddFiles()
        runCurrent()

        assertEquals(listOf("1 added · 1 can't be added"), messages)
        val sheet = vm.state.value.sheet as BinSheetUi.Unknown
        assertEquals(listOf("$p/Beach/notes.txt"), sheet.ui.paths)
        assertEquals(listOf("Beach/notes.txt"), sheet.ui.unknown.map { it.path })
        assertEquals(false, sheet.ui.canAdd)

        vm.onLeaveFiles()
        runCurrent()
        assertEquals(listOf(listOf("$p/Beach/notes.txt")), actions.left)
    }

    @Test fun addToProject_nothingAdded_saysSoAndNeverClaimsIt() = runTest {
        actions.addable = emptySet()
        val vm = vm()
        runCurrent()
        val messages = ArrayList<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        runCurrent()
        view.value = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Blocked(problems), null)
        runCurrent()
        vm.onAddFiles()
        runCurrent()
        assertEquals(listOf("1 can't be added"), messages)
        assertTrue(vm.state.value.sheet is BinSheetUi.Unknown)
    }

    /** Closing the sheet doesn't bring it back: the outcome stays in the state until the next run. */
    @Test fun closedUnknownSheet_staysClosed() = runTest {
        val vm = vm()
        runCurrent()
        val blocked = BinPipelineView(PipelinePhase.Idle, null, 1, MoveOutcome.Blocked(problems), null)
        view.value = blocked
        runCurrent()
        vm.onCloseSheet()
        runCurrent()
        view.value = blocked.copy(phase = PipelinePhase.Stopped)
        runCurrent()
        assertNull(vm.state.value.sheet)
    }
}
