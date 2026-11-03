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

/** Why Re-sort (and Categorize now) is disabled while a run is in progress (Q12). */
const val RESORT_BUSY = "Re-sort is available when analysis is stopped or done"

/** The system would not start the foreground service (the app was in the background). */
const val RESORT_NOT_STARTED = "Re-sort could not start; try again"

/** A Re-sort for a project that is not the open one, e.g. one asked for during a switch. */
const val RESORT_NOT_OPEN = "Open the project to re-sort it"
