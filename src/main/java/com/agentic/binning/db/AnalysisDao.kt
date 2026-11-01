package com.agentic.binning.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction

@Dao
abstract class AnalysisDao {
    /**
     * Saves [analysis] and its [tags] (stored trimmed and lowercased), marks the item Analyzed (or
     * keeps it Sorted when it has a category, so a re-analysis doesn't send it back to sorting) and
     * refreshes its FTS row (keeping the item's friendly name and category) and resets its attempts,
     * all in one transaction.
     */
    @Transaction
    open suspend fun saveAnalysis(analysis: AnalysisEntity, tags: List<String>) {
        val cleanTags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        upsertAnalysis(analysis)
        if (cleanTags.isNotEmpty()) insertTags(cleanTags.map { TagEntity(name = it) })
        deleteItemTags(analysis.itemId)
        if (cleanTags.isNotEmpty()) {
            insertItemTags(tagIds(cleanTags).map { ItemTagEntity(analysis.itemId, it) })
        }
        markAnalyzed(analysis.itemId)
        deleteFtsRow(analysis.itemId)
        rebuildFtsRow(analysis.itemId)
    }

    @Query("SELECT * FROM analysis WHERE itemId = :itemId")
    abstract suspend fun get(itemId: Long): AnalysisEntity?

    @Query(
        "SELECT tag.name FROM tag INNER JOIN item_tag ON item_tag.tagId = tag.id " +
            "WHERE item_tag.itemId = :itemId ORDER BY tag.name",
    )
    abstract suspend fun tagsFor(itemId: Long): List<String>

    /** [ftsQuery] is an FTS5 MATCH expression; returns matching item ids. */
    @SkipQueryVerification
    @Query("SELECT rowid FROM item_fts WHERE item_fts MATCH :ftsQuery")
    abstract suspend fun search(ftsQuery: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertAnalysis(analysis: AnalysisEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTags(tags: List<TagEntity>)

    @Query("SELECT id FROM tag WHERE name IN (:names)")
    protected abstract suspend fun tagIds(names: List<String>): List<Long>

    @Query("DELETE FROM item_tag WHERE itemId = :itemId")
    protected abstract suspend fun deleteItemTags(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertItemTags(links: List<ItemTagEntity>)

    @Query(
        "UPDATE item SET status = CASE WHEN categoryId IS NULL THEN 'Analyzed' ELSE 'Sorted' END, " +
            "failureReason = NULL, attempts = 0 WHERE id = :itemId",
    )
    protected abstract suspend fun markAnalyzed(itemId: Long)

    @SkipQueryVerification
    @Query("DELETE FROM item_fts WHERE rowid = :itemId")
    protected abstract suspend fun deleteFtsRow(itemId: Long)

    /** Rebuilds the row from the tables, so the item's current friendly name and category stay in it. */
    @SkipQueryVerification
    @Query(FTS_REBUILD_ROW)
    protected abstract suspend fun rebuildFtsRow(itemId: Long)
}
