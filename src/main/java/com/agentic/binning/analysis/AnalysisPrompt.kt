package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind

/**
 * Fixed prompt copy and output grammar for the on-device analysis call.
 * Changing any text here requires bumping [PROMPT_VERSION].
 */
object AnalysisPrompt {
    const val PROMPT_VERSION = "analysis-v2"
    const val MAX_TOKENS_PHOTO = 400
    const val MAX_TOKENS_VIDEO = 520

    val SYSTEM: String = """
        You catalogue a personal photo and video library. Look carefully and report only what is visible.
        Reply with one JSON object and nothing else, with these keys:
        "description": 1-3 plain sentences: the subject, the setting, and anything notable.
        "actions": for a video, 2-5 entries {"t": "m:ss", "text": "what happens"} in time order, using the timestamps shown; for a photo, [].
        "tags": 5-10 short lowercase keywords for searching (subjects, setting, activity, time of day).
        "suggested_name": 2-5 words naming the shot, like "Sea lions on the rocks". No file extension, no date.
        "shot_type": "wide", "medium" or "close".
    """.trimIndent()

    fun instruction(kind: ItemKind): String = when (kind) {
        ItemKind.Photo -> "Describe this photo."
        ItemKind.Video -> "Describe this video clip and how it changes over time."
    }

    /**
     * GBNF grammar constraining the reply to the JSON shape above: a video has 2-5 actions with
     * `m:ss` times, a photo exactly `[]`; both have 3-10 tags. Uses llama.cpp's `{m,n}` repetition.
     */
    fun grammar(kind: ItemKind): String = when (kind) {
        ItemKind.Photo -> GRAMMAR_PHOTO
        ItemKind.Video -> GRAMMAR_VIDEO
    }

    private val ROOT = """
        root    ::= "{" ws "\"description\":" ws str "," ws "\"actions\":" ws actions "," ws "\"tags\":" ws strs "," ws "\"suggested_name\":" ws str "," ws "\"shot_type\":" ws shot ws "}"
    """.trimIndent()

    private val SHARED = """
        strs    ::= "[" ws str ( "," ws str ){2,9} ws "]"
        shot    ::= "\"wide\"" | "\"medium\"" | "\"close\""
        str     ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] )* "\""
        ws      ::= [ \t\n]*
    """.trimIndent()

    private val VIDEO_ACTIONS = """
        actions ::= "[" ws action ( "," ws action ){1,4} ws "]"
        action  ::= "{" ws "\"t\":" ws ts "," ws "\"text\":" ws str ws "}"
        ts      ::= "\"" [0-9] [0-9]? ":" [0-5] [0-9] "\""
    """.trimIndent()

    private val PHOTO_ACTIONS = """
        actions ::= "[" ws "]"
    """.trimIndent()

    val GRAMMAR_VIDEO: String = listOf(ROOT, VIDEO_ACTIONS, SHARED).joinToString("\n")
    val GRAMMAR_PHOTO: String = listOf(ROOT, PHOTO_ACTIONS, SHARED).joinToString("\n")
}
