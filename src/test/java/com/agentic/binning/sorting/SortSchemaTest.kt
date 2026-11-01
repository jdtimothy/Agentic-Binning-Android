package com.agentic.binning.sorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class SortSchemaTest {
    private fun obj(m: Map<String, Any>, key: String) = m[key] as Map<String, Any>

    private fun rule(g: String, name: String) = g.lines().firstOrNull { it.startsWith("$name ") }?.substringAfter("::= ")

    @Test fun assignSchema_requiresAllFields() {
        val s = SortSchema.assignJsonSchema()
        assertEquals(false, s["additionalProperties"])
        assertEquals(listOf("new_categories", "assignments"), s["required"])
        val props = obj(s, "properties")
        val nc = obj(obj(props, "new_categories"), "items")
        assertEquals(false, nc["additionalProperties"])
        assertEquals(listOf("name", "description"), nc["required"])
        val a = obj(obj(props, "assignments"), "items")
        assertEquals(false, a["additionalProperties"])
        assertEquals(listOf("id", "category", "name"), a["required"])
        assertEquals("integer", obj(obj(a, "properties"), "id")["type"])
    }

    @Test fun bothSchemas_capNewCategoriesAtTwelve() {
        for (s in listOf(SortSchema.assignJsonSchema(), SortSchema.proposeJsonSchema())) {
            assertEquals(12, obj(obj(s, "properties"), "new_categories")["maxItems"])
        }
    }

    @Test fun proposeSchema_onlyNewCategories() {
        val s = SortSchema.proposeJsonSchema()
        assertEquals(listOf("new_categories"), s["required"])
        assertEquals(setOf("new_categories"), obj(s, "properties").keys)
        assertEquals(false, s["additionalProperties"])
    }

    @Test fun assignGrammar_parsesSampleReply_ruleNamesAndShape() {
        val g = SortSchema.assignGrammar(listOf(41L, 7L, 1234L), listOf("Food"))
        for (name in listOf("root", "newcats", "newcat", "assigns", "tail", "category", "catname", "fname", "desc", "ws")) {
            assertTrue("rule $name missing in\n$g", rule(g, name) != null)
        }
        val at = listOf(41, 7, 1234).map { g.indexOf("\"\\\"id\\\":\" ws \"$it\" tail") }
        assertTrue("every id is a literal: $at", at.all { it >= 0 })
        assertEquals("ids in chunk order", at, at.sorted())
        assertFalse(g.contains("\"\\\"id\\\":\" ws \"8\""))
        assertEquals("\"\\\"Food\\\"\" | catname", rule(g, "category"))
    }

    @Test fun assignGrammar_boundsEverything() {
        val g = SortSchema.assignGrammar(listOf(1L), emptyList())
        assertEquals("[ \\t\\n]{0,4}", rule(g, "ws"))
        assertTrue(rule(g, "catname")!!.contains("{1,40}"))
        assertTrue(rule(g, "fname")!!.contains("{1,60}"))
        assertTrue(rule(g, "desc")!!.contains("{1,160}"))
        assertTrue(rule(g, "newcats")!!.contains("{0,11}"))
        assertEquals("catname", rule(g, "category"))
        assertFalse("no unbounded repetition", g.contains(")*") || g.contains("]*") || g.contains("]+"))
    }

    @Test fun assignGrammar_escapesExistingNames() {
        val g = SortSchema.assignGrammar(listOf(1L), listOf("Say \"hi\" \\ there"))
        assertTrue(rule(g, "category")!!.contains("""Say \\\"hi\\\" \\\\ there"""))
    }

    @Test fun assignGrammar_emptyChunkIsEmptyArray() {
        val g = SortSchema.assignGrammar(emptyList(), emptyList())
        assertEquals("\"[\" ws \"]\"", rule(g, "assigns"))
    }

    @Test fun proposeGrammar_hasNoAssignments() {
        val g = SortSchema.proposeGrammar()
        assertFalse(g.contains("assignments"))
        assertTrue(rule(g, "root")!!.contains("new_categories"))
        assertTrue(rule(g, "newcats")!!.contains("{0,11}"))
        assertEquals("[ \\t\\n]{0,4}", rule(g, "ws"))
    }
}
