package com.agentic.binning.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {
    @Test fun blankOrPunctuationOnly_isNull() {
        assertNull(FtsQuery.from(""))
        assertNull(FtsQuery.from("   "))
        assertNull(FtsQuery.from(" - * ( \" "))
    }

    @Test fun lastWordIsAPrefix_earlierWordsAreExact() {
        assertEquals("\"turt\"*", FtsQuery.from("turt"))
        assertEquals("\"sea\" \"turt\"*", FtsQuery.from("sea turt"))
    }

    @Test fun trailingSpace_endsThePrefix() {
        assertEquals("\"sea\" \"turtle\"", FtsQuery.from("sea turtle "))
    }

    @Test fun ftsSyntax_isQuotedAsText() {
        assertEquals("\"AND\"*", FtsQuery.from("AND"))
        assertEquals("\"sea\" \"OR\"*", FtsQuery.from("sea OR"))
        assertEquals("\"-x\"*", FtsQuery.from("-x"))
        assertEquals("\"a:b\"*", FtsQuery.from("a:b"))
        assertEquals("\"(x\"*", FtsQuery.from("(x"))
        assertEquals("\"NEAR(\"*", FtsQuery.from("NEAR("))
    }

    @Test fun doubleQuotes_areEscaped() {
        assertEquals("\"say\" \"\"\"hi\"\"\"*", FtsQuery.from("say \"hi\""))
        assertEquals("\"turtle\"\"\"*", FtsQuery.from("turtle\""))
    }

    @Test fun punctuationOnlyWords_areDropped() {
        assertEquals("\"sea\" \"turtle\"*", FtsQuery.from("sea - turtle"))
    }

    @Test fun apostrophesAndAccents_stayInsideTheWord() {
        assertEquals("\"mama's\"*", FtsQuery.from("mama's"))
        assertEquals("\"café\" \"hoʻokipa\"*", FtsQuery.from("café hoʻokipa"))
    }

    @Test fun wordCount_isCapped() {
        val q = FtsQuery.from((1..30).joinToString(" ") { "w$it" })!!
        assertEquals(FtsQuery.MAX_WORDS, q.split(" ").size)
    }
}
