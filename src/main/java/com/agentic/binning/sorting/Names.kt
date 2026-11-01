package com.agentic.binning.sorting

private val illegalNameChars = Regex("""[/\:*?"<>|]""")
private val whitespaceRun = Regex("""\s+""")

/**
 * Makes a model-written name safe as a file or folder name: illegal characters become spaces,
 * runs of whitespace collapse, and the result is cut at [max] chars with no trailing dot or space.
 * Shared by suggested names (60) and category names (40).
 */
fun cleanName(name: String, max: Int): String {
    val cleaned = name
        .replace(illegalNameChars, " ")
        .replace(whitespaceRun, " ")
        .trim()
        .trimEnd { it == '.' || it.isWhitespace() }
    return cleaned.take(max).trimEnd { it == '.' || it.isWhitespace() }
}
