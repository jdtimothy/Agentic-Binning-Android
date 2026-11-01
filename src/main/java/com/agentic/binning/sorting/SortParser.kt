package com.agentic.binning.sorting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class NewCategory(val name: String, val description: String)

data class RawAssignment(val id: Long, val category: String, val name: String)

data class SortReply(val newCategories: List<NewCategory>, val assignments: List<RawAssignment>)

object SortParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the outermost `{...}` of [text] (so code fences and a line of prose don't matter).
     * Returns null when no JSON object with `new_categories` or `assignments` can be read.
     * An assignment whose id is unreadable is skipped, which the validator then reports as a missing id.
     */
    fun parse(text: String): SortReply? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val root = try {
            json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if ("new_categories" !in root && "assignments" !in root) return null
        val categories = (root["new_categories"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            NewCategory(o.string("name"), o.string("description"))
        }
        val assignments = (root["assignments"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"].asLong() ?: return@mapNotNull null
            RawAssignment(id, o.string("category"), o.string("name"))
        }
        return SortReply(categories, assignments)
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonElement?.asLong(): Long? {
        val p = this as? JsonPrimitive ?: return null
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { d -> return d.toLong().takeIf { it.toDouble() == d } }
        return null
    }
}
