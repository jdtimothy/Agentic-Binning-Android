package com.agentic.binning.ui.media

import com.agentic.binning.move.BinPreview
import com.agentic.binning.move.BinSource
import com.agentic.binning.move.LayoutProblems
import com.agentic.binning.move.LeftOut
import com.agentic.binning.move.LeftOutReason
import com.agentic.binning.move.MoveKind
import com.agentic.binning.move.MovePhase
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.move.MoveReport
import com.agentic.binning.move.MovedFile
import com.agentic.binning.move.RunState
import com.agentic.binning.move.SkippedFile
import com.agentic.binning.move.StepErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinModelsTest {
    private val failed = LeftOut(7, LeftOutReason.Failed, "VID_1.mp4", "/storage/emulated/0/DCIM/Camera/VID_1.mp4", "decode")
    private val missing = LeftOut(8, LeftOutReason.Missing, "IMG_2.jpg", "/storage/emulated/0/Download/IMG_2.jpg")

    private fun preview(
        files: Int = 248,
        folders: Int = 14,
        sources: List<BinSource> = listOf(BinSource("Camera", "DCIM/Camera", 192), BinSource("Download", "Download", 38)),
        otherStorage: Int = 0,
        shared: Int = 0,
        copyBytes: Long = 0,
        freeBytes: Long = 0,
        inPlace: Int = 0,
        leftOut: List<LeftOut> = listOf(failed, missing),
    ) = BinPreview(files, folders, sources, otherStorage, shared, copyBytes, freeBytes, inPlace, leftOut)

    private fun report(
        state: RunState = RunState.Done,
        headline: String = "247 files binned into 14 category folders",
        message: String? = null,
        moved: Int = 247,
        copiedShared: Int = 0,
        folders: Int = 14,
        skipped: List<SkippedFile> = emptyList(),
        numbered: List<String> = emptyList(),
        inPlace: Int = 0,
        lastMoved: String? = "Fire Knife Spin.mp4",
        removedFolders: List<String> = emptyList(),
        stillToMove: Int = 0,
        leftOut: List<LeftOut> = emptyList(),
        checkOk: Boolean? = true,
    ) = MoveReport(1, MoveKind.Bin, state, headline, message, moved, copiedShared, folders, skipped, numbered, 0, inPlace, lastMoved, removedFolders, stillToMove, leftOut, checkOk)

    // ---------------------------------------------------------------- State 1: confirm

    @Test fun confirm_saysHowManyFilesGoWhere() {
        val ui = confirmUi(preview(), "Hawaii Photos")
        assertEquals("Bin 248 files into 14 folders", ui.headline)
        assertEquals("Into Agentic Binning/Hawaii Photos/, one folder per category.", ui.intro)
        assertEquals("Bin 248 files", ui.startLabel)
    }

    @Test fun confirm_singular() {
        val ui = confirmUi(preview(files = 1, folders = 1, sources = listOf(BinSource("Camera", "DCIM/Camera", 1))), "Trip")
        assertEquals("Bin 1 file into 1 folder", ui.headline)
        assertEquals("Bin 1 file", ui.startLabel)
    }

    @Test fun confirm_listsEachSourceAndWhatStays() {
        val ui = confirmUi(preview(inPlace = 5), "Trip")
        assertEquals(
            listOf("From Camera" to "192", "From Download" to "38", "Already in their folders" to "5"),
            ui.facts.map { it.text to it.value },
        )
        assertEquals("DCIM/Camera", ui.facts[0].sub)
        assertEquals("Stay as they are", ui.facts[2].sub)
    }

    @Test fun confirm_hidesAlreadyInPlaceWhenThereAreNone() {
        assertTrue(confirmUi(preview(inPlace = 0), "Trip").facts.none { it.text == "Already in their folders" })
    }

    /** B1, B6: another volume is copied, and the sheet says what that takes. */
    @Test fun confirm_saysFilesOnAnotherStorageAreCopied() {
        val ui = confirmUi(preview(otherStorage = 18, copyBytes = 2_900_000_000L, freeBytes = 41_000_000_000L), "Trip")
        val fact = ui.facts.single { it.text == "From the SD card" }
        assertEquals("18", fact.value)
        assertEquals("Copied, checked, then deleted from the card · needs 2.7 GB free, 38.2 GB free", fact.sub)
    }

    /** P8's follow-on: the other project keeps its file. */
    @Test fun confirm_saysFilesInAnotherProjectAreCopied() {
        val fact = confirmUi(preview(shared = 2), "Trip").facts.single { it.text == "Also in another project" }
        assertEquals("2", fact.value)
        assertEquals("Copied into their folders; the other project keeps its file", fact.sub)
    }

    @Test fun confirm_warnsThereIsNoUndo() {
        assertEquals("No undo.", confirmUi(preview(), "Trip").callouts.first().bold)
    }

    /** B11, checked on the phone: Camera's files leave its album and appear in a category album. */
    @Test fun confirm_saysWhatGalleryWillShow() {
        assertEquals(
            "In Gallery they leave the Camera album and show up in albums named after their categories.",
            confirmUi(preview(), "Trip").callouts.last().text,
        )
        assertEquals(
            "In Gallery they show up in albums named after their categories.",
            confirmUi(preview(sources = listOf(BinSource("Download", "Download", 38))), "Trip").callouts.last().text,
        )
    }

    @Test fun confirm_listsWhatStaysWithWhy() {
        val rows = confirmUi(preview(), "Trip").left
        assertEquals(
            listOf(
                LeftRowUi(7, "VID_1.mp4", "Camera · Couldn't read this file.", "Failed"),
                LeftRowUi(8, "IMG_2.jpg", "Was in Download · not found", "Missing"),
            ),
            rows,
        )
    }

    // ---------------------------------------------------------------- State 2 and 6: progress

    private fun progress(
        done: Int = 12,
        total: Int = 248,
        current: String? = "Bamboo.mp4",
        paused: Boolean = false,
        next: String? = null,
        recent: List<MovedFile> = emptyList(),
        kind: MoveKind = MoveKind.Bin,
    ) = MoveProgress(kind, done, total, current, MovePhase.Moving, paused, from = "Camera", to = "Forest Nature", next = next, recent = recent)

    @Test fun card_showsTheFileAndWhereItGoes() {
        val ui = moveCardUi(progress(), resumedFrom = null, request = MoveRequest.None)
        assertEquals("Binning files", ui.title)
        assertEquals("13 of 248", ui.count)
        assertEquals("Bamboo.mp4", ui.file)
        assertEquals("Camera → Forest Nature", ui.fileSub)
        assertEquals("Other edits wait. You can leave this screen.", ui.note)
        assertEquals(12f / 248, ui.fraction, 0.0001f)
        assertNull(ui.banner)
        assertFalse(ui.paused)
    }

    @Test fun card_countNeverPassesTheTotal() {
        assertEquals("248 of 248", moveCardUi(progress(done = 248), null, MoveRequest.None).count)
    }

    @Test fun card_paused_namesTheNextFile() {
        val ui = moveCardUi(progress(current = null, paused = true, next = "Next.jpg"), null, MoveRequest.None)
        assertEquals("Paused", ui.title)
        assertEquals("12 of 248", ui.count)
        assertEquals("Next: Next.jpg", ui.file)
        assertEquals("The file in progress finished. Nothing is half moved.", ui.fileSub)
        assertEquals("Other edits wait until it ends.", ui.note)
        assertTrue(ui.paused)
    }

    /** B2: Pause and Stop say they wait for the file in progress, and can't be asked twice. */
    @Test fun card_pausingAndStopping_sayWhatTheyWaitFor() {
        val pausing = moveCardUi(progress(), null, MoveRequest.Pause)
        assertEquals("Pausing after this file…", pausing.note)
        assertFalse(pausing.canPause)
        assertTrue(pausing.canStop)
        val stopping = moveCardUi(progress(), null, MoveRequest.Stop)
        assertEquals("Stopping after this file…", stopping.note)
        assertFalse(stopping.canPause)
        assertFalse(stopping.canStop)
    }

    /** State 6: B3's resume says what happened. */
    @Test fun card_resumed_saysItPickedUpAndNothingWasLost() {
        val ui = moveCardUi(progress(done = 13), resumedFrom = 13, request = MoveRequest.None)
        assertEquals("Finishing Bin Files", ui.title)
        assertEquals("Picked up where it stopped when the app closed. 13 files had moved; nothing was lost.", ui.banner)
    }

    @Test fun card_resumedPausedRun_titleIsPaused() {
        val ui = moveCardUi(progress(current = null, paused = true, next = "N.jpg"), resumedFrom = 3, request = MoveRequest.None)
        assertEquals("Paused", ui.title)
        assertEquals("Picked up where it stopped when the app closed. 3 files had moved; nothing was lost.", ui.banner)
    }

    @Test fun card_listsRecentFiles() {
        val ui = moveCardUi(progress(recent = listOf(MovedFile("A.jpg", "Beach"))), null, MoveRequest.None)
        assertEquals(listOf(MovedFile("A.jpg", "Beach")), ui.recent)
    }

    // ---------------------------------------------------------------- State 3: report

    @Test fun report_done_headlineAndCheck() {
        val ui = reportUi(report())
        assertEquals("Files binned", ui.title)
        assertEquals("247 files binned into 14 category folders", ui.headline)
        assertEquals("Checked 247 files in 14 folders. Nothing missing or extra.", ui.sub)
        assertNull(ui.callout)
        assertEquals("Done", ui.primary)
        assertNull(ui.secondary)
        assertEquals(listOf("Moved now" to "247", "Folder check" to "OK"), ui.facts.map { it.text to it.value })
        assertFalse(ui.primaryStartsARun)
    }

    @Test fun report_done_showsOnlyWhatHappened() {
        val ui = reportUi(
            report(
                copiedShared = 2,
                moved = 245,
                numbered = listOf("A 2.jpg", "B 2.jpg", "C 2.jpg", "D 2.jpg"),
                removedFolders = listOf("Turtle Beach"),
                inPlace = 4,
            ),
        )
        assertEquals("Moved now", ui.facts[0].text)
        assertEquals("247", ui.facts[0].value)
        assertEquals("245 moved, 2 copied from another project", ui.facts[0].sub)
        val numbered = ui.facts.single { it.text == "Names that got a number" }
        assertEquals("4", numbered.value)
        assertEquals("A 2.jpg, B 2.jpg, C 2.jpg and 1 more", numbered.sub)
        assertEquals("Turtle Beach", ui.facts.single { it.text == "Empty folders removed" }.sub)
        assertEquals("4", ui.facts.single { it.text == "Already in place" }.value)
    }

    /** B4: a copy that didn't match is listed, and the user is told nothing was lost. */
    @Test fun report_listsWhatItCouldntMove() {
        val ui = reportUi(report(skipped = listOf(SkippedFile(3, "Sail.mp4", StepErrors.COPY_MISMATCH), SkippedFile(4, "B.jpg", StepErrors.INTERRUPTED), SkippedFile(5, "C.jpg", StepErrors.IO))))
        assertEquals(
            listOf(
                LeftRowUi(3, "Sail.mp4", "Its copy didn't match the original twice. Nothing was lost; the next Bin Files tries again.", "Skipped"),
                LeftRowUi(4, "B.jpg", "The app closed twice while moving it. Nothing was lost; the next Bin Files tries again.", "Skipped"),
                LeftRowUi(5, "C.jpg", "The phone refused to move it. Nothing was lost; the next Bin Files tries again.", "Skipped"),
            ),
            ui.skipped,
        )
    }

    @Test fun report_listsWhatItLeftBehind() {
        assertEquals(listOf(7L, 8L), reportUi(report(leftOut = listOf(failed, missing))).left.map { it.itemId })
    }

    @Test fun report_nothingToMove() {
        val ui = reportUi(report(headline = "Nothing to move. 12 files were already in place.", moved = 0, folders = 0, inPlace = 12))
        assertEquals("Nothing to move. 12 files were already in place.", ui.headline)
        assertEquals("Checked the category folders. Nothing missing or extra.", ui.sub)
        assertTrue(ui.facts.none { it.text == "Moved now" })
    }

    /** The check found something after the run: say so rather than "Nothing missing". */
    @Test fun report_checkFailed_saysSo() {
        val ui = reportUi(report(checkOk = false))
        assertEquals("Something in the category folders doesn't match. The next Bin Files shows what.", ui.sub)
        assertEquals("Check", ui.facts.single { it.text == "Folder check" }.value)
    }

    // ---------------------------------------------------------------- State 4: stopped

    @Test fun stoppedByTheUser_saysWhereAndOffersTheRest() {
        val ui = reportUi(report(state = RunState.Stopped, headline = "Bin Files stopped", moved = 37, stillToMove = 211))
        assertEquals("Bin Files stopped", ui.title)
        assertNull(ui.headline)
        assertEquals("Bin Files stopped.", ui.callout?.bold)
        assertEquals("You stopped it after Fire Knife Spin.mp4. Press Bin Files to move the rest.", ui.callout?.text)
        assertEquals(
            listOf("Moved" to "37", "Still where they were" to "211", "Folder check" to "OK"),
            ui.facts.map { it.text to it.value },
        )
        assertEquals("Moved by the next Bin Files", ui.facts[1].sub)
        assertEquals("The 37 moved files are all in place", ui.facts[2].sub)
        assertEquals("Bin 211 files", ui.primary)
        assertEquals("Close", ui.secondary)
        assertTrue(ui.primaryStartsARun)
    }

    @Test fun stoppedBeforeAnyFileMoved() {
        val ui = reportUi(report(state = RunState.Stopped, moved = 0, stillToMove = 3, lastMoved = null))
        assertEquals("You stopped it before any file moved. Press Bin Files to move them.", ui.callout?.text)
        assertEquals("Bin 3 files", ui.primary)
    }

    /** B6: the message names what to free up; Try again plans the rest afresh. */
    @Test fun stoppedByStorage_saysWhatToFreeUp() {
        val ui = reportUi(report(state = RunState.Stopped, message = "Storage is full. Free up 3.1 GB and press Bin Files again.", moved = 37, stillToMove = 211))
        assertEquals("Bin Files stopped: storage is full.", ui.callout?.bold)
        assertEquals("Free up 3.1 GB and press Bin Files again.", ui.callout?.text)
        assertEquals("Try again", ui.primary)
        assertTrue(ui.primaryStartsARun)
    }

    @Test fun failedRun_saysWhatWentWrong() {
        val ui = reportUi(report(state = RunState.Failed, message = "a.jpg wasn't where it should be after moving it", moved = 2, stillToMove = 5, checkOk = null))
        assertEquals("Bin Files stopped: something went wrong.", ui.callout?.bold)
        assertEquals("a.jpg wasn't where it should be after moving it.", ui.callout?.text)
        assertEquals("Try again", ui.primary)
        assertTrue(ui.facts.none { it.text == "Folder check" })
    }

    // ---------------------------------------------------------------- State 5: unknown files

    @Test fun unknownFiles_showPathsInsideTheProjectWithSizeAndDate() {
        val p = "/storage/emulated/0/Agentic Binning/Trip"
        val problems = LayoutProblems(missing = listOf("$p/Forest/Red.jpg"), unexpected = listOf("$p/Beach/IMG_1.jpg"))
        val ui = unknownUi(problems, p, "Trip") { UnknownFileFacts("Nov 2", "3.4 MB") }

        assertEquals("Nothing was moved.", ui.callout.bold)
        assertEquals("These files are in category folders, but they aren't in Trip. Bin Files checks the folders before it moves anything.", ui.callout.text)
        assertEquals(listOf(UnknownRowUi("Beach/IMG_1.jpg", "Added Nov 2 · 3.4 MB")), ui.unknown)
        assertEquals(listOf(UnknownRowUi("Forest/Red.jpg", "Binned, but no longer there. It is listed as Missing and doesn't block Bin Files.")), ui.missing)
        assertEquals(listOf("$p/Beach/IMG_1.jpg"), ui.paths)
    }
}
