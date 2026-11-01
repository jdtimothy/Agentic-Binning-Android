package com.agentic.binning.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutCheckTest {
    private val p = "/storage/emulated/0/Agentic Binning/Hawaii Photos"
    private val folders = setOf("Turtle Beach", "Breakfast")
    private val binned = listOf("$p/Turtle Beach/Sea Turtles Resting Sand.jpg", "$p/Breakfast/Macadamia Pancakes.jpg")

    @Test fun matchingLayout_isOk() {
        assertTrue(LayoutCheck.check(p, folders, binned, binned, emptyList()).ok)
    }

    @Test fun missingBinnedFile_isReported() {
        val r = LayoutCheck.check(p, folders, binned, binned.take(1), emptyList())
        assertEquals(listOf("$p/Breakfast/Macadamia Pancakes.jpg"), r.missing)
        assertTrue(r.unexpected.isEmpty())
    }

    @Test fun strayFileInACategoryFolder_isReported() {
        val stray = "$p/Turtle Beach/IMG_0001.jpg"
        val r = LayoutCheck.check(p, folders, binned, binned + stray, emptyList())
        assertEquals(listOf(stray), r.unexpected)
    }

    @Test fun removedItemsFile_isIgnored() {
        val removed = "$p/Turtle Beach/Turtle Beach Sign.jpg"
        assertTrue(LayoutCheck.check(p, folders, binned, binned + removed, listOf(removed)).ok)
    }

    @Test fun binningFolderAndFilesOutsideCategoryFolders_areNotChecked() {
        val others = listOf(
            "$p/.binning/project.db",
            "$p/.binning/thumbs/1.jpg",
            "$p/notes.txt",
            "$p/Not A Category/x.jpg",
            "/sdcard/DCIM/Camera/y.jpg",
        )
        assertTrue(LayoutCheck.check(p, folders, binned, binned + others, emptyList()).ok)
    }

    @Test fun pathsCompareIgnoringCase() {
        val onDisk = binned.map { it.replace("Turtle Beach/", "turtle beach/") }
        assertTrue(LayoutCheck.check(p, folders, binned, onDisk, emptyList()).ok)
    }
}
