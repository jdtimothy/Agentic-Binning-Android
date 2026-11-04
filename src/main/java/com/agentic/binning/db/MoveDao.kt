package com.agentic.binning.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agentic.binning.move.BinItem
import com.agentic.binning.move.MoveStore

data class CategoryName(val id: Long, val name: String)

/** Bin Files and Save moves (Plan 3): runs, steps, the removed-file record, and the planner's input. */
@Dao
abstract class MoveDao : MoveStore {
    @Insert
    protected abstract suspend fun insertRun(run: MoveRunEntity): Long

    @Insert
    protected abstract suspend fun insertSteps(steps: List<MoveStepEntity>)

    @Query("UPDATE item SET status = 'Binned' WHERE id IN (:ids)")
    protected abstract suspend fun markBinned(ids: List<Long>)

    @Query("UPDATE item SET status = 'Missing' WHERE id IN (:ids)")
    protected abstract suspend fun markMissing(ids: List<Long>)

    /**
     * Writes a run and every step in one transaction before anything moves, with the items found in
     * place marked binned and the ones whose file is gone marked Missing. Returns the run's id.
     */
    @Transaction
    open suspend fun writeRun(run: MoveRunEntity, steps: List<MoveStepEntity>, inPlace: List<Long>, missing: List<Long>): Long {
        val runId = insertRun(run)
        insertSteps(steps.map { it.copy(id = 0, runId = runId) })
        markItems(inPlace, missing)
        return runId
    }

    /** For a plan with nothing to move: the in-place items are marked binned and the gone ones Missing. */
    @Transaction
    open suspend fun markItems(inPlace: List<Long>, missing: List<Long>) {
        if (inPlace.isNotEmpty()) markBinned(inPlace)
        if (missing.isNotEmpty()) markMissing(missing)
    }

    @Query("SELECT * FROM move_run WHERE state IN ('Running', 'Paused') ORDER BY id DESC LIMIT 1")
    abstract suspend fun openRun(): MoveRunEntity?

    @Query("SELECT * FROM move_run WHERE id = :id")
    abstract suspend fun run(id: Long): MoveRunEntity?

    @Query("SELECT * FROM move_run WHERE finishedAt IS NOT NULL ORDER BY id DESC LIMIT 1")
    abstract suspend fun lastFinishedRun(): MoveRunEntity?

    @Query("SELECT * FROM move_step WHERE runId = :runId ORDER BY id")
    abstract override suspend fun steps(runId: Long): List<MoveStepEntity>

    @Query("UPDATE move_step SET attempts = attempts + 1 WHERE id = :stepId")
    protected abstract suspend fun bumpAttempts(stepId: Long)

    @Query("SELECT attempts FROM move_step WHERE id = :stepId")
    protected abstract suspend fun attempts(stepId: Long): Int

    @Transaction
    override suspend fun startStep(stepId: Long): Int {
        bumpAttempts(stepId)
        return attempts(stepId)
    }

    @Query("UPDATE move_step SET state = :state, error = :error WHERE id = :stepId")
    abstract override suspend fun setStepState(stepId: Long, state: String, error: String?)

    @Query("UPDATE move_step SET dest = :dest WHERE id = :stepId")
    abstract override suspend fun setStepDest(stepId: Long, dest: String)

    @Query("UPDATE item SET currentPath = :dest, status = 'Binned' WHERE id = :itemId")
    protected abstract suspend fun moveItem(itemId: Long, dest: String)

    @Query("SELECT EXISTS(SELECT 1 FROM item WHERE contentHash = :hash AND id != :itemId)")
    protected abstract suspend fun hashTaken(hash: String, itemId: Long): Boolean

    @Query("UPDATE item SET contentHash = :hash WHERE id = :itemId")
    protected abstract suspend fun setHash(itemId: Long, hash: String)

    @Transaction
    override suspend fun recordMoved(stepId: Long, itemId: Long, dest: String, hash: String?, state: String) {
        moveItem(itemId, dest)
        // The file was edited since it was added: keep the stored hash true, unless that would clash.
        if (hash != null && !hashTaken(hash, itemId)) setHash(itemId, hash)
        setStepState(stepId, state, null)
    }

    @Transaction
    override suspend fun recordMissing(stepId: Long, itemId: Long) {
        markMissing(listOf(itemId))
        setStepState(stepId, "Missing", null)
    }

    @Query("UPDATE item SET currentPath = :source, status = 'Sorted' WHERE id = :itemId")
    protected abstract suspend fun revertItem(itemId: Long, source: String)

    @Transaction
    override suspend fun recordReverted(stepId: Long, itemId: Long, source: String, error: String) {
        revertItem(itemId, source)
        setStepState(stepId, "Failed", error)
    }

    @Query("UPDATE move_run SET state = :state, message = :message WHERE id = :runId")
    abstract override suspend fun setRunState(runId: Long, state: String, message: String?)

    @Query(
        "UPDATE move_run SET state = :state, finishedAt = :finishedAt, message = :message, " +
            "removedFolders = :removedFolders WHERE id = :runId",
    )
    abstract suspend fun finishRun(runId: Long, state: String, finishedAt: Long, message: String?, removedFolders: String?)

    @Query("SELECT DISTINCT dest FROM move_step WHERE state IN ('Done', 'Verified')")
    protected abstract suspend fun finishedDestinations(): List<String>

    /** Folders a finished step put a file in: the only folders Bin Files may remove once empty (B12). */
    open suspend fun binnedFolders(): List<String> =
        finishedDestinations().map { it.substringBeforeLast('/') }.distinct()

    @Query("SELECT path FROM removed_file ORDER BY path")
    abstract suspend fun removedPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRemoved(rows: List<RemovedFileEntity>)

    /** Remembers [paths] as files the folder check skips: `removed` (Q2) or `left` (B5). */
    open suspend fun addRemoved(paths: List<String>, reason: String, at: Long) =
        insertRemoved(paths.map { RemovedFileEntity(it, at, reason) })

    @Query("DELETE FROM removed_file WHERE path IN (:paths)")
    abstract suspend fun dropRemoved(paths: List<String>)

    @Query("SELECT id, status, categoryId, friendlyName, currentPath, originalName FROM item ORDER BY id")
    abstract suspend fun binItems(): List<BinItem>

    @Query("SELECT id, name FROM category ORDER BY id")
    abstract suspend fun categoryNames(): List<CategoryName>
}
