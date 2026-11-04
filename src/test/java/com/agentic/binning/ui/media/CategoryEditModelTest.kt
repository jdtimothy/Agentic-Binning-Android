package com.agentic.binning.ui.media

import com.agentic.binning.db.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryEditModelTest {
    private val cats = listOf(
        CategoryEntity(1, "Turtle Beach", "", "model", null),
        CategoryEntity(2, "Beach Scenes", "", "model", null),
        CategoryEntity(3, "Luau", "", "user", null),
        CategoryEntity(4, "Sunsets", "", "model", null),
    )
    private val counts = mapOf(1L to 4, 2L to 6, 3L to 2, 4L to 3)

    private fun ui(d: CategoryEditDraft) = categoryEditUi(cats, counts, d)

    private fun row(d: CategoryEditDraft, id: Long) = ui(d).rows.single { it.id == id }

    // Turtle Beach holds 3 binned files, Beach Scenes 2; the others none.
    private val binned = mapOf(1L to 3, 2L to 2)

    private fun binnedUi(d: CategoryEditDraft) = categoryEditUi(cats, counts, d, binned)

    /** 2b state 5a: the footer says what a Save does to files. */
    @Test
    fun note_saysHowManyBinnedFilesMoveNow() {
        assertNull("no edits, nothing to say", binnedUi(CategoryEditDraft()).note)
        val renamed = CategoryEditDraft().startRename(1, cats).editTo("Sea Turtles").finishRename(cats)
        assertEquals("Saving moves 3 binned files right away, one at a time. Other files move at the next Bin Files.", binnedUi(renamed).note)
    }

    @Test
    fun note_noBinnedFiles_saysOnlyTheDatabaseChanges() {
        val renamed = CategoryEditDraft().startRename(3, cats).editTo("Party").finishRename(cats)
        assertEquals("Only the database changes. Files move at the next Bin Files.", binnedUi(renamed).note)
    }

    @Test
    fun note_oneFile_isSingular() {
        val renamed = CategoryEditDraft().startRename(1, cats).editTo("Sea Turtles").finishRename(cats)
        assertEquals(
            "Saving moves 1 binned file right away, one at a time. Other files move at the next Bin Files.",
            categoryEditUi(cats, counts, renamed, mapOf(1L to 1)).note,
        )
    }

    /** A merge moves only the dropped category's files, unless the kept one is renamed too. */
    @Test
    fun note_merge_countsWhatMoves() {
        val merged = CategoryEditDraft().tick(1).tick(2).keep(2).merge()
        assertEquals("Saving moves 3 binned files right away, one at a time. Other files move at the next Bin Files.", binnedUi(merged).note)
        val alsoRenamed = merged.startRename(2, cats).editTo("Coast").finishRename(cats)
        assertEquals("Saving moves 5 binned files right away, one at a time. Other files move at the next Bin Files.", binnedUi(alsoRenamed).note)
    }

    /** 2b state 5b: each choice in the merge box says what it does to files. */
    @Test
    fun mergeBox_saysWhatEachChoiceMoves() {
        val options = binnedUi(CategoryEditDraft().tick(1).tick(3)).mergeBox!!.options
        assertEquals("No files move now. Luau files go into the Turtle Beach folder at the next Bin Files.", options[0].detail)
        assertEquals("3 binned files move from the Turtle Beach folder into a new Luau folder when you save.", options[1].detail)

        val both = binnedUi(CategoryEditDraft().tick(1).tick(2)).mergeBox!!.options
        assertEquals("2 binned files move into the Turtle Beach folder when you save.", both[0].detail)
        assertEquals("3 binned files move into the Beach Scenes folder when you save.", both[1].detail)
    }

    @Test
    fun mergeBox_nothingBinned_saysNoFilesMove() {
        val options = ui(CategoryEditDraft().tick(1).tick(2)).mergeBox!!.options
        assertEquals(listOf("No files move now.", "No files move now."), options.map { it.detail })
    }

    @Test
    fun untouched_listsEveryCategory_andSaveIsOff() {
        val u = ui(CategoryEditDraft())
        assertEquals(listOf("Turtle Beach", "Beach Scenes", "Luau", "Sunsets"), u.rows.map { it.name })
        assertEquals(listOf(4, 6, 2, 3), u.rows.map { it.count })
        assertEquals(listOf(false, false, true, false), u.rows.map { it.yours })
        assertNull(u.mergeBox)
        assertFalse(u.canSave)
        assertEquals("Save", u.saveLabel)
    }

    @Test
    fun rename_isStagedWithItsNote() {
        val d = CategoryEditDraft().startRename(1, cats).editTo("  Sea   Turtles ").finishRename(cats)
        val r = row(d, 1)
        assertEquals("Sea Turtles", r.name)
        assertEquals(listOf("Renamed from Turtle Beach"), r.changes)
        assertTrue(ui(d).canSave)
        assertEquals("Save 1 change", ui(d).saveLabel)
        assertEquals(CategoryEdits(renames = mapOf(1L to "Sea Turtles"), merges = emptyList()), categoryEditsOf(cats, d))
    }

    @Test
    fun rename_clashesWithStagedNames_notJustSavedOnes() {
        val d = CategoryEditDraft().startRename(1, cats).editTo("Coral").finishRename(cats).startRename(4, cats).editTo("corals")
        val editing = row(d, 4)
        assertTrue(editing.editing)
        assertEquals("Same as “Coral”. Tick both to merge instead.", editing.editError)
        assertFalse(editing.canFinish)
        // Finishing anyway changes nothing.
        assertEquals(d.copy(editing = 4), d.finishRename(cats).copy(editing = 4))

        // The old name of a category renamed away is free again.
        val swap = CategoryEditDraft().startRename(1, cats).editTo("Beach Scenes")
        assertEquals("Same as “Beach Scenes”. Tick both to merge instead.", row(swap, 1).editError)
        val freed = CategoryEditDraft().startRename(2, cats).editTo("Shore").finishRename(cats).startRename(1, cats).editTo("Beach Scenes")
        assertNull(row(freed, 1).editError)
    }

    @Test
    fun rename_backToTheSavedName_isNoChange() {
        val d = CategoryEditDraft().startRename(1, cats).editTo("Coral").finishRename(cats).startRename(1, cats).editTo("Turtle Beach").finishRename(cats)
        assertTrue(row(d, 1).changes.isEmpty())
        assertFalse(ui(d).canSave)
    }

    @Test
    fun tickingTwo_opensTheMergeBox_andAThirdIsOff() {
        val d = CategoryEditDraft().tick(1).tick(2)
        val box = ui(d).mergeBox!!
        assertEquals("Merge Turtle Beach and Beach Scenes", box.title)
        assertEquals(listOf("Keep the name “Turtle Beach”" to true, "Keep the name “Beach Scenes”" to false), box.options.map { it.label to it.selected })
        assertEquals(listOf(true, true, false, false), ui(d).rows.map { it.tickEnabled })

        val keepB = d.keep(2)
        assertEquals(listOf(false, true), ui(keepB).mergeBox!!.options.map { it.selected })
        // Unticking closes the box.
        assertNull(ui(keepB.tick(1)).mergeBox)
    }

    @Test
    fun merge_isStaged_theDroppedOneHides_andCountsAdd() {
        val d = CategoryEditDraft().tick(1).tick(2).keep(2).merge()
        val u = ui(d)
        assertEquals(listOf("Beach Scenes", "Luau", "Sunsets"), u.rows.map { it.name })
        val kept = row(d, 2)
        assertEquals(10, kept.count)
        assertEquals(listOf("Merged with Turtle Beach"), kept.changes)
        assertNull(u.mergeBox)
        assertEquals("Save 1 change", u.saveLabel)
        assertEquals(CategoryEdits(renames = emptyMap(), merges = listOf(2L to 1L)), categoryEditsOf(cats, d))
    }

    @Test
    fun aRenameOfTheDroppedCategory_isLeftOut_andTheSurvivorKeepsItsRename() {
        val d = CategoryEditDraft()
            .startRename(1, cats).editTo("Turtles").finishRename(cats)
            .startRename(2, cats).editTo("Shore").finishRename(cats)
            .tick(1).tick(2).keep(2).merge()
        assertEquals(listOf("Renamed from Beach Scenes", "Merged with Turtles"), row(d, 2).changes)
        assertEquals(CategoryEdits(renames = mapOf(2L to "Shore"), merges = listOf(2L to 1L)), categoryEditsOf(cats, d))
        assertEquals("Save 2 changes", ui(d).saveLabel)
    }

    @Test
    fun cancelInTheMergeBox_unticksBoth() {
        val d = CategoryEditDraft().tick(3).tick(4).untick()
        assertTrue(d.ticked.isEmpty())
        assertNull(ui(d).mergeBox)
    }
}
