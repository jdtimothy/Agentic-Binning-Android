package com.agentic.binning.ui.project

import com.agentic.binning.media.HeldFile
import com.agentic.binning.media.SharedCopyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mockup state 7: the notice for media another project holds (P8). */
class SharedNoticeTest {
    private val maui = HeldFile("/a", "Maui", listed = true)
    private val maui2 = HeldFile("/b", "Maui", listed = true)
    private val sort = HeldFile("/c", "Sort Test", listed = false)

    @Test fun ask_namesEachProjectAndHowManyFiles() {
        val ui = sharedAskUi(listOf(maui, maui2, sort), added = 9, projectName = "Hawaii Photos")
        assertEquals("3 files are already in another project", ui.title)
        assertEquals(
            listOf(
                "In Maui: 2 files. In Sort Test (not on your list): 1 file. The other 9 files you picked were added.",
                "Copy media files puts a copy of each in Hawaii Photos/Copied from Maui/ and Copied from Sort Test/. The other projects' files stay exactly where they are.",
            ),
            ui.lines,
        )
    }

    @Test fun ask_oneFileOneProject_isSingular() {
        val ui = sharedAskUi(listOf(maui), added = 1, projectName = "Trip")
        assertEquals("1 file is already in another project", ui.title)
        assertEquals("In Maui: 1 file. The other file you picked was added.", ui.lines[0])
        assertEquals("Copy media files puts a copy of it in Trip/Copied from Maui/. The other project's file stays exactly where it is.", ui.lines[1])
    }

    @Test fun ask_nothingElseAdded_doesNotMentionIt() {
        assertEquals("In Maui: 1 file.", sharedAskUi(listOf(maui), added = 0, projectName = "Trip").lines[0])
    }

    /** Q11: nothing is added while files move, this included. */
    @Test fun whileFilesMove_saysNothingWasCopied() {
        val ui = sharedWaitsForMoveUi(listOf(maui, maui2), added = 1)
        assertEquals("1 added, 2 left out", ui.title)
        assertEquals(listOf("Files are moving, so nothing was copied. Pick them again when the files have moved."), ui.lines)
    }

    @Test fun cancelled_saysWhatWasLeftOut() {
        val ui = sharedCancelledUi(listOf(maui, maui2, sort), added = 9)
        assertEquals("9 added, 3 left out", ui.title)
        assertEquals(listOf("The 3 files in Maui and Sort Test weren't added. Pick them again to copy them."), ui.lines)
    }

    @Test fun cancelled_nothingAdded_saysSo() {
        val ui = sharedCancelledUi(listOf(maui), added = 0)
        assertEquals("Nothing added", ui.title)
        assertEquals(listOf("The file in Maui wasn't added. Pick it again to copy it."), ui.lines)
    }

    @Test fun copied_saysWhereEverythingWent() {
        val ui = sharedDoneUi(SharedCopyResult(copied = 3, folders = listOf("Copied from Maui", "Copied from Sort Test")), added = 9)
        assertEquals("12 added", ui.title)
        assertEquals(listOf("9 added from where they are", "3 copied into Copied from Maui and Copied from Sort Test"), ui.bullets)
        assertEquals(listOf("They're analyzed and sorted like any other item, and Bin Files moves them into their category folders."), ui.lines)
    }

    @Test fun copied_noOthers_skipsThatLine() {
        val ui = sharedDoneUi(SharedCopyResult(copied = 1, folders = listOf("Copied from Maui")), added = 0)
        assertEquals("1 added", ui.title)
        assertEquals(listOf("1 copied into Copied from Maui"), ui.bullets)
    }

    @Test fun copied_saysWhatDidNotCopy() {
        val ui = sharedDoneUi(SharedCopyResult(copied = 1, failed = 2, folders = listOf("Copied from Maui")), added = 0)
        assertTrue(ui.bullets.contains("2 couldn't be copied cleanly and stayed out. Nothing was lost."))
    }

    @Test fun copied_alreadyHere_saysSo() {
        val ui = sharedDoneUi(SharedCopyResult(alreadyHere = 1), added = 0)
        assertEquals("Nothing added", ui.title)
        assertEquals(listOf("1 was already in this project"), ui.bullets)
    }

    /** B6: storage ran out part way. */
    @Test fun copied_outOfSpace_saysWhatToFreeUp() {
        val ui = sharedDoneUi(SharedCopyResult(copied = 1, notTried = 2, neededBytes = 3_300_000_000L, folders = listOf("Copied from Maui")), added = 0)
        assertTrue(ui.bullets.contains("2 weren't copied: free up 3.1 GB and pick them again."))
    }
}
