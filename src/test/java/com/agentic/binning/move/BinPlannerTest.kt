package com.agentic.binning.move

import com.agentic.binning.db.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinPlannerTest {
    private val p = "/storage/emulated/0/Agentic Binning/Hawaii Photos"
    private val cats = mapOf(1L to "Beach Scenes", 2L to "Turtle Beach")

    private fun item(
        id: Long,
        path: String,
        cat: Long? = 1,
        name: String? = "Item $id",
        status: ItemStatus = ItemStatus.Sorted,
        original: String = path.substringAfterLast('/'),
    ) = BinItem(id, status, cat, name, path, original)

    private fun plan(
        items: List<BinItem>,
        onDisk: Set<String> = items.map { it.currentPath }.toSet(),
        shared: Set<String> = emptySet(),
        only: Set<Long>? = null,
    ) = BinPlanner.plan(p, items, cats, onDisk, { it in onDisk }, shared, only)

    @Test fun sortedItems_getStepsInIdOrder() {
        val r = plan(listOf(item(2, "/sdcard/DCIM/Camera/b.jpg", name = "Umbrellas"), item(1, "/sdcard/Download/a.JPG", name = "Morning")))
        assertEquals(
            listOf(
                PlannedStep(1, "/sdcard/Download/a.JPG", "$p/Beach Scenes/Morning.JPG", keepSource = false, numbered = false),
                PlannedStep(2, "/sdcard/DCIM/Camera/b.jpg", "$p/Beach Scenes/Umbrellas.jpg", keepSource = false, numbered = false),
            ),
            r.steps,
        )
        assertTrue(r.inPlace.isEmpty() && r.leftOut.isEmpty() && r.becameMissing.isEmpty())
    }

    @Test fun extension_comesFromTheOriginalName() {
        val r = plan(listOf(item(1, "$p/Copied from Maui/IMG_1.HEIC", original = "IMG_1.HEIC", name = "Crater")))
        assertEquals("$p/Beach Scenes/Crater.HEIC", r.steps.single().dest)
    }

    @Test fun inPlace_isMarkedNotMoved() {
        val r = plan(listOf(item(1, "$p/Beach Scenes/Item 1.jpg"), item(2, "$p/Beach Scenes/Item 2 3.jpg", status = ItemStatus.Binned)))
        assertTrue(r.steps.isEmpty())
        // Only an item not yet marked binned needs marking.
        assertEquals(listOf(1L), r.inPlace)
    }

    /** B10: a Re-sort may give a binned item another category; the next Bin Files moves it. */
    @Test fun binnedButMoved_isPlannedAgain() {
        val r = plan(listOf(item(1, "$p/Turtle Beach/Item 1.jpg", cat = 1, status = ItemStatus.Binned)))
        assertEquals("$p/Beach Scenes/Item 1.jpg", r.steps.single().dest)
    }

    @Test fun failedAndMissing_areLeftOutWithTheirReason() {
        val r = plan(
            listOf(
                item(1, "/sdcard/a.mp4", cat = null, name = null, status = ItemStatus.Failed),
                item(2, "/sdcard/b.jpg", status = ItemStatus.Missing),
                item(3, "/sdcard/c.jpg", cat = null, status = ItemStatus.Analyzed),
                item(4, "/sdcard/d.jpg", status = ItemStatus.Failed),
            ),
        )
        assertTrue(r.steps.isEmpty())
        assertEquals(
            listOf(
                LeftOut(1, LeftOutReason.Failed),
                LeftOut(2, LeftOutReason.Missing),
                LeftOut(3, LeftOutReason.NoCategory),
                LeftOut(4, LeftOutReason.Failed),
            ),
            r.leftOut,
        )
    }

    @Test fun goneSource_becomesMissing() {
        val r = plan(listOf(item(1, "/sdcard/a.jpg"), item(2, "/sdcard/b.jpg")), onDisk = setOf("/sdcard/b.jpg"))
        assertEquals(listOf(1L), r.becameMissing)
        assertEquals(listOf(2L), r.steps.map { it.itemId })
    }

    @Test fun goneBinnedFile_becomesMissing() {
        val r = plan(listOf(item(1, "$p/Beach Scenes/Item 1.jpg", status = ItemStatus.Binned)), onDisk = emptySet())
        assertEquals(listOf(1L), r.becameMissing)
        assertTrue(r.inPlace.isEmpty())
    }

    @Test fun takenName_isNumbered() {
        val r = plan(listOf(item(1, "/sdcard/a.jpg", name = "Sunset")), onDisk = setOf("/sdcard/a.jpg", "$p/Beach Scenes/sunset.jpg"))
        val step = r.steps.single()
        assertEquals("$p/Beach Scenes/Sunset 2.jpg", step.dest)
        assertTrue(step.numbered)
    }

    /** P8's follow-on ruling: a file another listed project also holds is copied, never taken. */
    @Test fun sharedItem_isCopiedNotMoved() {
        val r = plan(listOf(item(1, "/sdcard/DCIM/Camera/a.jpg"), item(2, "/sdcard/DCIM/Camera/b.jpg")), shared = setOf("/sdcard/DCIM/Camera/a.jpg"))
        assertTrue(r.steps.first { it.itemId == 1L }.keepSource)
        assertFalse(r.steps.first { it.itemId == 2L }.keepSource)
    }

    @Test fun only_limitsThePlan() {
        val r = plan(listOf(item(1, "/sdcard/a.jpg"), item(2, "/sdcard/b.jpg"), item(3, "/sdcard/c.jpg", status = ItemStatus.Failed)), only = setOf(2))
        assertEquals(listOf(2L), r.steps.map { it.itemId })
        assertTrue(r.leftOut.isEmpty())
    }

    @Test fun nextFree_skipsEveryTakenNumber() {
        val taken = setOf("$p/Beach Scenes/Sunset.jpg", "$p/Beach Scenes/sunset 2.jpg", "$p/Beach Scenes/Sunset 3.jpg").map { it.lowercase() }.toSet()
        assertEquals("$p/Beach Scenes/Sunset 4.jpg", MovePlanner.nextFree("$p/Beach Scenes/Sunset.jpg") { it.lowercase() in taken })
        assertEquals("$p/Beach Scenes/Sunset 4.jpg", MovePlanner.nextFree("$p/Beach Scenes/Sunset 2.jpg") { it.lowercase() in taken })
        assertEquals("$p/Beach Scenes/Gecko.jpg", MovePlanner.nextFree("$p/Beach Scenes/Gecko.jpg") { false })
    }
}
