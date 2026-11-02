package com.agentic.binning.projectmedia

import com.agentic.binning.settings.SortingModelChoice

/** What a Re-sort run should use (Task 9). [keepEdits] is ticked by default (Q4). */
data class ResortRequest(
    val model: SortingModelChoice,
    val keepEdits: Boolean = true,
)

sealed interface ResortStart {
    data object Started : ResortStart
    data class Refused(val reason: String) : ResortStart
}
