package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BS = '\\'

class AnalysisPromptTest {
    @Test fun grammar_and_prompt_mentionSameKeys() {
        val keys = Regex("\"(\\w+)\":").findAll(AnalysisPrompt.SYSTEM).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("description", "actions", "t", "text", "tags", "suggested_name", "shot_type"), keys)
        for (k in keys) {
            assertTrue("grammar lacks $k", AnalysisPrompt.GRAMMAR.contains("\\\"$k\\\":"))
        }
    }

    @Test fun grammar_keepsEscapesVerbatim() {
        // '~' stands in for a literal backslash so the expectation is readable.
        val strRule = """str     ::= "~"" ( [^"~~~x7F~x00-~x1F] | "~~" ["~~/bfnrt] )* "~""""".replace('~', BS)
        assertTrue(AnalysisPrompt.GRAMMAR.contains(strRule))
        assertTrue(AnalysisPrompt.GRAMMAR.contains("ws      ::= [ ~t~n]*".replace('~', BS)))
        assertEquals(7, AnalysisPrompt.GRAMMAR.lines().size)
    }

    @Test fun instructions_perKind() {
        assertEquals("Describe this photo.", AnalysisPrompt.instruction(ItemKind.Photo))
        assertEquals("Describe this video clip and how it changes over time.", AnalysisPrompt.instruction(ItemKind.Video))
    }

    @Test fun tokenBudgets_andVersion() {
        assertEquals("analysis-v1", AnalysisPrompt.PROMPT_VERSION)
        assertEquals(400, AnalysisPrompt.MAX_TOKENS_PHOTO)
        assertEquals(520, AnalysisPrompt.MAX_TOKENS_VIDEO)
    }
}
