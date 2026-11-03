package com.agentic.binning.sorting

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.db.CategoryInfo
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.UncategorizedItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Changing any prompt text or reply shape requires bumping this. */
const val SORT_PROMPT_VERSION = "sort-v4"

/** One analyzed item as the sorting model sees it: text only. */
data class SortItem(
    val id: Long,
    val kind: ItemKind,
    val suggestedName: String,
    val tags: List<String>,
    val description: String,
    /** Video actions as `m:ss text`; empty for photos. */
    val actions: List<String>,
    /** The user chose this item's name, so a model rename is never applied. */
    val nameSetByUser: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(item: UncategorizedItem): SortItem = SortItem(
            id = item.id,
            kind = item.kind,
            suggestedName = item.suggestedName,
            tags = item.tags,
            description = item.description,
            actions = try {
                json.decodeFromString(ListSerializer(ActionEntry.serializer()), item.actionsJson).map { "${it.t} ${it.text}" }
            } catch (_: Exception) {
                emptyList()
            },
            nameSetByUser = item.nameSetByUser,
        )
    }
}

/**
 * Fixed prompt copy for the sorting call, shared by Claude and the local models. The reply shape is
 * enforced by [SortSchema] (a JSON schema for Claude, a GBNF grammar locally).
 */
object SortPrompt {
    const val MAX_DESCRIPTION_CHARS = 240
    const val MAX_ACTIONS_CHARS = 160

    fun system(): String = """
        You sort a personal photo and video library into categories, using only the text given for each item.
        Rules:
        - Put items into the existing categories. Add a new category only when nothing existing fits well.
        - Add at most 12 new categories in one reply.
        - Never rename or merge existing categories. A "locked" category was set by the user: it can receive items, nothing else.
        - Avoid one-item categories unless an item truly fits nowhere else.
        - No catch-all category such as "Misc" or "Other", unless a handful of items fit nowhere.
        - A new category name is one general word that makes a good folder name, such as Animals, People, Food or Sunsets: never two subjects joined, and at most 40 characters. The specific subject (a turtle, a seal, a dish) belongs in the friendly name, not the category.
        - A category describes what is in the media (its subject, place or event), never the file type.
        - Each new category gets a one-line description.
        - Give every item a friendly name of 2-6 words that describes it. No dates unless they matter, and no file extension.
        Reply with one JSON object and nothing else:
        {"new_categories":[{"name":"...","description":"..."}],"assignments":[{"id":<item id>,"category":"<category name>","name":"<friendly name>"}]}
        Assign every listed item exactly once, using the ids shown. "category" is the exact name of an existing category or of one you list in "new_categories".
        When asked only to propose categories, reply with just {"new_categories":[...]}.
    """.trimIndent()

    /** One item as a tab-separated line: id, kind, suggested name, tags, description, and for videos the actions. */
    fun itemLine(item: SortItem): String {
        val cols = mutableListOf(
            item.id.toString(),
            if (item.kind == ItemKind.Video) "video" else "photo",
            flatten(item.suggestedName),
            item.tags.joinToString(", ") { flatten(it) },
            cutOnWord(flatten(item.description), MAX_DESCRIPTION_CHARS),
        )
        if (item.kind == ItemKind.Video) {
            cols += cutOnWord(item.actions.joinToString("; ") { flatten(it) }, MAX_ACTIONS_CHARS)
        }
        return cols.joinToString("\t")
    }

    /** The proposal pass sends the suggested name and tags only. */
    fun proposeLine(item: SortItem): String =
        flatten(item.suggestedName) + "\t" + item.tags.joinToString(", ") { flatten(it) }

    /** Name, item count, `locked` (the user's) or `open`, description. */
    fun categoryLine(c: CategoryInfo): String =
        listOf(flatten(c.name), c.items.toString(), if (c.createdBy == "model") "open" else "locked", flatten(c.description))
            .joinToString("\t")

    fun proposeUser(items: List<SortItem>): String = buildString {
        appendLine("Propose categories for this library. Reply with only new_categories: broad groups named with one general word each, at most 12, each with a one-line description.")
        appendLine()
        appendLine("Items (suggested name, tags):")
        items.forEach { appendLine(proposeLine(it)) }
    }.trimEnd()

    /** [maxNewCategories] below the system prompt's 12 is stated in this prompt. */
    fun assignUser(
        items: List<SortItem>,
        categories: List<CategoryInfo>,
        feedback: List<String>,
        maxNewCategories: Int = SortSchema.MAX_NEW_CATEGORIES,
    ): String = buildString {
        appendLine("Existing categories (name, items, locked or open, description):")
        if (categories.isEmpty()) appendLine("(none yet)") else categories.forEach { appendLine(categoryLine(it)) }
        appendLine()
        appendLine("Items to sort (id, kind, suggested name, tags, description, actions for videos):")
        items.forEach { appendLine(itemLine(it)) }
        if (feedback.isNotEmpty()) {
            appendLine()
            appendLine("Your previous reply was rejected. Fix these problems:")
            feedback.forEach { appendLine("- $it") }
        }
        appendLine()
        if (maxNewCategories < SortSchema.MAX_NEW_CATEGORIES) appendLine("Add at most $maxNewCategories new categories in this reply.")
        append("Reply with the JSON object only.")
    }

    private val whitespaceRun = Regex("""\s+""")

    /** Tabs and newlines would break the line format, so every field is flattened to single spaces. */
    private fun flatten(s: String) = s.replace(whitespaceRun, " ").trim()

    private fun cutOnWord(s: String, max: Int): String {
        if (s.length <= max) return s
        if (s[max] == ' ') return s.take(max)
        val space = s.lastIndexOf(' ', max)
        return if (space > 0) s.take(space) else s.take(max)
    }
}
