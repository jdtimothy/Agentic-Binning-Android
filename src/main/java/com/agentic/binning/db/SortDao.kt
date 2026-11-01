package com.agentic.binning.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** An analyzed item that no category holds yet, as the sorting model sees it. */
data class UncategorizedItem(
    val id: Long,
    val kind: ItemKind,
    val description: String,
    val actionsJson: String,
    val suggestedName: String,
    val tags: List<String>,
    val nameSetByUser: Boolean,
)

/** A category with the number of items currently in it. */
data class CategoryInfo(
    val id: Long,
    val name: String,
    val description: String,
    val createdBy: String,
    val items: Int,
)

/** The sorting model's decision for one item: a category by exact name, and a friendly name. */
data class Assignment(val itemId: Long, val categoryName: String, val friendlyName: String)

/** [UncategorizedItem] without its tags, which come from a second query (Room cannot fill a list column). */
data class UncategorizedRow(
    val id: Long,
    val kind: ItemKind,
    val description: String,
    val actionsJson: String,
    val suggestedName: String,
    val nameSetByUser: Boolean,
)

data class ItemTagName(val itemId: Long, val name: String)

/**
 * The FTS5 row of an item is rebuilt from the tables themselves, so a re-analysis keeps the friendly
 * name and category, and a sort keeps the description and tags. Room doesn't know `item_fts`, hence
 * SkipQueryVerification on every query that touches it.
 */
internal const val FTS_REBUILD_ROW =
    "INSERT INTO item_fts(rowid, description, actions, tags, friendly_name, category) " +
        "SELECT item.id, analysis.description, analysis.actionsJson, " +
        "COALESCE((SELECT group_concat(tag.name, ' ') FROM item_tag " +
        "INNER JOIN tag ON tag.id = item_tag.tagId WHERE item_tag.itemId = item.id), ''), " +
        "COALESCE(item.friendlyName, ''), COALESCE(category.name, '') " +
        "FROM item INNER JOIN analysis ON analysis.itemId = item.id " +
        "LEFT JOIN category ON category.id = item.categoryId WHERE item.id = :itemId"

@Dao
abstract class SortDao {
    @Query(
        "SELECT category.id AS id, category.name AS name, category.description AS description, " +
            "category.createdBy AS createdBy, " +
            "(SELECT COUNT(*) FROM item WHERE item.categoryId = category.id) AS items " +
            "FROM category ORDER BY category.id",
    )
    abstract suspend fun categories(): List<CategoryInfo>

    /** Every category, for showing names (the carousel's badges). */
    @Query("SELECT * FROM category ORDER BY id")
    abstract fun observeCategories(): Flow<List<CategoryEntity>>

    /**
     * Analyzed items with no category, oldest first. An item whose category the user set is left out:
     * [applyChunk] would never assign it, so offering it would only cost a model call.
     */
    @Transaction
    open suspend fun uncategorized(): List<UncategorizedItem> {
        val rows = uncategorizedRows()
        if (rows.isEmpty()) return emptyList()
        val tagsByItem = uncategorizedTags().groupBy({ it.itemId }, { it.name })
        return rows.map {
            UncategorizedItem(
                id = it.id,
                kind = it.kind,
                description = it.description,
                actionsJson = it.actionsJson,
                suggestedName = it.suggestedName,
                tags = tagsByItem[it.id].orEmpty(),
                nameSetByUser = it.nameSetByUser,
            )
        }
    }

    @Query("SELECT COUNT(*) FROM item WHERE status = 'Analyzed' AND categoryId IS NULL AND categorySetByUser = 0")
    abstract fun observeUncategorizedCount(): Flow<Int>

    /**
     * Applies one sorted chunk. Each assignment's category is found by exact name among the existing
     * categories, else taken from [newCategories] and inserted then: a new category no applied
     * assignment uses is never written. The item gets the categoryId and status Sorted (and the
     * friendly name unless the user chose it), and its FTS row is rewritten.
     *
     * Only an item that is still uncategorized and whose category the user never set is assigned; any
     * other is skipped, never overwritten (the user or another run filed it meanwhile), and so is an
     * item that no longer exists (removed from the project meanwhile). Returns how many items were
     * assigned. A category that is neither existing nor in [newCategories] throws
     * [IllegalArgumentException] and nothing is kept.
     */
    @Transaction
    open suspend fun applyChunk(newCategories: List<CategoryEntity>, assignments: List<Assignment>): Int {
        val offered = newCategories.associateBy { it.name }
        var applied = 0
        for (a in assignments) {
            if (!itemExists(a.itemId)) continue
            val existingId = categoryIdByName(a.categoryName)
            val offer = offered[a.categoryName]
            require(existingId != null || offer != null) { "No category named '${a.categoryName}' for item ${a.itemId}" }
            if (!isAssignable(a.itemId)) continue
            val categoryId = existingId ?: insertCategory(offer!!.copy(id = 0, createdBy = "model", folderName = null))
            check(assignItem(a.itemId, categoryId, a.friendlyName) == 1) { "Item ${a.itemId} was not assigned" }
            deleteFtsRow(a.itemId)
            rebuildFtsRow(a.itemId)
            applied++
        }
        return applied
    }

    @Insert
    abstract suspend fun startRun(run: SortRunEntity): Long

    @Query(
        "UPDATE sort_run SET finishedAt = :finishedAt, sorted = :sorted, newCategories = :newCategories, " +
            "outcome = :outcome, message = :message WHERE id = :id",
    )
    abstract suspend fun finishRun(
        id: Long,
        finishedAt: Long,
        sorted: Int,
        newCategories: Int,
        outcome: String,
        message: String?,
    )

    @Query("SELECT * FROM sort_run WHERE id = :id")
    abstract suspend fun run(id: Long): SortRunEntity?

    /** The last 20 finished runs, newest first, for the notice shown after a cold launch. */
    @Query("SELECT * FROM sort_run WHERE finishedAt IS NOT NULL ORDER BY id DESC LIMIT 20")
    abstract fun observeRecentRuns(): Flow<List<SortRunEntity>>

    /**
     * Closes the run rows a process death left open (no [SortRunEntity.finishedAt]) as `partial` with
     * [message]. Only called when no run is going. Returns how many were closed.
     */
    @Query("UPDATE sort_run SET finishedAt = :finishedAt, outcome = 'partial', message = :message WHERE finishedAt IS NULL")
    abstract suspend fun closeOpenRuns(finishedAt: Long, message: String): Int

    @Query(
        "SELECT item.id AS id, item.kind AS kind, analysis.description AS description, " +
            "analysis.actionsJson AS actionsJson, analysis.suggestedName AS suggestedName, " +
            "item.nameSetByUser AS nameSetByUser FROM item INNER JOIN analysis ON analysis.itemId = item.id " +
            "WHERE item.status = 'Analyzed' AND item.categoryId IS NULL AND item.categorySetByUser = 0 ORDER BY item.id",
    )
    protected abstract suspend fun uncategorizedRows(): List<UncategorizedRow>

    @Query(
        "SELECT item_tag.itemId AS itemId, tag.name AS name FROM item_tag " +
            "INNER JOIN tag ON tag.id = item_tag.tagId INNER JOIN item ON item.id = item_tag.itemId " +
            "WHERE item.status = 'Analyzed' AND item.categoryId IS NULL AND item.categorySetByUser = 0 " +
            "ORDER BY item_tag.itemId, tag.name",
    )
    protected abstract suspend fun uncategorizedTags(): List<ItemTagName>

    @Query("SELECT id FROM category WHERE name = :name ORDER BY id LIMIT 1")
    protected abstract suspend fun categoryIdByName(name: String): Long?

    @Insert
    protected abstract suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM item WHERE id = :itemId)")
    protected abstract suspend fun itemExists(itemId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM item WHERE id = :itemId AND categorySetByUser = 0 AND categoryId IS NULL)")
    protected abstract suspend fun isAssignable(itemId: Long): Boolean

    /** Guarded like [isAssignable], so it can never overwrite a category. */
    @Query(
        "UPDATE item SET categoryId = :categoryId, status = 'Sorted', " +
            "friendlyName = CASE WHEN nameSetByUser THEN friendlyName ELSE :friendlyName END " +
            "WHERE id = :itemId AND categorySetByUser = 0 AND categoryId IS NULL",
    )
    protected abstract suspend fun assignItem(itemId: Long, categoryId: Long, friendlyName: String): Int

    @SkipQueryVerification
    @Query("DELETE FROM item_fts WHERE rowid = :itemId")
    protected abstract suspend fun deleteFtsRow(itemId: Long)

    @SkipQueryVerification
    @Query(FTS_REBUILD_ROW)
    protected abstract suspend fun rebuildFtsRow(itemId: Long)
}
