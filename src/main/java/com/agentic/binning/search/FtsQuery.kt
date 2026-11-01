package com.agentic.binning.search

/**
 * Turns what the user types in the Project Media search field into a safe FTS5 MATCH expression
 * for `AnalysisDao.search` (the `item_fts` table, tokenizer `porter unicode61`).
 *
 * Each word becomes a quoted phrase, so FTS5 syntax the user types (`AND`, `-x`, `a:b`, `(`, `*`,
 * a stray `"`) is matched as text instead of failing the query. Words are ANDed. Only the last word
 * gets a prefix `*`, because it is the one still being typed: a prefix on every word lets porter
 * stems over-match (`"resting"*` also finds "restaurant").
 */
object FtsQuery {
    /** More words than this add nothing to a search of short descriptions; the rest are dropped. */
    const val MAX_WORDS = 12

    /** Null when there is nothing to search for (blank, or only punctuation): show every item. */
    fun from(input: String): String? {
        val words = input.trim().split(WHITESPACE)
            .filter { word -> word.any { it.isLetterOrDigit() } }
            .take(MAX_WORDS)
        if (words.isEmpty()) return null
        // A trailing space means the last word is finished, so it gets no prefix either.
        val lastIsPartial = input.isNotEmpty() && !input.last().isWhitespace()
        return words.mapIndexed { i, word ->
            val phrase = "\"" + word.replace("\"", "\"\"") + "\""
            if (i == words.lastIndex && lastIsPartial) "$phrase*" else phrase
        }.joinToString(" ")
    }

    private val WHITESPACE = Regex("\\s+")
}
