package com.agentic.binning.sorting

import com.agentic.binning.db.CategoryInfo
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.UncategorizedItem

internal fun item(
    id: Long,
    kind: ItemKind = ItemKind.Photo,
    name: String = "Item $id",
    tags: List<String> = listOf("a", "b"),
    description: String = "A description of item $id.",
    actions: List<String> = emptyList(),
    nameSetByUser: Boolean = false,
) = SortItem(id, kind, name, tags, description, actions, nameSetByUser)

internal fun category(
    name: String,
    items: Int = 0,
    createdBy: String = "model",
    description: String = "",
    id: Long = 1,
) = CategoryInfo(id, name, description, createdBy, items)

internal fun uncategorized(id: Long, kind: ItemKind, actionsJson: String = "[]") =
    UncategorizedItem(id, kind, "desc", actionsJson, "Name", listOf("t"), false)

/** Builds a reply as the model would write it and reads it back with the real parser. */
internal fun reply(
    newCategories: List<Pair<String, String>>,
    assignments: List<Triple<Long, String, String>>,
): SortReply {
    val nc = newCategories.joinToString(",") { """{"name":"${it.first}","description":"${it.second}"}""" }
    val a = assignments.joinToString(",") { """{"id":${it.first},"category":"${it.second}","name":"${it.third}"}""" }
    return SortParser.parse("""{"new_categories":[$nc],"assignments":[$a]}""")!!
}
