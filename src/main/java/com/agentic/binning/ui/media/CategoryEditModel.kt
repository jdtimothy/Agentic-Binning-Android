package com.agentic.binning.ui.media

import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.projectmedia.CategoryNames

/**
 * The Category Edit sheet's staged changes (plan 2b Task 14, mockup states 5a and 5b). Nothing is
 * written until Save, which applies every rename and merge in one transaction (Q6).
 *
 * [merges] are (keep, drop) pairs in the order staged; [ticked] holds at most two categories (Q10),
 * and [keepId] is the one whose name survives their merge.
 */
data class CategoryEditDraft(
    val renames: Map<Long, String> = emptyMap(),
    val merges: List<Pair<Long, Long>> = emptyList(),
    val ticked: List<Long> = emptyList(),
    val keepId: Long? = null,
    val editing: Long? = null,
    val editValue: String = "",
) {
    private val dropped: Set<Long> get() = merges.mapTo(HashSet()) { it.second }

    private fun name(c: CategoryEntity) = renames[c.id] ?: c.name

    /** The other visible categories' names as they would be after Save. */
    private fun othersNames(cats: List<CategoryEntity>, except: Long) =
        cats.filter { it.id != except && it.id !in dropped }.map(::name)

    /** Why the name being typed can't be used, or null. */
    fun editError(cats: List<CategoryEntity>): String? {
        val id = editing ?: return null
        val clean = CategoryNames.clean(editValue)
        if (clean.isEmpty()) return null
        return CategoryNames.clash(clean, othersNames(cats, id))?.let { "Same as “$it”. Tick both to merge instead." }
    }

    fun canFinish(cats: List<CategoryEntity>) =
        editing != null && CategoryNames.clean(editValue).isNotEmpty() && editError(cats) == null

    fun startRename(id: Long, cats: List<CategoryEntity>): CategoryEditDraft {
        val c = cats.firstOrNull { it.id == id } ?: return this
        return copy(editing = id, editValue = name(c))
    }

    fun editTo(text: String) = copy(editValue = text)

    /** Stages the rename (the tick button), or drops it when the name is back to the saved one. */
    fun finishRename(cats: List<CategoryEntity>): CategoryEditDraft {
        val id = editing ?: return this
        if (!canFinish(cats)) return this
        val saved = cats.first { it.id == id }.name
        val clean = CategoryNames.clean(editValue)
        val next = if (clean == saved) renames - id else renames + (id to clean)
        return copy(renames = next, editing = null, editValue = "")
    }

    fun cancelRename() = copy(editing = null, editValue = "")

    /** Ticks or unticks [id] for a merge; a third tick is ignored. The first ticked keeps its name by default. */
    fun tick(id: Long): CategoryEditDraft = when {
        id in ticked -> {
            val rest = ticked - id
            copy(ticked = rest, keepId = keepId.takeIf { it != id && it in rest } ?: rest.firstOrNull())
        }
        ticked.size >= 2 -> this
        else -> copy(ticked = ticked + id, keepId = keepId ?: id)
    }

    fun keep(id: Long) = if (id in ticked) copy(keepId = id) else this

    /** Stages merging the two ticked categories into [keepId]. */
    fun merge(): CategoryEditDraft {
        if (ticked.size != 2) return this
        val keep = keepId ?: ticked.first()
        val drop = ticked.first { it != keep }
        return copy(merges = merges + (keep to drop), ticked = emptyList(), keepId = null)
    }

    /** The merge box's Cancel. */
    fun untick() = copy(ticked = emptyList(), keepId = null)
}

data class CategoryEditRow(
    val id: Long,
    /** The name as it will be after Save. */
    val name: String,
    /** Its items, plus those of the categories merged into it. */
    val count: Int,
    val yours: Boolean,
    /** "Renamed from …", "Merged with …". */
    val changes: List<String>,
    val ticked: Boolean,
    val tickEnabled: Boolean,
    val editing: Boolean,
    val editValue: String,
    val editError: String?,
    val canFinish: Boolean,
)

data class KeepOption(val id: Long, val label: String, val detail: String, val selected: Boolean)

data class MergeBox(val title: String, val options: List<KeepOption>)

data class CategoryEditUi(
    val rows: List<CategoryEditRow>,
    val mergeBox: MergeBox?,
    val saveLabel: String,
    val canSave: Boolean,
)

/** What Save applies: renames of the surviving categories, and the merges in order (keep, drop). */
data class CategoryEdits(val renames: Map<Long, String>, val merges: List<Pair<Long, Long>>) {
    val count: Int get() = renames.size + merges.size
}

fun categoryEditsOf(cats: List<CategoryEntity>, d: CategoryEditDraft): CategoryEdits {
    val dropped = d.merges.mapTo(HashSet()) { it.second }
    val ids = cats.mapTo(HashSet()) { it.id }
    return CategoryEdits(
        renames = d.renames.filterKeys { it in ids && it !in dropped },
        merges = d.merges,
    )
}

fun categoryEditUi(cats: List<CategoryEntity>, counts: Map<Long, Int>, d: CategoryEditDraft): CategoryEditUi {
    val byId = cats.associateBy { it.id }
    val dropped = d.merges.mapTo(HashSet()) { it.second }
    fun name(id: Long) = d.renames[id] ?: byId[id]?.name.orEmpty()
    fun total(id: Long): Int = (counts[id] ?: 0) + d.merges.filter { it.first == id }.sumOf { total(it.second) }
    val error = d.editError(cats)
    val rows = cats.filter { it.id !in dropped }.map { c ->
        val mergedIn = d.merges.filter { it.first == c.id }.map { name(it.second) }
        CategoryEditRow(
            id = c.id,
            name = name(c.id),
            count = total(c.id),
            yours = c.createdBy == "user",
            changes = listOfNotNull(
                d.renames[c.id]?.let { "Renamed from ${c.name}" },
                mergedIn.takeIf { it.isNotEmpty() }?.let { "Merged with ${it.joinToString(", ")}" },
            ),
            ticked = c.id in d.ticked,
            tickEnabled = c.id in d.ticked || d.ticked.size < 2,
            editing = d.editing == c.id,
            editValue = if (d.editing == c.id) d.editValue else "",
            editError = if (d.editing == c.id) error else null,
            canFinish = d.editing == c.id && d.canFinish(cats),
        )
    }
    val box = if (d.ticked.size == 2) {
        val keep = d.keepId ?: d.ticked.first()
        MergeBox(
            title = "Merge ${name(d.ticked[0])} and ${name(d.ticked[1])}",
            // In 2b nothing is binned, so no category has a folder yet: a merge never moves files now.
            options = d.ticked.map { KeepOption(it, "Keep the name “${name(it)}”", "No files move now.", it == keep) },
        )
    } else {
        null
    }
    val n = categoryEditsOf(cats, d).count
    return CategoryEditUi(
        rows = rows,
        mergeBox = box,
        saveLabel = when (n) {
            0 -> "Save"
            1 -> "Save 1 change"
            else -> "Save $n changes"
        },
        canSave = n > 0,
    )
}
