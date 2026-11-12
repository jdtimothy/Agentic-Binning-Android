package com.agentic.binning.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomentPromptTest {
    @Test fun phrases_stopAtFirstSentenceAndRemoveModelMarkers() {
        assertEquals("A wave breaks", MomentPrompt.clean("  A wave breaks. More words.\nNext line"))
        assertEquals("A wave breaks", MomentPrompt.clean("A wave breaks.</think>"))
        assertNull(MomentPrompt.clean("</think>"))
        assertNull(MomentPrompt.clean("Wave."))
        assertNull(MomentPrompt.clean((1..15).joinToString(" ") { "word" } + "."))
        assertEquals(40, MomentPrompt.MAX_TOKENS)
    }
}
