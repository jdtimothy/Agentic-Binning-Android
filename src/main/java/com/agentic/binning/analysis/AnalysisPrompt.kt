package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind

/**
 * Fixed prompt copy and output grammar for the on-device analysis call.
 * Changing any text here requires bumping [PROMPT_VERSION].
 */
object AnalysisPrompt {
    // v3: app-selected moment times replace the model's unreliable video timestamps.
    const val PROMPT_VERSION = "analysis-v3"
    const val MAX_TOKENS_PHOTO = 400
    const val MAX_TOKENS_VIDEO = 520

    val SYSTEM: String = """
        You catalogue a personal photo and video library. Look carefully and report only what is visible.
        Reply with one JSON object and nothing else, with these keys:
        "description": 1-3 plain sentences: the subject, the setting, and anything notable.
        "actions": always [].
        "tags": 5-10 short lowercase keywords for searching (subjects, setting, activity, time of day).
        "suggested_name": 2-5 words naming the shot, like "Sea lions on the rocks". No file extension, no date.
        "shot_type": "wide", "medium" or "close".
    """.trimIndent()

    fun instruction(kind: ItemKind): String = when (kind) {
        ItemKind.Photo -> "Describe this photo."
        ItemKind.Video -> "Describe this video clip and how it changes over time."
    }

    /**
     * Actions are fixed empty text for both kinds; the app adds video moments afterwards.
     * No free whitespace before []: that choice caused token-limit whitespace loops on Windows.
     */
    fun grammar(kind: ItemKind): String = when (kind) {
        ItemKind.Photo -> GRAMMAR_PHOTO
        ItemKind.Video -> GRAMMAR_VIDEO
    }

    private val ROOT = """
        root    ::= "{" ws "\"description\":" ws str "," ws "\"actions\": []," ws "\"tags\":" ws strs "," ws "\"suggested_name\":" ws str "," ws "\"shot_type\":" ws shot ws "}"
    """.trimIndent()

    private val SHARED = """
        strs    ::= "[" ws str ( "," ws str ){2,9} ws "]"
        shot    ::= "\"wide\"" | "\"medium\"" | "\"close\""
        str     ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] )* "\""
        ws      ::= [ \t\n]*
    """.trimIndent()

    val GRAMMAR_VIDEO: String = listOf(ROOT, SHARED).joinToString("\n")
    val GRAMMAR_PHOTO: String = GRAMMAR_VIDEO
}
