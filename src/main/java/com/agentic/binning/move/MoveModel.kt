package com.agentic.binning.move

/** What started a run: Bin Files, or a Save that touched binned files. Stored by name in `move_run.kind`. */
enum class MoveKind {
    Bin, Save;

    companion object {
        /** Unknown or missing values read as [Bin]: both kinds run the same way. */
        fun of(name: String?): MoveKind = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Bin
    }
}

/** A run's state, stored by name in `move_run.state`. */
enum class RunState {
    Running, Paused, Stopped, Done, Failed;

    /** A run the app did not finish: resumed when its project opens (B3), paused again if it was paused (B2). */
    val isOpen: Boolean get() = this == Running || this == Paused

    companion object {
        /** A value from a newer build reads as [Failed], so it is never resumed by mistake. */
        fun of(name: String?): RunState = entries.firstOrNull { it.name == name } ?: Failed
    }
}

/**
 * One file's progress, stored by name in `move_step.state`. Written after each phase, so a crash
 * resumes where it stopped (Task 7):
 * - [Planned]: nothing has happened yet, or a rename may have happened but isn't recorded.
 * - [Copied]: a copy to the temp file is complete but not yet checked (copies across volumes only).
 * - [Verified]: the file is at its destination and recorded; the source may still exist.
 * - [Done], [Skipped], [Missing], [Failed]: final.
 */
enum class StepState {
    Planned, Copied, Verified, Done, Skipped, Missing, Failed;

    val isFinal: Boolean get() = this == Done || this == Skipped || this == Missing || this == Failed

    val needsWork: Boolean get() = !isFinal

    companion object {
        /** A value from a newer build reads as [Failed]: final, so the file is left alone. */
        fun of(name: String?): StepState = entries.firstOrNull { it.name == name } ?: Failed
    }
}

/** Why a step was skipped or failed (`move_step.error`). */
object StepErrors {
    /** A copy didn't match its original twice (B4); the source is untouched. */
    const val COPY_MISMATCH = "copy-mismatch"

    /** The step was started twice without finishing (B3): a file that crashes the app can't loop. */
    const val INTERRUPTED = "interrupted"

    /** A rename's check failed: the file isn't at its target with the same size. Stops the run. */
    const val RENAME_CHECK = "rename-check"

    /** The rename or copy was refused by the system. */
    const val IO = "io"
}

/** What the progress card shows for the file in progress. */
enum class MovePhase { Moving, Copying, Checking, Placing, DeletingOriginal }

data class MoveProgress(
    val kind: MoveKind,
    /** Steps finished (in any final state). */
    val done: Int,
    val total: Int,
    /** The file in progress, by its destination's file name; null between files. */
    val current: String?,
    val phase: MovePhase?,
    val paused: Boolean,
)

/** How a run ended, or why it didn't start. */
sealed interface MoveOutcome {
    data object Done : MoveOutcome

    /** The user pressed Stop; the file in progress finished first (B2). */
    data object Stopped : MoveOutcome

    /** Not enough free space for the next copy, or under the floor every run keeps (B6). */
    data class StorageFull(val neededBytes: Long) : MoveOutcome

    /** Something the run can't carry on from (a rename's check failed). */
    data class Failed(val message: String) : MoveOutcome

    /** The check before found files no item points to (B5); nothing was written or moved. */
    data class Blocked(val problems: LayoutProblems) : MoveOutcome

    /** The plan had nothing to move; [inPlace] items were only marked binned (B9). */
    data class NothingToMove(val inPlace: Int) : MoveOutcome
}
