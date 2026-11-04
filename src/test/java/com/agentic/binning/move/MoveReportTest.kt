package com.agentic.binning.move

import com.agentic.binning.db.MoveRunEntity
import com.agentic.binning.db.MoveStepEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveReportTest {
    private val p = "/storage/emulated/0/Agentic Binning/Trip"

    private fun run(state: RunState, message: String? = null, kind: MoveKind = MoveKind.Bin, removed: String? = null) =
        MoveRunEntity(id = 1, kind = kind.name, startedAt = 1, finishedAt = 2, state = state.name, message = message, removedFolders = removed)

    private fun step(id: Long, state: StepState, dest: String = "$p/Beach/F$id.jpg", keep: Boolean = false, numbered: Boolean = false, error: String? = null) =
        MoveStepEntity(id = id, runId = 1, itemId = id, source = "/s/$id.jpg", dest = dest, state = state.name, keepSource = keep, numbered = numbered, error = error)

    @Test fun done_headlineCountsFilesAndFolders() {
        val steps = (1L..3L).map { step(it, StepState.Done, dest = if (it == 3L) "$p/Sunset/F3.jpg" else "$p/Beach/F$it.jpg") }
        val r = MoveReport.of(run(RunState.Done, removed = "$p/Turtle Beach"), steps, inPlace = 0, leftOut = emptyList(), checkOk = true)
        assertEquals("3 files binned into 2 category folders", r.headline)
        assertEquals(3, r.moved)
        assertEquals(listOf("Turtle Beach"), r.removedFolders)
        assertEquals(0, r.stillToMove)
        assertEquals(true, r.checkOk)
    }

    @Test fun oneFile_isSingular() {
        val r = MoveReport.of(run(RunState.Done), listOf(step(1, StepState.Done)), 0, emptyList(), true)
        assertEquals("1 file binned into 1 category folder", r.headline)
    }

    @Test fun stopped_saysHowManyAreLeft() {
        val steps = listOf(step(1, StepState.Done), step(2, StepState.Planned), step(3, StepState.Planned))
        val r = MoveReport.of(run(RunState.Stopped, message = "You stopped it."), steps, 0, emptyList(), checkOk = null)
        assertEquals("Bin Files stopped", r.headline)
        assertEquals("You stopped it.", r.message)
        assertEquals(1, r.moved)
        assertEquals(2, r.stillToMove)
    }

    @Test fun skipped_areListedWithWhy() {
        val steps = listOf(step(1, StepState.Done), step(2, StepState.Skipped, error = StepErrors.COPY_MISMATCH), step(3, StepState.Missing))
        val r = MoveReport.of(run(RunState.Done), steps, 0, emptyList(), true)
        assertEquals(listOf(SkippedFile(2, "F2.jpg", StepErrors.COPY_MISMATCH)), r.skipped)
        assertEquals(1, r.becameMissing)
        assertEquals("1 file binned into 1 category folder", r.headline)
    }

    @Test fun nothingToMove_headline() {
        val r = MoveReport.of(run(RunState.Done), emptyList(), inPlace = 12, leftOut = listOf(LeftOut(9, LeftOutReason.Failed)), checkOk = true)
        assertEquals("Nothing to move. 12 files were already in place.", r.headline)
        assertEquals(listOf(LeftOut(9, LeftOutReason.Failed)), r.leftOut)
    }

    @Test fun numbered_listsTheNames() {
        val steps = listOf(step(1, StepState.Done, dest = "$p/Beach/Sunset 2.jpg", numbered = true), step(2, StepState.Done))
        assertEquals(listOf("Sunset 2.jpg"), MoveReport.of(run(RunState.Done), steps, 0, emptyList(), true).numbered)
    }

    @Test fun sharedCopies_areCountedApart() {
        val steps = listOf(step(1, StepState.Done, keep = true), step(2, StepState.Done))
        val r = MoveReport.of(run(RunState.Done), steps, 0, emptyList(), true)
        assertEquals(1, r.copiedShared)
        assertEquals(1, r.moved)
        assertEquals("2 files binned into 1 category folder", r.headline)
    }

    @Test fun save_headline() {
        val r = MoveReport.of(run(RunState.Done, kind = MoveKind.Save), listOf(step(1, StepState.Done), step(2, StepState.Done)), 0, emptyList(), true)
        assertEquals("Saved. 2 files moved and checked.", r.headline)
    }

    @Test fun formatBytes_roundsToOneDecimal() {
        assertEquals("3.1 GB", formatBytes(3_300_000_000L))
        assertEquals("200 MB", formatBytes(200L * 1024 * 1024))
        assertTrue(formatBytes(512).endsWith("KB"))
    }
}
