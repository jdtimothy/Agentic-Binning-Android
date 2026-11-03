package com.agentic.binning.ui.media

import com.agentic.binning.analysis.ActionEntry
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

class DetailSheetModelTest {
    private fun detail(
        status: ItemStatus = ItemStatus.Sorted,
        name: String? = "Fire Dancers Night",
        nameSet: Boolean = false,
        categoryId: Long? = 7,
        categorySet: Boolean = false,
        kind: ItemKind = ItemKind.Photo,
        description: String? = "Two fire dancers.",
    ) = ItemDetail(
        item = ItemEntity(
            id = 5, contentHash = "h", originalPath = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", originalName = "IMG_1.jpg",
            currentPath = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", kind = kind, takenAt = 1_784_000_000_000,
            durationMs = if (kind == ItemKind.Video) 42_000 else null, width = 4000, height = 3000, status = status,
            categoryId = categoryId, friendlyName = name, nameSetByUser = nameSet, categorySetByUser = categorySet, addedAt = 0,
        ),
        description = description,
        actions = if (kind == ItemKind.Video) listOf(ActionEntry("0:05", "They spin the fire")) else emptyList(),
        tags = listOf("fire", "night"),
        suggestedName = "Fire dancers",
        shotType = "wide",
        categoryName = if (categoryId != null) "Fire Performance" else null,
        categoryDescription = if (categoryId != null) "Fire shows" else null,
        categoryCreatedBy = if (categoryId != null) "model" else null,
    )

    private fun ui(d: ItemDetail, draft: DetailDraft = DetailDraft.of(d)) = detailUi(d, draft, ZoneOffset.UTC)

    @Test
    fun untouched_isFromSorting_andSaveIsOff() {
        val u = ui(detail())
        assertEquals(LockMarker(MarkerKind.FromSorting, null), u.nameMarker)
        assertEquals(LockMarker(MarkerKind.FromSorting, null), u.categoryMarker)
        assertFalse(u.saveEnabled)
        assertEquals("Item details", u.title)
        assertEquals("Fire Performance", u.categoryLabel)
        assertEquals(StatusBadge.Sorted, u.badge)
        assertEquals("File name when binned: Fire Dancers Night.jpg · The model suggested “Fire dancers”", u.nameHint)
    }

    @Test
    fun lockedValues_showSetByYouWithUnlock_andUnlockTurnsSaveOn() {
        val d = detail(nameSet = true, categorySet = true)
        assertEquals(LockMarker(MarkerKind.SetByYou, "Unlock"), ui(d).nameMarker)

        val unlocked = DetailDraft.of(d).copy(unlockName = true)
        val u = ui(d, unlocked)
        assertEquals(LockMarker(MarkerKind.WillUnlock, "Undo"), u.nameMarker)
        assertEquals(LockMarker(MarkerKind.SetByYou, "Unlock"), u.categoryMarker)
        assertTrue(u.saveEnabled)
        assertEquals(SaveRequest(5, "Fire Dancers Night", null, unlockName = true, unlockCategory = false), saveRequest(d, unlocked))
    }

    @Test
    fun editingTheName_isPendingYours_andSavesTheTrimmedName() {
        val d = detail()
        val draft = DetailDraft.of(d).copy(name = "  Luau fire  ")
        val u = ui(d, draft)
        assertEquals(LockMarker(MarkerKind.PendingYours, null), u.nameMarker)
        assertEquals(LockMarker(MarkerKind.FromSorting, null), u.categoryMarker)
        assertTrue(u.saveEnabled)
        assertEquals("File name when binned: Luau fire.jpg · The model suggested “Fire dancers”", u.nameHint)
        assertEquals(SaveRequest(5, "Luau fire", null, unlockName = false, unlockCategory = false), saveRequest(d, draft))
    }

    @Test
    fun editingAfterUnlock_keepsTheLock() {
        val d = detail(nameSet = true)
        val draft = DetailDraft.of(d).copy(name = "New", unlockName = true)
        assertEquals(LockMarker(MarkerKind.PendingYours, null), ui(d, draft).nameMarker)
        assertFalse(saveRequest(d, draft).unlockName)
    }

    @Test
    fun aBlankName_turnsSaveOff() {
        val d = detail()
        assertFalse(ui(d, DetailDraft.of(d).copy(name = "   ")).saveEnabled)
    }

    @Test
    fun choosingACategory_isPendingYours() {
        val d = detail()
        val existing = DetailDraft.of(d).copy(pick = CategoryPick.Existing(9, "Beach"))
        assertEquals(LockMarker(MarkerKind.PendingYours, null), ui(d, existing).categoryMarker)
        assertEquals("Beach", ui(d, existing).categoryLabel)
        assertEquals(CategoryChoice.Existing(9), saveRequest(d, existing).category)

        val fresh = DetailDraft.of(d).copy(pick = CategoryPick.New("Snorkeling"))
        assertEquals("Snorkeling", ui(d, fresh).categoryLabel)
        assertTrue(ui(d, fresh).categoryIsNew)
        assertEquals(CategoryChoice.New("Snorkeling"), saveRequest(d, fresh).category)

        // Picking the category it already has changes nothing.
        val same = DetailDraft.of(d).copy(pick = CategoryPick.Existing(7, "Fire Performance"))
        assertFalse(ui(d, same).saveEnabled)
    }

    @Test
    fun waitingItem_isNotPlacedYet_withTheWarnCallout() {
        val d = detail(status = ItemStatus.Analyzed, categoryId = null)
        val u = ui(d)
        assertEquals("Waiting to be categorized", u.title)
        assertEquals(StatusBadge.Waiting, u.badge)
        assertEquals(LockMarker(MarkerKind.NotPlaced, null), u.categoryMarker)
        assertNull(u.categoryLabel)
        assertTrue(u.callout.warn)
        assertTrue(u.callout.text.startsWith("Sorting couldn't place this item."))
    }

    @Test
    fun sortedItem_saysWhereTheFileStays_andListsDetails() {
        val u = ui(detail())
        assertFalse(u.callout.warn)
        assertEquals("Not binned yet. The file stays in DCIM/Camera until Bin Files.", u.callout.text)
        assertEquals(
            listOf(
                "Shot type" to "Wide",
                "Taken" to "Jul 14, 2026 · 3:33 AM",
                "Size" to "4000 × 3000",
                "Original file" to "IMG_1.jpg",
                "Location" to "DCIM/Camera/IMG_1.jpg",
                "Category note" to "Fire shows",
            ),
            u.details,
        )
    }

    @Test
    fun video_hasDurationAndActions_andNoAnalysisSaysSo() {
        val v = ui(detail(kind = ItemKind.Video))
        assertEquals("0:42", v.duration)
        assertEquals(listOf("0:05" to "They spin the fire"), v.actions.map { it.time to it.text })
        assertEquals("File name when binned: Fire Dancers Night.mp4 · The model suggested “Fire dancers”", v.nameHint)

        val bare = ui(detail(description = null))
        assertEquals("No analysis. Name and category were set by hand.", bare.description)
    }
}
