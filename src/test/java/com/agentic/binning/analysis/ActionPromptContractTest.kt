package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPromptContractTest {
    @Test fun mainCall_fixesEmptyActionsWithoutWhitespaceChoice() {
        for (kind in ItemKind.entries) {
            val root = AnalysisPrompt.grammar(kind).lineSequence().first()
            assertTrue(root, root.contains(""""\"actions\": [],""""))
            assertFalse(root, root.contains("ws actions"))
        }
        assertTrue(AnalysisPrompt.SYSTEM.contains("\"actions\": always []."))
        assertEquals("analysis-v3", AnalysisPrompt.PROMPT_VERSION)
    }
}
