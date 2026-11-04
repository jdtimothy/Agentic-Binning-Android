package com.agentic.binning.projectmedia

import com.agentic.binning.db.ItemStatus
import com.agentic.binning.move.MoveKind
import com.agentic.binning.move.MovePhase
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.pipeline.PipelinePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 2b Task 10: statuses into [MediaCounts], and the pipeline's phase into [ActiveJob]. */
class MediaCountsTest {
    private data class Row(val status: ItemStatus, val categoryId: Long? = null, val categorySetByUser: Boolean = false)

    private fun counts(rows: List<Row>) = mediaCountsOf(rows, { it.status }) { isWaiting(it.status, it.categoryId, it.categorySetByUser) }

    @Test fun everyStatusHasItsOwnBucket() {
        val rows = listOf(
            Row(ItemStatus.Pending),
            Row(ItemStatus.Pending),
            Row(ItemStatus.Analyzing),
            Row(ItemStatus.Analyzed),
            Row(ItemStatus.Failed),
            Row(ItemStatus.Missing),
            Row(ItemStatus.Sorted, categoryId = 1),
            Row(ItemStatus.Sorted, categoryId = 2),
            Row(ItemStatus.Binned, categoryId = 1),
        )

        val c = counts(rows)

        assertEquals(MediaCounts(total = 9, pending = 2, analyzing = 1, waiting = 1, failed = 1, missing = 1, readyToBin = 2, binned = 1), c)
        // Unlike 2a's ItemCounts, Missing is not counted as failed.
        assertEquals(c.total, c.pending + c.analyzing + c.waiting + c.failed + c.missing + c.readyToBin + c.binned)
    }

    @Test fun waiting_isOnlyAnalyzedWithoutACategoryTheModelMaySet() {
        assertTrue(isWaiting(ItemStatus.Analyzed, categoryId = null, categorySetByUser = false))
        assertFalse(isWaiting(ItemStatus.Analyzed, categoryId = 3, categorySetByUser = false))
        assertFalse(isWaiting(ItemStatus.Analyzed, categoryId = null, categorySetByUser = true))
        assertFalse(isWaiting(ItemStatus.Pending, categoryId = null, categorySetByUser = false))
        assertFalse(isWaiting(ItemStatus.Failed, categoryId = null, categorySetByUser = false))
    }

    @Test fun emptyProject() {
        assertEquals(MediaCounts(total = 0), counts(emptyList()))
    }

    @Test fun activeJob_followsThePhase() {
        assertEquals(ActiveJob.Sorting, activeJobOf(PipelinePhase.Categorizing))
        for (phase in listOf(PipelinePhase.LoadingModel, PipelinePhase.Preparing, PipelinePhase.Describing, PipelinePhase.Paused, PipelinePhase.Releasing)) {
            assertEquals(phase.name, ActiveJob.Analysis, activeJobOf(phase))
        }
        assertNull(activeJobOf(PipelinePhase.Idle))
        assertNull(activeJobOf(PipelinePhase.Stopped))
        assertNull(activeJobOf(null))
        // Plan 3: a move is Binning or Saving, with its progress.
        val bin = MoveProgress(MoveKind.Bin, done = 12, total = 248, current = "A.jpg", phase = MovePhase.Moving, paused = false)
        assertEquals(ActiveJob.Binning(12, 248), activeJobOf(PipelinePhase.Moving, bin))
        assertEquals(ActiveJob.Binning(12, 248, paused = true), activeJobOf(PipelinePhase.MovingPaused, bin.copy(paused = true)))
        assertEquals(ActiveJob.Saving(1, 3), activeJobOf(PipelinePhase.Moving, bin.copy(kind = MoveKind.Save, done = 1, total = 3)))
        // Before the first progress (the folder check), a move is Binning with nothing counted yet.
        assertEquals(ActiveJob.Binning(0, 0), activeJobOf(PipelinePhase.Moving, null))
        // Every phase is covered above.
        assertEquals(10, PipelinePhase.entries.size)
    }

    @Test fun pausedAnalysis_blocksBinFiles() {
        val s = mediaStatus(MediaCounts(total = 5, pending = 2, readyToBin = 3), activeJobOf(PipelinePhase.Paused))
        assertFalse(s.binFilesEnabled)
        assertEquals("Analyzing 2 left", s.text)
    }
}
