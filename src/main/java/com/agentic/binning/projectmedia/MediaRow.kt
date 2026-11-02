package com.agentic.binning.projectmedia

import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus

/** Which FTS column produced a search hit, for the tile's "Matched in" line (Q15). */
enum class MatchedIn { Name, Category, Tags, Actions, Description }

/** One Project Media tile. */
data class MediaRow(
    val id: Long,
    val kind: ItemKind,
    val durationMs: Long?,
    val takenAt: Long?,
    val status: ItemStatus,
    val categoryId: Long?,
    val friendlyName: String?,
    val suggestedName: String?,
    val shotType: String?,
    val nameSetByUser: Boolean,
    val categorySetByUser: Boolean,
    val currentPath: String,
    val matchedIn: MatchedIn? = null,
)

sealed interface CategoryChoice {
    data class Existing(val id: Long) : CategoryChoice
    data class New(val name: String) : CategoryChoice
}

sealed interface EditResult {
    data object Ok : EditResult
    data class Created(val id: Long) : EditResult
    data class Clash(val existing: String) : EditResult
    data object InvalidName : EditResult
    data object NotFound : EditResult
}
