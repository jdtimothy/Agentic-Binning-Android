package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind
import com.agentic.binning.sorting.cleanName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ActionEntry(val t: String, val text: String)

data class AnalysisResult(
    val description: String,
    val actions: List<ActionEntry>,
    val tags: List<String>,
    val suggestedName: String,
    val shotType: String,
)

data class PartialAnalysis(
    val description: String,
    val descriptionDone: Boolean,
    val actions: List<ActionEntry>,
    val tags: List<String>,
)

@Serializable
private data class RawAnalysis(
    val description: String = "",
    val actions: List<ActionEntry> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("suggested_name") val suggestedName: String = "",
    @SerialName("shot_type") val shotType: String = "medium",
)

object AnalysisParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val shotTypes = setOf("wide", "medium", "close")
    private const val MAX_TAGS = 10
    private const val MAX_NAME = 60

    fun parse(text: String, kind: ItemKind): AnalysisResult? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val raw = try {
            json.decodeFromString<RawAnalysis>(text.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        val description = raw.description.trim()
        if (description.isEmpty()) return null
        val name = cleanName(raw.suggestedName, MAX_NAME)
        if (name.isEmpty()) return null
        val tags = raw.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)
        return AnalysisResult(
            description = description,
            actions = if (kind == ItemKind.Video) raw.actions else emptyList(),
            tags = tags,
            suggestedName = name,
            shotType = raw.shotType.trim().lowercase().takeIf { it in shotTypes } ?: "medium",
        )
    }

    /**
     * Best-effort view of a reply that is still streaming in. Never throws: anything it cannot
     * read yet is simply left empty. Description grows as characters arrive; actions and tags
     * appear only once each entry is complete.
     */
    fun partial(text: String): PartialAnalysis =
        try {
            scanPartial(text)
        } catch (_: Exception) {
            PartialAnalysis("", false, emptyList(), emptyList())
        }

    private class StrScan(val value: String, val end: Int, val closed: Boolean)

    /** Reads a JSON string whose opening quote is at [open]. [StrScan.end] is the index after the closing quote. */
    private fun scanString(s: String, open: Int): StrScan {
        val sb = StringBuilder()
        var i = open + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return StrScan(sb.toString(), i + 1, true)
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) break
            when (val e = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    if (i + 6 > s.length) break
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) sb.append(code.toChar())
                    i += 4
                }
                else -> sb.append(e)
            }
            i += 2
        }
        return StrScan(sb.toString(), s.length, false)
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Index just after `"key":` and any whitespace, or -1 if the key has not arrived. */
    private fun valueStart(s: String, key: String, from: Int): Int {
        val k = s.indexOf("\"$key\":", from)
        return if (k < 0) -1 else skipWs(s, k + key.length + 3)
    }

    private fun scanPartial(s: String): PartialAnalysis {
        val none = PartialAnalysis("", false, emptyList(), emptyList())
        val dStart = valueStart(s, "description", 0)
        if (dStart < 0 || dStart >= s.length || s[dStart] != '"') return none
        val desc = scanString(s, dStart)
        if (!desc.closed) return none.copy(description = desc.value)

        val aStart = valueStart(s, "actions", desc.end)
        if (aStart < 0 || aStart >= s.length || s[aStart] != '[') {
            return PartialAnalysis(desc.value, true, emptyList(), emptyList())
        }
        val actions = ArrayList<ActionEntry>()
        var i = aStart + 1
        var arrayClosed = false
        while (true) {
            i = skipWs(s, i)
            if (i >= s.length) break
            val c = s[i]
            if (c == ',') { i++; continue }
            if (c == ']') { arrayClosed = true; i++; break }
            if (c != '{') break
            val end = objectEnd(s, i)
            if (end < 0) break
            parseAction(s.substring(i, end))?.let { actions.add(it) }
            i = end
        }
        val tags = ArrayList<String>()
        if (arrayClosed) {
            val tStart = valueStart(s, "tags", i)
            if (tStart >= 0 && tStart < s.length && s[tStart] == '[') {
                var j = tStart + 1
                while (true) {
                    j = skipWs(s, j)
                    if (j >= s.length) break
                    val c = s[j]
                    if (c == ',') { j++; continue }
                    if (c != '"') break
                    val tag = scanString(s, j)
                    if (!tag.closed) break
                    val v = tag.value.trim().lowercase()
                    if (v.isNotEmpty() && v !in tags) tags.add(v)
                    j = tag.end
                }
            }
        }
        return PartialAnalysis(desc.value, true, actions, tags.take(MAX_TAGS))
    }

    /** Index after the `}` closing the object that starts at [open], or -1 if it is incomplete. */
    private fun objectEnd(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '"') {
                val str = scanString(s, i)
                if (!str.closed) return -1
                i = str.end
                continue
            }
            if (c == '{') depth++
            if (c == '}' && --depth == 0) return i + 1
            i++
        }
        return -1
    }

    private fun parseAction(objText: String): ActionEntry? = try {
        val obj = json.parseToJsonElement(objText) as? JsonObject
        val t = (obj?.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val text = (obj?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (t != null && text != null) ActionEntry(t, text) else null
    } catch (_: Exception) {
        null
    }
}
