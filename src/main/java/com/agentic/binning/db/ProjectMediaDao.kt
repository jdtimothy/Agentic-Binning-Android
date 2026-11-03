package com.agentic.binning.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * FTS rebuild that left-joins analysis, so an item set by hand with no analysis still has a row
 * (name and category). 2a's [FTS_REBUILD_ROW] inner-joins analysis and stays for 2a's callers.
 */
internal const val FTS_REBUILD_ROW_LEFT =
    "INSERT INTO item_fts(rowid, description, actions, tags, friendly_name, category) " +
        "SELECT item.id, COALESCE(analysis.description, ''), COALESCE(analysis.actionsJson, ''), " +
        "COALESCE((SELECT group_concat(tag.name, ' ') FROM item_tag " +
        "INNER JOIN tag ON tag.id = item_tag.tagId WHERE item_tag.itemId = item.id), ''), " +
        "COALESCE(item.friendlyName, ''), COALESCE(category.name, '') " +
        "FROM item LEFT JOIN analysis ON analysis.itemId = item.id " +
        "LEFT JOIN category ON category.id = item.categoryId WHERE item.id = :itemId"

data class MediaGridRow(
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
    val hasAnalysis: Boolean,
)

@Dao
abstract class ProjectMediaDao {
    @Query(
        "SELECT item.id AS id, item.kind AS kind, item.durationMs AS durationMs, item.takenAt AS takenAt, " +
            "item.status AS status, item.categoryId AS categoryId, item.friendlyName AS friendlyName, " +
            "analysis.suggestedName AS suggestedName, analysis.shotType AS shotType, " +
            "item.nameSetByUser AS nameSetByUser, item.categorySetByUser AS categorySetByUser, " +
            "item.currentPath AS currentPath, analysis.itemId IS NOT NULL AS hasAnalysis " +
            "FROM item LEFT JOIN analysis ON analysis.itemId = item.id " +
            "ORDER BY item.takenAt ASC, item.id ASC",
    )
    abstract fun observeGrid(): Flow<List<MediaGridRow>>

    @Query("SELECT * FROM item WHERE id = :id")
    abstract suspend fun item(id: Long): ItemEntity?

    @Query("SELECT * FROM category ORDER BY id")
    abstract suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    abstract suspend fun category(id: Long): CategoryEntity?

    @Insert
    abstract suspend fun insertCategory(category: CategoryEntity): Long

    /**
     * The user's rename: the category becomes theirs and loses its description, which the model
     * wrote for the old name (sorting reads it, and would make the old name again from it).
     */
    @Query("UPDATE category SET name = :name, description = '', createdBy = 'user' WHERE id = :id")
    abstract suspend fun renameCategory(id: Long, name: String)

    @Query("UPDATE category SET createdBy = 'user' WHERE id = :id")
    abstract suspend fun markCategoryUser(id: Long)

    @Query("DELETE FROM category WHERE id = :id")
    abstract suspend fun deleteCategory(id: Long)

    @Query("UPDATE item SET categoryId = :keep WHERE categoryId = :drop")
    abstract suspend fun reassignCategory(drop: Long, keep: Long)

    @Query("SELECT id FROM item WHERE categoryId = :categoryId")
    abstract suspend fun itemIdsInCategory(categoryId: Long): List<Long>

    @Query(
        "UPDATE item SET friendlyName = :name, nameSetByUser = :nameSetByUser, " +
            "categoryId = :categoryId, categorySetByUser = :categorySetByUser, " +
            "status = :status WHERE id = :id",
    )
    abstract suspend fun updateItemEdit(
        id: Long,
        name: String?,
        nameSetByUser: Boolean,
        categoryId: Long?,
        categorySetByUser: Boolean,
        status: ItemStatus,
    )

    @Query("DELETE FROM item WHERE id IN (:ids)")
    abstract suspend fun deleteItems(ids: List<Long>)

    @Query("UPDATE item SET status = 'Pending', attempts = 0, failureReason = NULL WHERE id = :id")
    abstract suspend fun retryFailed(id: Long)

    @SkipQueryVerification
    @Query("DELETE FROM item_fts WHERE rowid = :itemId")
    abstract suspend fun deleteFtsRow(itemId: Long)

    @SkipQueryVerification
    @Query("DELETE FROM item_fts WHERE rowid IN (:ids)")
    abstract suspend fun deleteFtsRows(ids: List<Long>)

    @SkipQueryVerification
    @Query(FTS_REBUILD_ROW_LEFT)
    abstract suspend fun rebuildFtsRowLeft(itemId: Long)

    @SkipQueryVerification
    @Query("SELECT rowid FROM item_fts WHERE rowid IN (:ids) AND item_fts MATCH :ftsQuery")
    abstract suspend fun ftsHits(ids: List<Long>, ftsQuery: String): List<Long>

    @Transaction
    open suspend fun refreshFts(itemId: Long) {
        deleteFtsRow(itemId)
        rebuildFtsRowLeft(itemId)
    }
}
