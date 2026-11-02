package com.agentic.binning.projectmedia

import com.agentic.binning.db.ItemKind

/** Which chip is selected on Project Media (All, Waiting, Failed, or one category). */
sealed interface MediaChip {
    data object All : MediaChip
    data object Waiting : MediaChip
    data object Failed : MediaChip
    data class Category(val id: Long) : MediaChip
}

/**
 * Grid filter for Project Media. [chip] is the category row; [kind], [shotType] and the taken-at
 * bounds come from the Filters sheet. A null bound means "no limit on that side".
 */
data class MediaFilter(
    val chip: MediaChip = MediaChip.All,
    val kind: ItemKind? = null,
    val shotType: String? = null,
    val takenFrom: Long? = null,
    val takenTo: Long? = null,
)

data class ChipCount(val chip: MediaChip, val label: String, val count: Int)
