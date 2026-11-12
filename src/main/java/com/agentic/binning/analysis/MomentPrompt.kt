package com.agentic.binning.analysis

/** The short, independent single-frame call used after the full-clip description. */
object MomentPrompt {
    const val MAX_TOKENS = 40
    const val SYSTEM = "You describe one moment of a video in one short phrase of at most 14 words. Report only what is visible: name the subject, then what it does. Reply with the phrase and nothing else."
    const val TEXT = "This image is one frame of a video clip.\nIn one short phrase, say what is happening in it."
    // A full stop ends generation; newline-only termination ran to the cap on real clips.
    val GRAMMAR = """root ::= [A-Z] [^\n\r"{}.<|]{3,160} ".""""

    fun clean(text: String): String? {
        val line = text.trim().lineSequence().firstOrNull().orEmpty()
            .substringBefore('<').substringBefore('|').substringBefore('.')
            .trim().trim('"', '\'').trim()
        val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.joinToString(" ").takeIf { words.size in 2..14 && it.any(Char::isLetter) }
    }
}
