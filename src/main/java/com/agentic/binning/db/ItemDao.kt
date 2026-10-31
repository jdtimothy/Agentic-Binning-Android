package com.agentic.binning.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A project's item count and how many of those still wait for analysis (Pending or Analyzing). */
data class ItemTotals(val total: Int, val waiting: Int)

/** Narrow view of [ItemDao] used by the registrar, so it can be tested with a fake. */
interface ItemStore {
    suspend fun findByHashes(hashes: List<String>): List<ItemEntity>
    suspend fun findByPaths(paths: List<String>): List<ItemEntity>
    suspend fun insert(item: ItemEntity): Long
}

@Dao
interface ItemDao : ItemStore {
    @Insert
    override suspend fun insert(item: ItemEntity): Long

    @Query("SELECT * FROM item WHERE contentHash IN (:hashes)")
    override suspend fun findByHashes(hashes: List<String>): List<ItemEntity>

    @Query("SELECT * FROM item WHERE currentPath IN (:paths)")
    override suspend fun findByPaths(paths: List<String>): List<ItemEntity>

    @Query("SELECT * FROM item ORDER BY addedAt, id")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE status = 'Pending' ORDER BY id LIMIT 1")
    suspend fun nextPending(): ItemEntity?

    @Query("UPDATE item SET status = :status, failureReason = :failureReason WHERE id = :id")
    suspend fun setStatus(id: Long, status: ItemStatus, failureReason: String? = null)

    @Query("UPDATE item SET status = :to WHERE status = :from")
    suspend fun resetStatus(from: ItemStatus, to: ItemStatus): Int

    @Query("UPDATE item SET status = 'Analyzing', failureReason = NULL, attempts = attempts + 1 WHERE id = :id")
    suspend fun markAnalyzing(id: Long)

    @Query("UPDATE item SET status = 'Pending', attempts = 0 WHERE id = :id")
    suspend fun returnToPending(id: Long)

    @Query("UPDATE item SET status = 'Failed', failureReason = :reason WHERE status = 'Analyzing' AND attempts >= :failAfter")
    suspend fun failInterrupted(failAfter: Int, reason: String): Int

    /** See `AnalysisStore.recoverInterrupted`. */
    @Transaction
    suspend fun recoverInterrupted(failAfter: Int, reason: String): Int =
        failInterrupted(failAfter, reason) + resetStatus(ItemStatus.Analyzing, ItemStatus.Pending)

    @Query("SELECT COUNT(*) FROM item WHERE status = :status")
    suspend fun countByStatus(status: ItemStatus): Int

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(status IN ('Pending', 'Analyzing')), 0) AS waiting FROM item")
    fun observeTotals(): Flow<ItemTotals>
}
