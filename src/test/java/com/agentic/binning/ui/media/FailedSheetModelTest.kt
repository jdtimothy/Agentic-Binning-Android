package com.agentic.binning.ui.media

import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.projectmedia.CategoryChoice
import com.agentic.binning.projectmedia.ItemDetail
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedSheetModelTest {
    private fun detail(
        status: ItemStatus = ItemStatus.Failed,
        reason: String? = "decode",
        attempts: Int = 2,
        kind: ItemKind = ItemKind.Video,
    ) = ItemDetail(
        item = ItemEntity(
            id = 9, contentHash = "h", originalPath = "/storage/emulated/0/DCIM/Camera/VID_1.mp4", originalName = "VID_1.mp4",
            currentPath = "/storage/emulated/0/DCIM/Camera/VID_1.mp4", kind = kind, takenAt = 1_784_000_000_000,
            durationMs = if (kind == ItemKind.Video) 42_000 else null, width = null, height = null, status = status,
            addedAt = 0, failureReason = reason, attempts = attempts,
        ),
        description = null, actions = emptyList(), tags = emptyList(), suggestedName = null, shotType = null,
        categoryName = null, categoryDescription = null, categoryCreatedBy = null,
    )

    private fun ui(d: ItemDetail, draft: DetailDraft = DetailDraft.of(d), manual: Boolean = false) =
        failedUi(d, draft, manual, ZoneOffset.UTC)

    @Test
    fun decodeFailure_explainsAndOffersAllThree() {
        val u = ui(detail())
        assertEquals("Couldn't analyze", u.title)
        assertEquals("Failed · decode", u.badge)
        assertEquals("VID_1.mp4", u.fileName)
        assertEquals(
            "The phone couldn't decode this video, so the model never saw it. The file may be damaged or use a format " +
                "the phone's decoders can't read. Tried 2 times.",
            u.reason,
        )
        assertTrue(u.canRetry)
        assertTrue(u.canSetByHand)
        assertTrue(u.isVideo)
        assertEquals("0:42", u.duration)
        assertEquals("DCIM/Camera/VID_1.mp4", u.details.toMap()["Location"])
        assertEquals("Not blocked. This item stays where it is.", u.details.toMap()["Bin Files"])
        assertFalse(u.manual)
    }

    @Test
    fun otherFailures_useTheStoredReasonsWords_andCountOneTry() {
        val u = ui(detail(reason = "length", attempts = 1, kind = ItemKind.Photo))
        assertEquals("Failed · length", u.badge)
        assertEquals("The model's reply was cut off. Tried once.", u.reason)
        assertNull(u.duration)
    }

    @Test
    fun aFailureWithNoReason_saysSoGenerally() {
        val u = ui(detail(reason = null, attempts = 0))
        assertEquals("Failed", u.badge)
        assertEquals("Couldn't analyze this file.", u.reason)
    }

    @Test
    fun missingFile_canOnlyBeRemoved() {
        val u = ui(detail(status = ItemStatus.Missing, reason = null, attempts = 0))
        assertEquals("File missing", u.title)
        assertEquals("Missing", u.badge)
        assertEquals("The file is no longer at this location, so it can't be analyzed.", u.reason)
        assertFalse(u.canRetry)
        assertFalse(u.canSetByHand)
    }

    @Test
    fun byHand_needsANameAndACategory_andLocksBoth() {
        val d = detail()
        val blank = ui(d, manual = true)
        assertTrue(blank.manual)
        assertEquals("File name when binned: ….mp4", blank.nameHint)
        assertNull(blank.categoryLabel)
        assertFalse(blank.saveEnabled)
        assertEquals(
            "With a name and category the clip is binned like any other at the next Bin Files. It has no description or " +
                "tags, so search finds it only by name and category.",
            blank.callout,
        )
        assertNull(setByHandRequest(d, DetailDraft.of(d)))

        val named = DetailDraft.of(d).copy(name = " Sunset Drive Home ")
        assertFalse(ui(d, named, manual = true).saveEnabled)
        assertEquals("File name when binned: Sunset Drive Home.mp4", ui(d, named, manual = true).nameHint)

        val ready = named.copy(pick = CategoryPick.Existing(3, "Sunsets"))
        val u = ui(d, ready, manual = true)
        assertTrue(u.saveEnabled)
        assertEquals("Sunsets", u.categoryLabel)
        assertFalse(u.categoryIsNew)
        assertEquals(SetByHandRequest(9, "Sunset Drive Home", CategoryChoice.Existing(3)), setByHandRequest(d, ready))

        val newCat = named.copy(pick = CategoryPick.New("Drives"))
        assertTrue(ui(d, newCat, manual = true).categoryIsNew)
        assertEquals(SetByHandRequest(9, "Sunset Drive Home", CategoryChoice.New("Drives")), setByHandRequest(d, newCat))
    }

    @Test
    fun byHand_onAPhoto_namesItAPhoto() {
        val u = ui(detail(kind = ItemKind.Photo), manual = true)
        assertEquals("File name when binned: ….jpg", u.nameHint)
        assertTrue(u.callout.startsWith("With a name and category the photo is binned"))
    }

    @Test
    fun onlyFailedAndMissingItems_openThisSheet() {
        assertEquals(
            listOf(ItemStatus.Failed, ItemStatus.Missing),
            ItemStatus.entries.filter(::opensFailedSheet),
        )
    }
}
