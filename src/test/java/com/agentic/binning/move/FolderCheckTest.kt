package com.agentic.binning.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCheckTest {
    private val p = "/storage/emulated/0/Agentic Binning/Hawaii Photos"
    private val cats = listOf("Beach Scenes", "Turtle Beach")

    private fun check(
        items: List<CheckItem>,
        disk: List<String>,
        removed: List<String> = emptyList(),
        categories: List<String> = cats,
    ) = FolderCheck.check(p, categories, items, removed, disk)

    @Test fun clean_isOk() {
        val r = check(listOf(CheckItem("$p/Beach Scenes/a.jpg", binned = true)), listOf("$p/Beach Scenes/a.jpg"))
        assertTrue(r.ok)
    }

    /** B5: a binned file deleted in Gallery is reported (the caller marks it Missing) but never blocks. */
    @Test fun missingBinned_isReportedNotBlocking() {
        val r = check(listOf(CheckItem("$p/Beach Scenes/a.jpg", binned = true)), emptyList())
        assertEquals(listOf("$p/Beach Scenes/a.jpg"), r.missing)
        assertTrue(r.unexpected.isEmpty())
        assertTrue(FolderCheck.blocks(r).not())
    }

    @Test fun unknownFile_isUnexpected() {
        val r = check(emptyList(), listOf("$p/Beach Scenes/IMG_4410.jpg"))
        assertEquals(listOf("$p/Beach Scenes/IMG_4410.jpg"), r.unexpected)
        assertTrue(FolderCheck.blocks(r))
    }

    @Test fun removedFile_isSkipped() {
        val r = check(emptyList(), listOf("$p/Turtle Beach/Sign.jpg"), removed = listOf("$p/Turtle Beach/Sign.jpg"))
        assertTrue(r.ok)
    }

    /** A binned item a Re-sort moved to another category (B10), or a failed item added from there. */
    @Test fun unbinnedItemInACategoryFolder_isExpected() {
        val r = check(listOf(CheckItem("$p/Turtle Beach/Item 1.jpg", binned = false)), listOf("$p/Turtle Beach/Item 1.jpg"))
        assertTrue(r.ok)
    }

    @Test fun hiddenFiles_areIgnored() {
        val r = check(emptyList(), listOf("$p/Beach Scenes/.nomedia", "$p/Beach Scenes/.thumbs/x.jpg"))
        assertTrue(r.ok)
    }

    @Test fun filesOutsideCategoryFolders_areIgnored() {
        val r = check(emptyList(), listOf("$p/.binning/project.db", "$p/Copied from Maui/a.jpg", "$p/loose.jpg", "/sdcard/DCIM/Camera/b.jpg"))
        assertTrue(r.ok)
    }

    @Test fun caseDiffers_stillMatches() {
        val r = check(listOf(CheckItem("$p/Beach Scenes/A.JPG", binned = true)), listOf("$p/beach scenes/a.jpg"))
        assertTrue(r.ok)
    }

    @Test fun categoryNames_areMadeSafeLikeThePlanner() {
        // ProjectFolders.folderNameFor replaces ':' with '_' but keeps the apostrophe.
        assertEquals("Mama's_ Fish", MovePlanner.folderFor("Mama's: Fish"))
        val r = check(emptyList(), listOf("$p/Mama's_ Fish/a.jpg"), categories = listOf("Mama's: Fish"))
        assertEquals(listOf("$p/Mama's_ Fish/a.jpg"), r.unexpected)
    }

    @Test fun categoryFiles_listsEveryCategoryFolder() {
        val listed = mutableListOf<String>()
        val files = FolderCheck.categoryFiles(p, cats) { dir -> listed += dir; listOf("$dir/x.jpg") }
        assertEquals(listOf("$p/Beach Scenes", "$p/Turtle Beach"), listed)
        assertEquals(listOf("$p/Beach Scenes/x.jpg", "$p/Turtle Beach/x.jpg"), files)
    }
}
