package com.agentic.binning.projectmedia

import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus

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

/** Whether [row] passes every part of this filter; Project Media's grid, chips and Filters sheet all use it. */
fun MediaFilter.matches(row: MediaRow): Boolean =
    when (chip) {
        MediaChip.All -> true
        MediaChip.Waiting -> isWaiting(row.status, row.categoryId, row.categorySetByUser)
        MediaChip.Failed -> row.status == ItemStatus.Failed
        is MediaChip.Category -> row.categoryId == chip.id
    } &&
        (kind == null || row.kind == kind) &&
        (shotType == null || row.shotType == shotType) &&
        (takenFrom == null || (row.takenAt != null && row.takenAt >= takenFrom)) &&
        (takenTo == null || (row.takenAt != null && row.takenAt <= takenTo))

data class ChipCount(val chip: MediaChip, val label: String, val count: Int)
