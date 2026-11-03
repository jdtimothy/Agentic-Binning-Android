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
