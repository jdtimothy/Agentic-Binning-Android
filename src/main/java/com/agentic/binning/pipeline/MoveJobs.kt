package com.agentic.binning.pipeline

import com.agentic.binning.move.MoveControl
import com.agentic.binning.move.MoveOutcome
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.project.ProjectRef

/** What a move run does: Bin Files (check, plan, move), a Save's written plan, or finish a run the app didn't (B3). */
sealed interface MoveJob {
    data object BinFiles : MoveJob
    data class Save(val runId: Long) : MoveJob
    data class Resume(val runId: Long) : MoveJob
}

/** Runs one move job for the open project (AppGraph: `BinFilesJob` over its database and files). */
fun interface FileMover {
    suspend fun run(project: ProjectRef, job: MoveJob, control: MoveControl, onProgress: (MoveProgress) -> Unit): MoveOutcome
}

sealed interface MoveStart {
    data object Started : MoveStart
    data class Refused(val reason: String) : MoveStart
}

/** A Save that would move files while another run is going: refused, never queued (Q11). */
const val SAVE_BUSY = "Saving moves files, so it waits until analysis is done"

/** Bin Files while another run is going: disabled with this reason, never queued (Q11, Q12). */
const val MOVE_BUSY = "Bin Files is available when analysis is stopped or done"

const val MOVE_NOT_OPEN = "Open the project to bin its files"
const val MOVE_NOT_STARTED = "Bin Files couldn't start. Try again with the app open."
