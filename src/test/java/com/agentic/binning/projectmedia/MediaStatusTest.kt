package com.agentic.binning.projectmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStatusTest {
    @Test fun emptyProject() {
        val s = mediaStatus(MediaCounts(total = 0), null)
        assertEquals("Nothing to bin yet", s.text)
        assertFalse(s.binFilesEnabled)
    }

    @Test fun ready_enablesBinFiles() {
        val s = mediaStatus(MediaCounts(total = 40, readyToBin = 34, binned = 6), null)
        assertEquals("34 files ready to bin", s.text)
        assertTrue(s.binFilesEnabled)
        assertEquals(34, s.readyToBin)
        assertEquals(MediaTone.Ready, s.tone)
    }

    @Test fun waitingToBeCategorized_blocksBinFiles_andOffersCategorizeNow() {
        val s = mediaStatus(MediaCounts(total = 44, waiting = 3, failed = 1, readyToBin = 34, binned = 6), null)
        assertEquals("34 files ready to bin · 3 waiting to be categorized", s.text)
        assertFalse(s.binFilesEnabled)
        assertEquals(StatusAction.CategorizeNow, s.action)
        assertEquals(MediaTone.Attention, s.tone)
    }

    @Test fun failedAndMissing_doNotBlockBinFiles() {
        val s = mediaStatus(MediaCounts(total = 40, failed = 1, missing = 2, readyToBin = 37), null)
        assertEquals("37 files ready to bin · 1 failed, 2 missing", s.text)
        assertTrue(s.binFilesEnabled)
        assertNull(s.action)
    }

    @Test fun pendingItems_blockBinFiles_evenWhenNotRunning() {
        val s = mediaStatus(MediaCounts(total = 50, pending = 12, readyToBin = 38), null)
        assertEquals("38 files ready to bin · 12 waiting to analyze", s.text)
        assertFalse(s.binFilesEnabled)
    }

    @Test fun allBinned() {
        val s = mediaStatus(MediaCounts(total = 41, failed = 1, binned = 40), null)
        assertEquals("All files binned · 1 failed", s.text)
        assertFalse(s.binFilesEnabled)
        assertEquals(MediaTone.Quiet, s.tone)
    }

    @Test fun anyJob_disablesBinFiles() {
        val c = MediaCounts(total = 40, readyToBin = 34, binned = 6)
        assertEquals("Analyzing 12 left", mediaStatus(c.copy(pending = 11, analyzing = 1), ActiveJob.Analysis).text)
        assertEquals("Categorizing…", mediaStatus(c, ActiveJob.Sorting).text)
        assertEquals("Saving · moving 2 of 3 files", mediaStatus(c, ActiveJob.Saving(1, 3)).text)
        assertEquals("Saving · moving 1 of 1 file", mediaStatus(c, ActiveJob.Saving(0, 1)).text)
        assertEquals("Saving…", mediaStatus(c, ActiveJob.Saving(0, 0)).text)
        assertEquals("Binning · 40 of 40 files", mediaStatus(c, ActiveJob.Binning(40, 40)).text)
        for (job in listOf(ActiveJob.Analysis, ActiveJob.Sorting, ActiveJob.Saving(0, 3), ActiveJob.Binning(0, 3))) {
            val s = mediaStatus(c, job)
            assertFalse(s.binFilesEnabled)
            assertEquals(MediaTone.Running, s.tone)
        }
    }

    @Test fun analyzingItem_withoutAJob_stillReadsAsAnalyzing() {
        val s = mediaStatus(MediaCounts(total = 10, pending = 4, analyzing = 1, readyToBin = 5), null)
        assertEquals("Analyzing 5 left", s.text)
        assertFalse(s.binFilesEnabled)
    }

    @Test fun singular() {
        assertEquals("1 file ready to bin", mediaStatus(MediaCounts(total = 1, readyToBin = 1), null).text)
    }

    @Test fun binFilesReason_namesWhatBlocksIt() {
        assertEquals("Bin Files is available when analysis is stopped or done", binFilesBlockedReason(MediaCounts(total = 4, readyToBin = 4), ActiveJob.Sorting))
        assertEquals("Bin Files waits for analysis to finish", binFilesBlockedReason(MediaCounts(total = 4, pending = 1, readyToBin = 3), null))
        assertEquals("Bin Files waits for 3 items to be categorized", binFilesBlockedReason(MediaCounts(total = 4, waiting = 3, readyToBin = 1), null))
        assertEquals("Nothing to bin yet", binFilesBlockedReason(MediaCounts(total = 1, failed = 1), null))
        assertEquals("All files are binned", binFilesBlockedReason(MediaCounts(total = 2, binned = 2), null))
        // On exactly when mediaStatus turns Bin Files on.
        val ready = MediaCounts(total = 5, failed = 1, readyToBin = 4)
        assertNull(binFilesBlockedReason(ready, null))
        assertTrue(mediaStatus(ready, null).binFilesEnabled)
    }
}
