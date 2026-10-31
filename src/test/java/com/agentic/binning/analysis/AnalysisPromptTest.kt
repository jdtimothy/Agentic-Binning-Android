package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BS = '\\'

class AnalysisPromptTest {
    private val video = AnalysisPrompt.grammar(ItemKind.Video)
    private val photo = AnalysisPrompt.grammar(ItemKind.Photo)

    private fun rule(grammar: String, name: String): String? =
        grammar.lines().firstOrNull { it.startsWith("$name ") }?.substringAfter("::= ")

    @Test fun grammar_and_prompt_mentionSameKeys() {
        val keys = Regex("\"(\\w+)\":").findAll(AnalysisPrompt.SYSTEM).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("description", "actions", "t", "text", "tags", "suggested_name", "shot_type"), keys)
        for (k in keys) {
            assertTrue("video grammar lacks $k", video.contains("\\\"$k\\\":"))
        }
        // A photo's actions are always [], so the action object's keys never appear.
        val actionKeys = setOf("t", "text")
        for (k in keys - actionKeys) {
            assertTrue("photo grammar lacks $k", photo.contains("\\\"$k\\\":"))
        }
        for (k in actionKeys) {
            assertFalse("photo grammar has $k", photo.contains("\\\"$k\\\":"))
        }
    }

    @Test fun grammar_perKindActions() {
        assertEquals(""""[" ws action ( "," ws action ){1,4} ws "]"""", rule(video, "actions"))
        assertEquals(""""{" ws "~"t~":" ws ts "," ws "~"text~":" ws str ws "}"""".replace('~', BS), rule(video, "action"))
        assertEquals(""""~"" [0-9] [0-9]? ":" [0-5] [0-9] "~""""".replace('~', BS), rule(video, "ts"))
        assertEquals(""""[" ws "]"""", rule(photo, "actions"))
        assertNull(rule(photo, "action"))
        assertNull(rule(photo, "ts"))
    }

    @Test fun grammar_tagsThreeToTen_bothKinds() {
        for (g in listOf(video, photo)) {
            assertEquals(""""[" ws str ( "," ws str ){2,9} ws "]"""", rule(g, "strs"))
        }
    }

    @Test fun grammar_keepsSharedRulesVerbatim() {
        // '~' stands in for a literal backslash so the expectation is readable.
        val strRule = """str     ::= "~"" ( [^"~~~x7F~x00-~x1F] | "~~" ["~~/bfnrt] )* "~""""".replace('~', BS)
        val wsRule = "ws      ::= [ ~t~n]*".replace('~', BS)
        val shotRule = """shot    ::= "~"wide~"" | "~"medium~"" | "~"close~""""".replace('~', BS)
        val rootRule = """root    ::= "{" ws "~"description~":" ws str "," ws "~"actions~":" ws actions "," ws "~"tags~":" ws strs "," ws "~"suggested_name~":" ws str "," ws "~"shot_type~":" ws shot ws "}"""".replace('~', BS)
        for (g in listOf(video, photo)) {
            assertTrue(g.lines().contains(strRule))
            assertTrue(g.lines().contains(wsRule))
            assertTrue(g.lines().contains(shotRule))
            assertTrue(g.lines().contains(rootRule))
        }
        assertEquals(8, video.lines().size)
        assertEquals(6, photo.lines().size)
    }

    @Test fun instructions_perKind() {
        assertEquals("Describe this photo.", AnalysisPrompt.instruction(ItemKind.Photo))
        assertEquals("Describe this video clip and how it changes over time.", AnalysisPrompt.instruction(ItemKind.Video))
    }

    @Test fun tokenBudgets_andVersion() {
        assertEquals("analysis-v2", AnalysisPrompt.PROMPT_VERSION)
        assertEquals(400, AnalysisPrompt.MAX_TOKENS_PHOTO)
        assertEquals(520, AnalysisPrompt.MAX_TOKENS_VIDEO)
    }
}
