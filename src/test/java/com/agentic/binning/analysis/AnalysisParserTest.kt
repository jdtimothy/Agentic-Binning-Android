package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisParserTest {
    private val fullVideo =
        """{"description":"Sea lions on rocks, \"basking\" in the sun.","actions":[{"t":"0:00","text":"Pan right"},{"t":"0:04","text":"One slides into the water"}],"tags":["sea lions","rocks","sunny"],"suggested_name":"Sea lions on the rocks","shot_type":"wide"}"""

    private fun reply(
        description: String = "A pier at night.",
        actions: String = "[]",
        tags: String = """["pier"]""",
        name: String = "Pier at night",
        shot: String = "wide",
    ) = """{"description":"$description","actions":$actions,"tags":$tags,"suggested_name":"$name","shot_type":"$shot"}"""

    @Test fun parse_video() {
        val r = AnalysisParser.parse(
            """{"description":"Sea lions on rocks.","actions":[{"t":"0:00","text":"Pan right"}],"tags":["Sea Lions","rocks","sea lions"],"suggested_name":"Sea lions on the rocks","shot_type":"wide"}""",
            ItemKind.Video,
        )!!
        assertEquals(listOf("sea lions", "rocks"), r.tags)
        assertEquals("0:00", r.actions.single().t)
        assertEquals("wide", r.shotType)
    }

    @Test fun parse_photo_dropsActions() {
        val r = AnalysisParser.parse(
            reply(actions = """[{"t":"0:00","text":"Pan right"}]"""),
            ItemKind.Photo,
        )!!
        assertTrue(r.actions.isEmpty())
    }

    @Test fun parse_cleansSuggestedName() {
        val r = AnalysisParser.parse(reply(name = "Pier: night/moon..."), ItemKind.Photo)!!
        assertEquals("Pier night moon", r.suggestedName)
    }

    @Test fun parse_capsNameAndTags() {
        val tags = (1..14).joinToString(",", "[", "]") { "\"t$it\"" }
        val r = AnalysisParser.parse(reply(name = "word ".repeat(30).trim(), tags = tags), ItemKind.Photo)!!
        assertTrue(r.suggestedName.length <= 60)
        assertFalse(r.suggestedName.endsWith(" "))
        assertEquals(10, r.tags.size)
    }

    @Test fun parse_rejectsBlankDescription() {
        assertNull(AnalysisParser.parse(reply(description = "  "), ItemKind.Photo))
    }

    @Test fun parse_rejectsBlankNameAfterCleaning() {
        assertNull(AnalysisParser.parse(reply(name = "?*:."), ItemKind.Photo))
    }

    @Test fun parse_rejectsTruncatedJson() {
        assertNull(AnalysisParser.parse(fullVideo.substring(0, 60), ItemKind.Video))
    }

    @Test fun parse_rejectsNonJson() {
        assertNull(AnalysisParser.parse("I cannot help with that.", ItemKind.Photo))
    }

    @Test fun parse_ignoresTextBeforeFirstBrace() {
        assertNotNull(AnalysisParser.parse("Here you go:\n" + reply(), ItemKind.Photo))
    }

    @Test fun parse_unknownShotType_becomesMedium() {
        val r = AnalysisParser.parse(reply(shot = "panoramic"), ItemKind.Photo)!!
        assertEquals("medium", r.shotType)
    }

    @Test fun partial_streamsDescription() {
        val p = AnalysisParser.partial("""{"description":"Golden-hour view acr""")
        assertEquals("Golden-hour view acr", p.description)
        assertFalse(p.descriptionDone)
    }

    @Test fun partial_emptyAndGarbage() {
        val p = AnalysisParser.partial("")
        assertEquals("", p.description)
        assertFalse(p.descriptionDone)
        assertEquals("", AnalysisParser.partial("{\"desc").description)
    }

    @Test fun partial_afterDescription_collectsCompletedActionsAndTags() {
        val p = AnalysisParser.partial(
            """{"description":"Sea lions.","actions":[{"t":"0:00","text":"Pan right"},{"t":"0:04","text":"One sl""",
        )
        assertEquals("Sea lions.", p.description)
        assertTrue(p.descriptionDone)
        assertEquals(listOf(ActionEntry("0:00", "Pan right")), p.actions)
        assertTrue(p.tags.isEmpty())

        val q = AnalysisParser.partial(fullVideo.substringBefore("\"suggested_name\"") + "")
        assertEquals(2, q.actions.size)
        assertEquals(listOf("sea lions", "rocks", "sunny"), q.tags)
    }

    @Test fun partial_midTags_returnsOnlyCompleteTags() {
        val p = AnalysisParser.partial(fullVideo.substringBefore("\"rocks\"") + "\"ro")
        assertEquals(listOf("sea lions"), p.tags)
    }

    @Test fun partial_decodesEscapes() {
        val p = AnalysisParser.partial(fullVideo)
        assertEquals("Sea lions on rocks, \"basking\" in the sun.", p.description)
        assertEquals("Sea lions on rocks, \"basking\" in the sun.".length, p.description.length)
        // Dangling escape at the cut must not throw or leak the backslash.
        assertEquals("a", AnalysisParser.partial("""{"description":"a\""").description)
        assertEquals("a", AnalysisParser.partial("""{"description":"a\u00""").description)
    }

    @Test fun partial_neverThrows_andDescriptionNeverShrinks() {
        var last = 0
        for (i in 0..fullVideo.length) {
            val p = AnalysisParser.partial(fullVideo.substring(0, i))
            assertTrue("shrank at $i", p.description.length >= last)
            last = p.description.length
        }
        val done = AnalysisParser.partial(fullVideo)
        assertTrue(done.descriptionDone)
        assertEquals(2, done.actions.size)
        assertEquals(3, done.tags.size)
    }
}
