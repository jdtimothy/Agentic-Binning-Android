package com.agentic.binning.sorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SortParserTest {
    @Test fun parse_readsValidReply() {
        val r = SortParser.parse(
            """{"new_categories":[{"name":"Food","description":"Meals"}],"assignments":[{"id":1,"category":"Food","name":"Eggs"},{"id":2,"category":"Food","name":"Toast"}]}""",
        )!!
        assertEquals(listOf(NewCategory("Food", "Meals")), r.newCategories)
        assertEquals(listOf(RawAssignment(1, "Food", "Eggs"), RawAssignment(2, "Food", "Toast")), r.assignments)
    }

    @Test fun parse_returnsNullOnGarbage() {
        assertNull(SortParser.parse("I cannot do that."))
        assertNull(SortParser.parse("{\"assignments\": [ {\"id\":1"))
        assertNull(SortParser.parse("{}"))
        assertNull(SortParser.parse(""))
    }

    @Test fun parse_proposalReplyHasNoAssignments() {
        val r = SortParser.parse("""{"new_categories":[{"name":"Food","description":"Meals"}]}""")!!
        assertEquals(1, r.newCategories.size)
        assertEquals(emptyList<RawAssignment>(), r.assignments)
    }

    @Test fun parse_toleratesFencesAndProse() {
        val r = SortParser.parse("Here:\n```json\n{\"new_categories\":[],\"assignments\":[{\"id\":5,\"category\":\"A\",\"name\":\"B\"}]}\n```")
        assertNotNull(r)
        assertEquals(5L, r!!.assignments.single().id)
    }

    @Test fun parse_skipsEntriesWithoutReadableId() {
        val r = SortParser.parse("""{"new_categories":[],"assignments":[{"id":"x","category":"A","name":"B"},{"id":2,"category":"A","name":"B"}]}""")!!
        assertEquals(listOf(2L), r.assignments.map { it.id })
    }

    @Test fun parse_acceptsIdsWrittenAsStringsOrDecimals() {
        val r = SortParser.parse("""{"new_categories":[],"assignments":[{"id":"3","category":"A","name":"B"},{"id":4.0,"category":"A","name":"B"}]}""")!!
        assertEquals(listOf(3L, 4L), r.assignments.map { it.id })
    }
}
