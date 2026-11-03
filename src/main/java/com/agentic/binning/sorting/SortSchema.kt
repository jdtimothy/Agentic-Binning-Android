package com.agentic.binning.sorting

import kotlinx.serialization.json.JsonPrimitive

/**
 * The reply shape in two forms: a JSON schema for Claude's structured output, and GBNF grammars for
 * the local models. The local assignment grammar is built per chunk, so its `assignments` array is
 * exactly the chunk's ids in order (the spike found this removes every id error), and every free
 * string and the whitespace are bounded so a small model can't ramble or loop.
 */
object SortSchema {
    const val MAX_NEW_CATEGORIES = 12
    const val MAX_CATEGORY_CHARS = 40
    const val MAX_NAME_CHARS = 60
    const val MAX_DESCRIPTION_CHARS = 160

    private val str = mapOf("type" to "string")

    private val newCategoryItem: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf("name" to str, "description" to str),
        "required" to listOf("name", "description"),
        "additionalProperties" to false,
    )

    private val assignmentItem: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf("id" to mapOf("type" to "integer"), "category" to str, "name" to str),
        "required" to listOf("id", "category", "name"),
        "additionalProperties" to false,
    )

    private val newCategories: Map<String, Any> =
        mapOf("type" to "array", "items" to newCategoryItem, "maxItems" to MAX_NEW_CATEGORIES)

    fun assignJsonSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "new_categories" to newCategories,
            "assignments" to mapOf("type" to "array", "items" to assignmentItem),
        ),
        "required" to listOf("new_categories", "assignments"),
        "additionalProperties" to false,
    )

    fun proposeJsonSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf("new_categories" to newCategories),
        "required" to listOf("new_categories"),
        "additionalProperties" to false,
    )

    /**
     * Grammar for one assignment chunk: [ids] are listed in order, each as a literal, and `category` is
     * one of [existingNames] (as literals) or a new name of at most 40 chars.
     */
    fun assignGrammar(ids: List<Long>, existingNames: List<String>): String {
        val entries = ids.joinToString(""" "," ws """) { """"{" ws "\"id\":" ws "$it" tail""" }
        val assigns = if (ids.isEmpty()) """"[" ws "]$Q""" else """"[" ws $entries ws "]$Q"""
        val category = (existingNames.distinct().map { literalOf(it) } + "catname").joinToString(" | ")
        return listOf(
            """root     ::= "{" ws "\"new_categories\":" ws newcats "," ws "\"assignments\":" ws assigns ws "}$Q""",
            """newcats  ::= "[" ws "]" | "[" ws newcat ( "," ws newcat ){0,${MAX_NEW_CATEGORIES - 1}} ws "]$Q""",
            NEWCAT,
            "assigns  ::= $assigns",
            """tail     ::= "," ws "\"category\":" ws category "," ws "\"name\":" ws fname ws "}$Q""",
            "category ::= $category",
            SHARED,
        ).joinToString("\n")
    }

    /** Grammar for the proposal pass: only `new_categories`, one to twelve of them. */
    fun proposeGrammar(): String = listOf(
        """root     ::= "{" ws "\"new_categories\":" ws newcats ws "}$Q""",
        """newcats  ::= "[" ws newcat ( "," ws newcat ){0,${MAX_NEW_CATEGORIES - 1}} ws "]$Q""",
        NEWCAT,
        SHARED,
    ).joinToString("\n")

    /** A double quote, for raw strings that would otherwise end in one. */
    private const val Q = "\""

    private const val NEWCAT =
        """newcat   ::= "{" ws "\"name\":" ws catname "," ws "\"description\":" ws desc ws "}$Q"""

    /**
     * A new category name's character: one word, so no space and no joiner ("&", "+", "/", "_", "-").
     * An existing multi-word category stays reachable as a literal in `category`.
     */
    private const val WORD_CHAR = """[^"\\\x7F\x00-\x20&+/_\x2D]"""

    /** Any character a JSON string may hold unescaped. */
    private const val CHAR = """[^"\\\x7F\x00-\x1F]"""

    private const val SHARED =
        """catname  ::= "\"" $WORD_CHAR{1,$MAX_CATEGORY_CHARS} "\"$Q
fname    ::= "\"" $CHAR{1,$MAX_NAME_CHARS} "\"$Q
desc     ::= "\"" $CHAR{1,$MAX_DESCRIPTION_CHARS} "\"$Q
ws       ::= [ \t\n]{0,4}"""

    /** A GBNF literal matching [name] as a JSON string, quotes included. */
    private fun literalOf(name: String): String {
        val jsonString = JsonPrimitive(name).toString()
        return "\"" + jsonString.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
