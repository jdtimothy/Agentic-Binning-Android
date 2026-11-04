package com.agentic.binning.move

import com.agentic.binning.db.MoveStepEntity

/** What the move engine reads and writes in the project database; `MoveDao` implements it. */
interface MoveStore {
    suspend fun steps(runId: Long): List<MoveStepEntity>

    /** Counts a start of the step and returns how many there have been, this one included. */
    suspend fun startStep(stepId: Long): Int

    suspend fun setStepState(stepId: Long, state: String, error: String?)

    /** A name taken after planning: the step moves to [dest] instead. */
    suspend fun setStepDest(stepId: Long, dest: String)

    /**
     * In one transaction: the item is at [dest] and binned, its hash is [hash] (unless null, or another
     * item has it), and the step is [state].
     */
    suspend fun recordMoved(stepId: Long, itemId: Long, dest: String, hash: String?, state: String)

    /** In one transaction: the item is Missing and so is the step. */
    suspend fun recordMissing(stepId: Long, itemId: Long)

    /**
     * In one transaction: the item is back at [source] and Sorted, and the step Failed with [error].
     * For a recorded file whose destination vanished while its source is still there.
     */
    suspend fun recordReverted(stepId: Long, itemId: Long, source: String, error: String)

    suspend fun setRunState(runId: Long, state: String, message: String?)
}
