package com.agentic.binning.projectmedia

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.db.ItemEntity

/** Everything the detail sheet shows about one item. Analysis fields are null or empty without one. */
data class ItemDetail(
    val item: ItemEntity,
    val description: String?,
    val actions: List<ActionEntry>,
    val tags: List<String>,
    val suggestedName: String?,
    val shotType: String?,
    val categoryName: String?,
    val categoryDescription: String?,
    val categoryCreatedBy: String?,
)
