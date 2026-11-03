package com.agentic.binning.sorting

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortPromptTest {
    @Test fun itemLine_cutsDescriptionOnWord() {
        val words = (1..80).joinToString(" ") { "word$it" }
        val line = SortPrompt.itemLine(item(7, description = words, tags = listOf("x", "y")))
        val cols = line.split("\t")
        assertEquals(5, cols.size)
        assertEquals("7", cols[0])
        assertEquals("photo", cols[1])
        assertEquals("Item 7", cols[2])
        assertEquals("x, y", cols[3])
        val desc = cols[4]
        assertTrue(desc.length <= 240)
        assertTrue("ends on a whole word: $desc", words.startsWith(desc) && words[desc.length] == ' ')
        assertTrue(desc.length > 200)
    }

    @Test fun itemLine_shortDescriptionKept() {
        val cols = SortPrompt.itemLine(item(1, description = "Short.")).split("\t")
        assertEquals("Short.", cols[4])
    }

    @Test fun itemLine_tabsAndNewlinesInTextAreFlattened() {
        val cols = SortPrompt.itemLine(item(1, name = "A\tB", description = "one\ntwo\tthree")).split("\t")
        assertEquals(5, cols.size)
        assertEquals("A B", cols[2])
        assertEquals("one two three", cols[4])
    }

    @Test fun itemLine_videoIncludesActions() {
        val actions = (1..30).map { "0:%02d Something happens number $it".format(it) }
        val cols = SortPrompt.itemLine(item(9, kind = ItemKind.Video, actions = actions)).split("\t")
        assertEquals(6, cols.size)
        assertEquals("video", cols[1])
        assertTrue(cols[5].startsWith("0:01 Something happens number 1; 0:02 "))
        assertTrue(cols[5].length <= 160)
    }

    @Test fun itemLine_videoWithoutActionsHasEmptyLastColumn() {
        val line = SortPrompt.itemLine(item(9, kind = ItemKind.Video))
        assertEquals(6, line.split("\t").size)
        assertTrue(line.endsWith("\t"))
    }

    @Test fun categoryLine_showsLockedOrOpen() {
        assertEquals("Sea Lions\t12\tlocked\tPinnipeds", SortPrompt.categoryLine(category("Sea Lions", 12, "user", "Pinnipeds")))
        assertEquals("Food\t3\topen\tMeals", SortPrompt.categoryLine(category("Food", 3, "model", "Meals")))
    }

    @Test fun system_statesTheRules() {
        val s = SortPrompt.system().lowercase()
        for (needle in listOf("existing categor", "never rename or merge", "one-item", "catch-all", "one general word", "40", "2-6 words", "description")) {
            assertTrue("system prompt lacks '$needle'", s.contains(needle))
        }
    }

    @Test fun system_saysCategoriesDescribeContentNotFileType() {
        val s = SortPrompt.system()
        assertTrue(s, s.contains("what is in the media (its subject, place or event), never the file type"))
    }

    @Test fun assignUser_listsItemsCategoriesAndFeedback() {
        val text = SortPrompt.assignUser(
            listOf(item(1), item(2)),
            listOf(category("Food", 2)),
            listOf("Unknown category 'X'"),
        )
        assertTrue(text.contains("Food\t2\topen"))
        assertTrue(text.contains("1\tphoto\tItem 1"))
        assertTrue(text.contains("2\tphoto\tItem 2"))
        assertTrue(text.contains("Unknown category 'X'"))
    }

    @Test fun assignUser_noFeedbackNoCorrectionBlock() {
        val text = SortPrompt.assignUser(listOf(item(1)), emptyList(), emptyList())
        assertFalse(text.contains("rejected"))
    }

    /** I2: a tighter cap than the system prompt's 12 is stated in the chunk's own prompt. */
    @Test fun assignUser_statesATighterNewCategoryCap() {
        val capped = SortPrompt.assignUser(listOf(item(1)), listOf(category("Food", 2)), emptyList(), maxNewCategories = 3)
        assertTrue(capped, capped.contains("Add at most 3 new categories in this reply."))
        val default = SortPrompt.assignUser(listOf(item(1)), listOf(category("Food", 2)), emptyList())
        assertFalse(default, default.contains("new categories in this reply"))
    }

    @Test fun proposeUser_sendsNameAndTagsOnly() {
        val text = SortPrompt.proposeUser(listOf(item(1, name = "Sails", tags = listOf("boat", "sea"), description = "SECRETDESC")))
        assertTrue(text.contains("Sails\tboat, sea"))
        assertFalse(text.contains("SECRETDESC"))
    }

    @Test fun sortItem_fromUncategorized_parsesActions() {
        val json = """[{"t":"0:00","text":"Pan right"},{"t":"0:04","text":"Dives"}]"""
        val v = SortItem.from(uncategorized(3, ItemKind.Video, json))
        assertEquals(listOf("0:00 Pan right", "0:04 Dives"), v.actions)
        assertEquals(emptyList<String>(), SortItem.from(uncategorized(4, ItemKind.Video, "not json")).actions)
    }

    /** I3: both backends read the cap on new categories; the prompt version moved with the text. */
    @Test fun system_statesTheNewCategoryCap() {
        assertTrue(SortPrompt.system().contains("Add at most 12 new categories in one reply."))
        assertEquals("sort-v4", SORT_PROMPT_VERSION)
    }

    /** Categories are general, one word each; the specific subject goes in the friendly name. */
    @Test fun system_asksForOneGeneralWord_andKeepsSpecificsOutOfCategories() {
        val s = SortPrompt.system()
        assertTrue(s, s.contains("A new category name is one general word"))
        assertTrue(s, s.contains("never two subjects joined"))
        assertTrue(s, s.contains("The specific subject (a turtle, a seal, a dish) belongs in the friendly name"))
        assertFalse(s, s.contains("3 words"))
    }

    @Test fun proposeUser_asksForOneGeneralWordPerGroup() {
        val text = SortPrompt.proposeUser(listOf(item(1)))
        assertTrue(text, text.contains("broad groups named with one general word each"))
    }
}
