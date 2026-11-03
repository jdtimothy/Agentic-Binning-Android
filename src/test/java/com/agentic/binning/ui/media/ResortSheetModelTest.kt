package com.agentic.binning.ui.media

import com.agentic.binning.projectmedia.RESORT_BUSY
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.settings.SortingModelChoice.Claude
import com.agentic.binning.settings.SortingModelChoice.Lfm3_1_7B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResortSheetModelTest {
    private val counts = ResortCounts(analyzed = 15, failed = 1, myCategories = 3, myItems = 5)

    private fun ui(
        counts: ResortCounts = this.counts,
        settingsChoice: SortingModelChoice = Lfm3_1_7B,
        chosen: SortingModelChoice = settingsChoice,
        keepEdits: Boolean = true,
        unavailable: Map<SortingModelChoice, String> = emptyMap(),
        busy: Boolean = false,
    ) = resortUi(counts, settingsChoice, chosen, keepEdits, unavailable, busy)

    @Test
    fun listsWhatSettingsLists_withTheSettingsChoiceMarkedAndPicked() {
        val u = ui(settingsChoice = Claude)
        assertEquals(listOf(Claude, Lfm3_1_7B), u.rows.map { it.choice })
        assertEquals(listOf("Claude (cloud)", "LFM3-1.7B (on device)"), u.rows.map { it.title })
        assertEquals(listOf(true, false), u.rows.map { it.settingsChoice })
        assertEquals(listOf(true, false), u.rows.map { it.selected })
        assertEquals("Sends descriptions and tags, never photos", u.rows[0].subtitle)
        assertEquals("Runs on this phone", u.rows[1].subtitle)
    }

    @Test
    fun readyToRun_namesTheCountAndLeavesFailedItemsOut() {
        val u = ui()
        assertEquals(
            "Sorts 15 analyzed items again from scratch. The categories may change completely: new names, merges " +
                "and splits. 1 failed item is left out.",
            u.intro,
        )
        assertEquals("Re-sort 15 items", u.buttonLabel)
        assertTrue(u.enabled)
        assertNull(u.reason)
        assertEquals("Runs on this phone's NPU. Analysis waits until it finishes.", u.modelNote)
        assertEquals(
            listOf("Category names and descriptions", "Which items each category holds", "Friendly names that sorting chose"),
            u.changes,
        )
        assertEquals(listOf("Any file on the phone. Only the database changes.", "Files move only when you press Bin Files."), u.unchanged)
    }

    @Test
    fun claudeNote_saysWhatIsSent_withoutACost() {
        val note = ui(chosen = Claude).modelNote
        assertEquals(
            "Sends the text of 15 items (descriptions, actions, tags, suggested names) and the category list to Claude. " +
                "Never photos.",
            note,
        )
        assertFalse(note.contains("$"))
    }

    @Test
    fun keepMyEdits_countsWhatItKeeps_andWhatUntickingGivesUp() {
        assertTrue(ui().keepEdits)
        assertEquals("Keeps the 3 categories and 5 items you set. New items can still join your categories.", ui().keepText)
        assertEquals("Your 3 categories and 5 items may be replaced. They stop being locked.", ui(keepEdits = false).keepText)
        val one = counts.copy(myCategories = 1, myItems = 0)
        assertEquals("Keeps the 1 category you set. New items can still join your categories.", ui(counts = one).keepText)
        val none = counts.copy(myCategories = 0, myItems = 0)
        assertEquals("Nothing is set by you yet.", ui(counts = none).keepText)
        assertEquals("Nothing is set by you yet.", ui(counts = none, keepEdits = false).keepText)
    }

    @Test
    fun aModelThatCantRun_isMarked_andTurnsTheButtonOffWithItsReason() {
        val missing = mapOf(Claude to "Add your API key to use Claude")
        val lfm = ui(unavailable = missing)
        assertTrue(lfm.enabled)
        assertTrue(lfm.rows[0].unavailable)
        assertEquals("Add your API key to use Claude", lfm.rows[0].subtitle)

        val claude = ui(chosen = Claude, unavailable = missing)
        assertFalse(claude.enabled)
        assertEquals("Add your API key to use Claude", claude.reason)
    }

    @Test
    fun aRunInProgress_turnsTheButtonOff_beforeAnyOtherReason() {
        val u = ui(chosen = Claude, unavailable = mapOf(Claude to "Add your API key to use Claude"), busy = true)
        assertFalse(u.enabled)
        assertEquals(RESORT_BUSY, u.reason)
    }

    @Test
    fun nothingAnalyzed_hasNothingToResort() {
        val u = ui(counts = ResortCounts(analyzed = 0, failed = 2, myCategories = 0, myItems = 0))
        assertFalse(u.enabled)
        assertEquals(NOTHING_TO_RESORT, u.reason)
        assertEquals("Re-sort 0 items", u.buttonLabel)
    }

    @Test
    fun oneItem_isSingular() {
        val u = ui(counts = ResortCounts(analyzed = 1, failed = 0, myCategories = 0, myItems = 0))
        assertEquals("Re-sort 1 item", u.buttonLabel)
        assertTrue(u.intro.startsWith("Sorts 1 analyzed item again from scratch."))
        assertFalse(u.intro.contains("left out"))
    }
}
