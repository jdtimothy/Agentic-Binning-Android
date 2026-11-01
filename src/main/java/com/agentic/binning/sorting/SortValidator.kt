package com.agentic.binning.sorting

import com.agentic.binning.db.Assignment
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.CategoryInfo
import java.text.Normalizer
import java.util.Locale

sealed interface Validation {
    /**
     * Apply [newCategories] and [assignments]. [warnings] are rules that were only logged (the one-item
     * rule and unusable `new_categories` entries on a retry). [leftOut] maps the ids of items that
     * were not assigned to the problem with each; it is only filled on a retry (`isRetry = true`):
     * those items stay uncategorized while the rest of the chunk is applied.
     */
    data class Ok(
        val newCategories: List<CategoryEntity>,
        val assignments: List<Assignment>,
        val warnings: List<String> = emptyList(),
        val leftOut: Map<Long, String> = emptyMap(),
    ) : Validation

    /** Nothing may be applied; [problems] go back to the model for the retry. */
    data class Rejected(val problems: List<String>) : Validation
}

/**
 * Checks a parsed [SortReply] against the chunk it answers before anything is written. Claude has no
 * grammar and the local grammar can't know the project, so these rules stay even though the local
 * grammar already fixes the ids.
 *
 * An assignment to a category that is neither existing nor listed in `new_categories` adopts it as a
 * new category (LFM3-1.7B does this on most chunks), under the same cleaning, merging and limits.
 *
 * Every problem rejects the first attempt, so the retry hears about all of them. The retry is never
 * rejected: what validates is applied and only the items with a problem are left out
 * ([Validation.Ok.leftOut]), so every item of the chunk ends either assigned or left out. On the retry
 * a missing or duplicated id leaves that item out, an id not in the chunk is ignored (a warning), an
 * unusable category or an overfull catch-all leaves its items out, and over [validate]'s
 * `maxNewCategories` only the new categories with the most items are kept (the others' items are
 * left out). A friendly name that reads like a sentence is replaced by the suggested name without a
 * retry.
 */
object SortValidator {
    const val MAX_CATEGORY_NAME = 40
    const val MAX_FRIENDLY_NAME = 60
    const val MAX_CATEGORY_DESCRIPTION = 160

    /** A catch-all bin may hold at most max([CATCH_ALL_MIN], 1/[CATCH_ALL_DIVISOR] of the project). */
    const val CATCH_ALL_MIN = 5
    const val CATCH_ALL_DIVISOR = 20

    private const val SINGLETON_MIN = 2
    private const val SINGLETON_DIVISOR = 5

    private val diacritics = Regex("""\p{Mn}+""")
    private val notLetterOrDigit = Regex("""[^\p{L}\p{N}]+""")

    /** Catch-all names, plus media kinds: a category says what is in the media, not its file type. */
    private val catchAllWords = listOf(
        "misc", "miscellaneous", "other", "general", "uncategorized", "various", "random", "unsorted",
        "photo", "video", "image", "picture", "clip", "footage", "media", "file",
    )
    private val catchAll: Set<String> by lazy { catchAllWords.map { normalize(it) }.toSet() }

    private val articles = setOf("the", "a", "an")

    /**
     * Lowercase, no diacritics or punctuation, the articles "the", "a" and "an" dropped (unless
     * nothing else is left; a last "a" is kept, as in "Vitamin A"), single spaces, and the last word
     * made singular.
     */
    fun normalize(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase(Locale.ROOT)
            .replace(notLetterOrDigit, " ")
            .trim()
        if (plain.isEmpty()) return ""
        val words = plain.split(' ')
        val kept = words.filterIndexed { i, w -> w !in articles || (w == "a" && i == words.lastIndex) }.ifEmpty { words }
        return (kept.dropLast(1) + singular(kept.last())).joinToString(" ")
    }

    /** Words a new category name never ends on: the model stopped mid-phrase. */
    private val danglingWords = setOf("but", "and", "or", "with", "of", "the", "is")
    private val reasoning = Regex("""\blet['’]s\b|\blets see\b""")
    private const val MAX_CATEGORY_WORDS = 5

    /** The sort prompt's own wording, which LFM sometimes hands back as a category name. */
    private val promptPhrases: Set<String> by lazy {
        listOf("items to sort", "new categories", "existing categories", "category", "items", "suggested name", "friendly name")
            .map { normalize(it) }.toSet()
    }

    /**
     * Why [raw] is not usable as a new category name, or null when it is. LFM sometimes leaks its
     * reasoning ("Nature no... let's see existing Nature"), stops mid-phrase ("Nature forest is natural
     * elements, but") or repeats the prompt ("Items to sort").
     */
    fun categoryNameProblem(raw: String): String? {
        val text = raw.replace(Regex("""\s+"""), " ").trim()
        val lower = text.lowercase(Locale.ROOT)
        val words = text.split(' ').filter { w -> w.any { it.isLetterOrDigit() } }
        val lastWord = words.lastOrNull()?.lowercase(Locale.ROOT)?.trim { !it.isLetterOrDigit() }
        return when {
            "..." in text || "…" in text -> "it has '...'"
            reasoning.containsMatchIn(lower) -> "it reads like reasoning, not a name"
            words.size > MAX_CATEGORY_WORDS -> "it has ${words.size} words; use at most $MAX_CATEGORY_WORDS"
            text.trimEnd('.').endsWith(",") || lastWord in danglingWords -> "it stops mid-phrase"
            normalize(text) in promptPhrases -> "it repeats the prompt's wording"
            else -> null
        }
    }

    private const val MAX_FRIENDLY_WORDS = 8

    /** A friendly name the model wrote as a sentence or with an ellipsis is dropped, not retried. */
    private fun friendlyNameUsable(raw: String): Boolean =
        "..." !in raw && "…" !in raw && raw.trim().split(Regex("""\s+""")).size <= MAX_FRIENDLY_WORDS

    private fun singular(w: String): String = when {
        w.length > 3 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 3 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("ses")) -> w.dropLast(2)
        w.length > 1 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun isCatchAll(name: String): Boolean = normalize(name) in catchAll

    /** Names already taken, found by exact text (any case) and then by [normalize]. */
    private class NameTable {
        private val byLower = HashMap<String, String>()
        private val byNormal = HashMap<String, String>()

        fun add(name: String) {
            byLower.putIfAbsent(name.lowercase(Locale.ROOT), name)
            normalize(name).takeIf { it.isNotEmpty() }?.let { byNormal.putIfAbsent(it, name) }
        }

        fun find(name: String): String? =
            byLower[name.lowercase(Locale.ROOT)] ?: normalize(name).takeIf { it.isNotEmpty() }?.let { byNormal[it] }
    }

    private fun tableOf(existing: List<CategoryInfo>) = NameTable().also { t -> existing.forEach { t.add(it.name) } }

    private fun newEntity(name: String, description: String) = CategoryEntity(
        name = name,
        description = cleanDescription(description),
        createdBy = "model",
        folderName = null,
    )

    private fun cleanDescription(d: String) =
        d.replace(Regex("""\s+"""), " ").trim().take(MAX_CATEGORY_DESCRIPTION).trimEnd()

    /**
     * The categories a proposal pass adds: names cleaned, near-duplicates merged into the first, and
     * anything that matches an existing category dropped. Unusable names are skipped.
     */
    fun mergeProposals(proposals: List<NewCategory>, existing: List<CategoryInfo>): List<CategoryEntity> {
        val table = tableOf(existing)
        val out = ArrayList<CategoryEntity>()
        for (p in proposals) {
            val name = cleanName(p.name, MAX_CATEGORY_NAME)
            if (name.isEmpty() || table.find(name) != null || categoryNameProblem(p.name) != null) continue
            table.add(name)
            out += newEntity(name, p.description)
            if (out.size == SortSchema.MAX_NEW_CATEGORIES) break
        }
        return out
    }

    fun validate(
        reply: SortReply,
        chunk: List<SortItem>,
        existing: List<CategoryInfo>,
        projectItemCount: Int,
        isRetry: Boolean = false,
        maxNewCategories: Int = SortSchema.MAX_NEW_CATEGORIES,
    ): Validation {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        val byId = chunk.associateBy { it.id }

        // Ids: every chunk id exactly once, nothing else.
        val seen = HashSet<Long>()
        val duplicates = sortedSetOf<Long>()
        val foreign = sortedSetOf<Long>()
        for (a in reply.assignments) {
            if (a.id !in byId) foreign += a.id else if (!seen.add(a.id)) duplicates += a.id
        }
        val missing = chunk.map { it.id }.filter { it !in seen }

        // Problems that reject only the first attempt; on the retry they are warnings or leave items out.
        val soft = ArrayList<String>()
        val itemProblems = LinkedHashMap<Long, String>()

        if (!isRetry) {
            if (missing.isNotEmpty()) problems += "Missing ids (assign every listed item): ${missing.joinToString(", ")}"
            if (duplicates.isNotEmpty()) problems += "Duplicate ids (each id exactly once): ${duplicates.joinToString(", ")}"
            if (foreign.isNotEmpty()) problems += "Ids not in this chunk (use only the listed ids): ${foreign.joinToString(", ")}"
        } else {
            // A duplicated item is left out whole: which of its categories was meant is unknown.
            missing.forEach { itemProblems[it] = "Item $it was missing from the reply." }
            duplicates.forEach { itemProblems[it] = "Item $it was assigned more than once." }
            if (foreign.isNotEmpty()) warnings += "Ignored ids not in this chunk: ${foreign.joinToString(", ")}"
        }

        // Categories: existing ones win, then the first new one with a matching name (listed before adopted).
        val table = tableOf(existing)
        val created = LinkedHashMap<String, CategoryEntity>()
        for (nc in reply.newCategories) {
            val name = cleanName(nc.name, MAX_CATEGORY_NAME)
            if (name.isEmpty()) {
                soft += "A new category has an empty name (given '${nc.name.take(40)}'). Use a short noun."
                continue
            }
            if (table.find(name) != null) continue
            // Not added: an assignment to it is an item problem below; a listing nobody uses is dropped anyway.
            if (categoryNameProblem(nc.name) != null) continue
            table.add(name)
            created[name] = newEntity(name, nc.description)
        }

        // New names that are not names, with the items given them (one message per name).
        val badNames = LinkedHashMap<String, Pair<String, MutableList<Long>>>()

        val used = HashMap<String, MutableList<Long>>()
        val assignments = ArrayList<Assignment>()
        val done = HashSet<Long>()
        for (a in reply.assignments) {
            val item = byId[a.id] ?: continue
            if (isRetry && a.id in duplicates) continue
            if (!done.add(a.id)) continue
            // The exact name first, so an existing category longer than 40 chars still matches.
            var resolved = a.category.trim().takeIf { it.isNotEmpty() }?.let { table.find(it) }
            if (resolved == null) {
                val name = cleanName(a.category, MAX_CATEGORY_NAME)
                if (name.isEmpty()) {
                    itemProblems[a.id] = "Item ${a.id} has no usable category (given '${a.category.trim().take(40)}'). " +
                        "Use an existing category or a short noun."
                    continue
                }
                resolved = table.find(name)
                if (resolved == null) {
                    val why = categoryNameProblem(a.category)
                    if (why != null) {
                        badNames.getOrPut(name) { why to ArrayList() }.second += a.id
                        continue
                    }
                    // Not listed in new_categories: adopt it.
                    resolved = name.also {
                        table.add(it)
                        created[it] = newEntity(it, it)
                    }
                }
            }
            used.getOrPut(resolved) { ArrayList() } += a.id
            val friendly = if (item.nameSetByUser || !friendlyNameUsable(a.name)) item.suggestedName else
                cleanName(a.name, MAX_FRIENDLY_NAME).ifEmpty { item.suggestedName }
            assignments += Assignment(a.id, resolved, friendly)
        }
        for ((name, bad) in badNames) {
            val (why, ids) = bad
            val msg = "'$name' is not a usable category name ($why). Items ${ids.joinToString(", ")} need an existing " +
                "category or a short noun."
            ids.forEach { itemProblems[it] = msg }
        }

        // Catch-all bins stay small. On the retry a full one takes what fits, in assignment order, and
        // leaves the rest out, so one stubborn catch-all doesn't sink the items that were fine.
        val limit = maxOf(CATCH_ALL_MIN, projectItemCount / CATCH_ALL_DIVISOR)
        val overflow = HashSet<Long>()
        for ((name, ids) in used) {
            if (!isCatchAll(name)) continue
            val already = existing.filter { it.name == name }.sumOf { it.items }
            val total = already + ids.size
            if (total <= limit) continue
            if (!isRetry) {
                problems += "Too many items in a catch-all category '$name' ($total, at most $limit allowed). " +
                    "Put them in specific categories."
                continue
            }
            val room = (limit - already).coerceAtLeast(0)
            val msg = "Catch-all category '$name' is full (at most $limit items)."
            for (id in ids.drop(room)) {
                itemProblems[id] = msg
                overflow += id
            }
            ids.retainAll(ids.take(room))
        }
        if (overflow.isNotEmpty()) assignments.removeAll { it.itemId in overflow }

        // New categories nobody uses are dropped.
        var newUsed = created.values.filter { used[it.name]?.isNotEmpty() == true }

        // At most maxNewCategories new ones. On the retry the largest are kept (the first listed on a
        // tie) and the others' items are left out.
        if (newUsed.size > maxNewCategories) {
            if (!isRetry) {
                problems += "Too many new categories (${newUsed.size}; at most $maxNewCategories allowed). " +
                    "Use existing categories and fewer, broader new ones."
            } else {
                val kept = newUsed.sortedByDescending { used.getValue(it.name).size }.take(maxNewCategories).toSet()
                val dropped = HashSet<Long>()
                for (c in newUsed.filter { it !in kept }) {
                    for (id in used.getValue(c.name)) {
                        itemProblems[id] = "New category '${c.name}' was over the limit of $maxNewCategories new categories."
                        dropped += id
                    }
                    used.remove(c.name)
                }
                assignments.removeAll { it.itemId in dropped }
                newUsed = newUsed.filter { it in kept }
            }
        }

        // Singletons among the new categories are limited.
        // Checked even when other problems exist, so the single retry hears about every problem.
        val singles = newUsed.filter { used.getValue(it.name).size == 1 }
        if (singles.size > maxOf(SINGLETON_MIN, newUsed.size / SINGLETON_DIVISOR)) {
            val msg = "${singles.size} new categories hold only one item (${singles.joinToString { it.name }}). " +
                "Fold them into broader categories."
            if (isRetry) warnings += msg else problems += msg
        }

        val softProblems = soft + itemProblems.values.distinct()
        return when {
            problems.isNotEmpty() -> Validation.Rejected(problems + softProblems)
            !isRetry && softProblems.isNotEmpty() -> Validation.Rejected(softProblems)
            else -> Validation.Ok(newUsed, assignments, warnings + soft, leftOut = itemProblems)
        }
    }
}
