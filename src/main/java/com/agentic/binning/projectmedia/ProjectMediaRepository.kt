package com.agentic.binning.projectmedia

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.MediaGridRow
import com.agentic.binning.db.ProjectDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Project Media grid, chips, and edits. Every edit is one transaction that refreshes the FTS row of
 * each changed item (left-join rebuild, so a set-by-hand item with no analysis is still searchable).
 *
 * [onRetryFailed] is how a Retry lifts Stop and starts analysis (P6), wired by AppGraph; tests leave
 * it as a no-op.
 *
 * An edit that changes where a binned file belongs writes its move plan in the same transaction, and
 * the plan runs after the commit ([saveMoves], Plan 3 Task 14).
 */
class ProjectMediaRepository(
    private val db: ProjectDatabase,
    private val onRetryFailed: suspend () -> Unit = {},
    /** The project's folder: a removed item whose file is inside it is remembered (Q2); null knows only Binned. */
    private val projectDir: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How a Save moves binned files; null changes only the database. */
    private val saveMoves: SaveMoveHooks? = null,
) {
    /**
     * Runs [edit] in one transaction with the move plan for the items it reports. An edit refused because
     * a run is in progress rolls back with its plan, so nothing is half done and nothing is queued. So
     * does one whose plan fails: planning reads the other listed projects, and any of them may be unreadable.
     */
    private suspend fun withMoves(edit: suspend (affected: MutableCollection<Long>) -> EditResult): EditResult {
        var runId: Long? = null
        val outcome: EditResult = try {
            db.useWriterConnection { connection ->
                // The bundled-driver way to a transaction; DAO calls inside it use this connection.
                connection.immediateTransaction<EditResult> {
                    val affected = ArrayList<Long>()
                    val result = edit(affected)
                    if (saveMoves != null && result == EditResult.Ok && affected.isNotEmpty()) {
                        runId = try {
                            saveMoves.plan(affected)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "A Save's move plan failed", e)
                            throw MovesRefused(PLAN_FAILED)
                        }
                        if (runId != null) saveMoves.blockedReason()?.let { throw MovesRefused(it) }
                    }
                    result
                }
            }
        } catch (e: MovesRefused) {
            return EditResult.Busy(e.reason)
        }
        runId?.let { saveMoves?.started(it) }
        return outcome
    }

    private class MovesRefused(val reason: String) : Exception()

    fun observeRows(filter: MediaFilter, ftsQuery: String?): Flow<List<MediaRow>> = flow {
        db.media().observeGrid().collect { grid ->
            emit(rowsFrom(grid, filter, ftsQuery))
        }
    }

    fun observeCounts(): Flow<MediaCounts> = db.media().observeGrid().map { grid ->
        mediaCountsOf(grid, { it.status }, ::waiting)
    }

    /** Whether the last run's folder check found something: Bin Files then stays on to show it. */
    fun observeFolderCheckFailed(): Flow<Boolean> = db.moves().observeLastCheckOk().map { it == false }

    /** Every category in id order, for the chips row (its lock marks the user's). */
    fun observeCategories(): Flow<List<CategoryEntity>> = db.sort().observeCategories()

    fun observeChips(filter: MediaFilter): Flow<List<ChipCount>> = flow {
        db.media().observeGrid().collect { grid ->
            val base = grid.map { it.toRow() }.filter(filter.copy(chip = MediaChip.All)::matches)
            val chips = mutableListOf<ChipCount>()
            chips += ChipCount(MediaChip.All, "All", base.size)
            val waitingN = base.count { isWaiting(it.status, it.categoryId, it.categorySetByUser) }
            if (waitingN > 0) chips += ChipCount(MediaChip.Waiting, "Waiting", waitingN)
            val failedN = base.count { it.status == ItemStatus.Failed }
            if (failedN > 0) chips += ChipCount(MediaChip.Failed, "Failed", failedN)
            val byCat = base.filter { it.categoryId != null }.groupBy { it.categoryId!! }
            val names = db.media().categories().associate { it.id to it.name }
            for ((id, rows) in byCat) {
                chips += ChipCount(MediaChip.Category(id), names[id] ?: "", rows.size)
            }
            emit(chips)
        }
    }

    suspend fun ensureCategory(name: String): EditResult {
        val clean = CategoryNames.clean(name)
        if (clean.isEmpty()) return EditResult.InvalidName
        val existing = db.media().categories()
        CategoryNames.clash(clean, existing.map { it.name })?.let { return EditResult.Clash(it) }
        val id = db.media().insertCategory(CategoryEntity(name = clean, description = "", createdBy = "user", folderName = null))
        return EditResult.Created(id)
    }

    /** The detail sheet's item, analysis, tags and category; null once the item is gone. */
    suspend fun detail(id: Long): ItemDetail? {
        val item = db.media().item(id) ?: return null
        val analysis = db.analyses().get(id)
        val category = item.categoryId?.let { db.media().category(it) }
        val actions = analysis?.actionsJson?.let { json ->
            // A malformed list (an old or hand-made row) shows no actions rather than failing the sheet.
            runCatching { DetailJson.decodeFromString(ListSerializer(ActionEntry.serializer()), json) }.getOrDefault(emptyList())
        }.orEmpty()
        return ItemDetail(
            item = item,
            description = analysis?.description,
            actions = actions,
            tags = if (analysis != null) db.analyses().tagsFor(id) else emptyList(),
            suggestedName = analysis?.suggestedName,
            shotType = analysis?.shotType,
            categoryName = category?.name,
            categoryDescription = category?.description,
            categoryCreatedBy = category?.createdBy,
        )
    }

    /**
     * Saves the detail sheet in one transaction. The name is locked only when it changed (Q5), unless
     * [unlockName]; a chosen category is locked unless [unlockCategory], which keeps the category (P2).
     */
    suspend fun saveItem(
        id: Long,
        name: String,
        category: CategoryChoice?,
        unlockName: Boolean,
        unlockCategory: Boolean,
    ): EditResult = withMoves { affected ->
        affected += id
        saveItemLocked(id, name, category, unlockName, unlockCategory)
    }

    private suspend fun saveItemLocked(
        id: Long,
        name: String,
        category: CategoryChoice?,
        unlockName: Boolean,
        unlockCategory: Boolean,
    ): EditResult {
        val item = db.media().item(id) ?: return EditResult.NotFound
        val cat = when (category) {
            null -> item.categoryId to item.categorySetByUser
            is CategoryChoice.Existing -> {
                if (db.media().category(category.id) == null) return EditResult.NotFound
                category.id to true
            }
            is CategoryChoice.New -> when (val created = ensureCategory(category.name)) {
                is EditResult.Created -> created.id to true
                else -> return created
            }
        }
        var categoryId = cat.first
        var categorySet = cat.second
        if (unlockCategory) {
            categorySet = false
            // keep categoryId (P2 / Task 6.4)
        }
        val nameSet = when {
            unlockName -> false
            name != item.friendlyName -> true
            else -> item.nameSetByUser
        }
        val status = when {
            categoryId != null && item.status == ItemStatus.Analyzed -> ItemStatus.Sorted
            categoryId != null && item.status == ItemStatus.Sorted -> ItemStatus.Sorted
            else -> item.status
        }
        db.media().updateItemEdit(
            id = id,
            name = name,
            nameSetByUser = nameSet,
            categoryId = categoryId,
            categorySetByUser = categorySet,
            status = status,
        )
        db.media().refreshFts(id)
        return EditResult.Ok
    }

    /**
     * Bulk Move to category, in one transaction: each analyzed item gets [category], locked as the
     * user's. Items not analyzed yet (pending, failed, missing) are left as they are: a category on
     * them would leave them neither waiting nor ready to bin.
     */
    suspend fun moveItems(ids: List<Long>, category: CategoryChoice): EditResult = withMoves { affected ->
        affected += ids
        moveItemsLocked(ids, category)
    }

    private suspend fun moveItemsLocked(ids: List<Long>, category: CategoryChoice): EditResult {
        val categoryId = when (category) {
            is CategoryChoice.Existing -> {
                if (db.media().category(category.id) == null) return EditResult.NotFound
                category.id
            }
            is CategoryChoice.New -> when (val created = ensureCategory(category.name)) {
                is EditResult.Created -> created.id
                else -> return created
            }
        }
        for (id in ids) {
            val item = db.media().item(id) ?: continue
            if (!canTakeACategory(item.status)) continue
            val status = if (item.status == ItemStatus.Analyzed) ItemStatus.Sorted else item.status
            db.media().updateItemEdit(
                id = id,
                name = item.friendlyName,
                nameSetByUser = item.nameSetByUser,
                categoryId = categoryId,
                categorySetByUser = true,
                status = status,
            )
            db.media().refreshFts(id)
        }
        return EditResult.Ok
    }

    /**
     * Remove from project (Q1): the rows go, the files stay. A file left in the project's folders (a
     * binned item's, or one a Re-sort moved out of binned) is remembered in `removed_file`, so the
     * folder check never stops on it (Q2). One transaction.
     */
    suspend fun removeItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                val kept = db.media().removalRows(ids).filter { keepsAFileInTheProject(it.status, it.currentPath) }.map { it.currentPath }
                if (kept.isNotEmpty()) db.moves().addRemoved(kept, REMOVED, clock())
                db.media().deleteFtsRows(ids)
                db.media().deleteItems(ids)
            }
        }
    }

    private fun keepsAFileInTheProject(status: ItemStatus, path: String): Boolean {
        if (status == ItemStatus.Binned) return true
        val root = projectDir?.trimEnd('/') ?: return false
        return path.startsWith("$root/") && !path.startsWith("$root/.binning/")
    }

    suspend fun renameCategory(id: Long, name: String): EditResult {
        val current = db.media().category(id) ?: return EditResult.NotFound
        val clean = CategoryNames.clean(name)
        if (clean.isEmpty()) return EditResult.InvalidName
        CategoryNames.clash(clean, db.media().categories().map { it.name }, except = current.name)
            ?.let { return EditResult.Clash(it) }
        db.media().renameCategory(id, clean)
        db.media().itemIdsInCategory(id).forEach { db.media().refreshFts(it) }
        return EditResult.Ok
    }

    suspend fun mergeCategories(keep: Long, drop: Long): EditResult {
        if (db.media().category(keep) == null || db.media().category(drop) == null) return EditResult.NotFound
        val affected = (db.media().itemIdsInCategory(keep) + db.media().itemIdsInCategory(drop)).distinct()
        db.media().reassignCategory(drop = drop, keep = keep)
        db.media().markCategoryUser(keep)
        db.media().deleteCategory(drop)
        affected.forEach { db.media().refreshFts(it) }
        return EditResult.Ok
    }

    /**
     * The Category Edit sheet's Save (Task 14): every merge ([merges] as keep to drop, in order) and
     * rename ([renames], for categories that survive) in one transaction. The names after Save are
     * checked first, so swapping two names works and a clash changes nothing. Every category touched
     * becomes the user's, and the FTS rows of their items are rebuilt.
     */
    suspend fun applyCategoryEdits(renames: Map<Long, String>, merges: List<Pair<Long, Long>>): EditResult =
        withMoves { affected -> applyCategoryEditsLocked(renames, merges, affected) }

    private suspend fun applyCategoryEditsLocked(
        renames: Map<Long, String>,
        merges: List<Pair<Long, Long>>,
        affected: MutableCollection<Long>,
    ): EditResult {
        val cats = db.media().categories().associateBy { it.id }
        if (merges.any { (keep, drop) -> keep == drop || keep !in cats || drop !in cats }) return EditResult.NotFound
        val dropped = merges.mapTo(HashSet()) { it.second }
        val cleaned = renames.filterKeys { it !in dropped }.mapValues { CategoryNames.clean(it.value) }
        if (cleaned.keys.any { it !in cats }) return EditResult.NotFound
        if (cleaned.values.any { it.isEmpty() }) return EditResult.InvalidName
        val finalNames = cats.values.filter { it.id !in dropped }.map { cleaned[it.id] ?: it.name }
        finalNames.groupBy(CategoryNames::key).values.firstOrNull { it.size > 1 }?.let { same ->
            // Name the one that was already there, as a single rename's clash does.
            return EditResult.Clash(same.firstOrNull { it !in cleaned.values } ?: same.first())
        }
        val touched = LinkedHashSet<Long>()
        for ((keep, drop) in merges) {
            db.media().reassignCategory(drop = drop, keep = keep)
            db.media().markCategoryUser(keep)
            db.media().deleteCategory(drop)
            touched -= drop
            touched += keep
        }
        for ((id, name) in cleaned) {
            db.media().renameCategory(id, name)
            touched += id
        }
        val items = touched.flatMap { db.media().itemIdsInCategory(it) }.distinct()
        items.forEach { db.media().refreshFts(it) }
        affected += items
        return EditResult.Ok
    }

    /**
     * Retry analysis (Q19): the item back to Pending with its attempts reset and a remembered Stop
     * lifted, in one transaction; analysis is started only after it commits (P6).
     */
    suspend fun retryFailed(id: Long): EditResult {
        val result = db.useWriterConnection { connection ->
            connection.immediateTransaction {
                val item = db.media().item(id)
                if (item == null || item.status != ItemStatus.Failed) {
                    EditResult.NotFound
                } else {
                    db.media().retryFailed(id)
                    db.meta().setStoppedByUser(false)
                    EditResult.Ok
                }
            }
        }
        if (result == EditResult.Ok) onRetryFailed()
        return result
    }

    /** A failed item's name and category set by hand, both locked (Q19), in one transaction. */
    suspend fun setByHand(id: Long, name: String, category: CategoryChoice): EditResult = db.useWriterConnection { connection ->
        connection.immediateTransaction { setByHandLocked(id, name, category) }
    }

    private suspend fun setByHandLocked(id: Long, name: String, category: CategoryChoice): EditResult {
        db.media().item(id) ?: return EditResult.NotFound
        val categoryId = when (category) {
            is CategoryChoice.Existing -> {
                if (db.media().category(category.id) == null) return EditResult.NotFound
                category.id
            }
            is CategoryChoice.New -> when (val created = ensureCategory(category.name)) {
                is EditResult.Created -> created.id
                else -> return created
            }
        }
        db.media().updateItemEdit(
            id = id,
            name = name,
            nameSetByUser = true,
            categoryId = categoryId,
            categorySetByUser = true,
            status = ItemStatus.Sorted,
        )
        db.media().refreshFts(id)
        return EditResult.Ok
    }

    private suspend fun rowsFrom(grid: List<MediaGridRow>, filter: MediaFilter, ftsQuery: String?): List<MediaRow> {
        val narrowed = grid.map { it.toRow() }.filter(filter::matches)
        if (ftsQuery == null) return narrowed
        if (narrowed.isEmpty()) return emptyList()
        val hits = db.analyses().search(ftsQuery).toSet()
        val shown = narrowed.filter { it.id in hits }
        val matched = matchedIn(shown.map { it.id }, ftsQuery)
        return shown.map { it.copy(matchedIn = matched[it.id]) }
    }

    private fun waiting(row: MediaGridRow): Boolean = isWaiting(row.status, row.categoryId, row.categorySetByUser)

    private suspend fun matchedIn(ids: List<Long>, ftsQuery: String): Map<Long, MatchedIn> {
        if (ids.isEmpty()) return emptyMap()
        val order = listOf(
            MatchedIn.Name to "friendly_name : $ftsQuery",
            MatchedIn.Category to "category : $ftsQuery",
            MatchedIn.Tags to "tags : $ftsQuery",
            MatchedIn.Actions to "actions : $ftsQuery",
            MatchedIn.Description to "description : $ftsQuery",
        )
        val found = mutableMapOf<Long, MatchedIn>()
        for ((field, q) in order) {
            val hits = try {
                db.media().ftsHits(ids, q)
            } catch (_: Exception) {
                emptyList()
            }
            for (id in hits) if (id !in found) found[id] = field
        }
        return found
    }

    private fun MediaGridRow.toRow() = MediaRow(
        id = id,
        kind = kind,
        durationMs = durationMs,
        takenAt = takenAt,
        status = status,
        categoryId = categoryId,
        friendlyName = friendlyName,
        suggestedName = suggestedName,
        shotType = shotType,
        nameSetByUser = nameSetByUser,
        categorySetByUser = categorySetByUser,
        currentPath = currentPath,
        hasAnalysis = hasAnalysis,
    )
}

private val DetailJson = Json { ignoreUnknownKeys = true }

private const val TAG = "ProjectMedia"
private const val PLAN_FAILED = "Couldn't work out the file moves, so nothing was saved"

/** How a Save moves binned files: wired by AppGraph over the move engine and the foreground service. */
interface SaveMoveHooks {
    /** Why files can't move now (a run is in progress); null when they can. */
    fun blockedReason(): String?

    /** Writes the plan for [ids], inside the edit's transaction; null when no file has to move. */
    suspend fun plan(ids: Collection<Long>): Long?

    /** Runs the plan, after the edit has committed. */
    suspend fun started(runId: Long)
}

/** `removed_file.reason` for an item removed from the project (Q2); B5's Leave them writes `left`. */
const val REMOVED = "removed"
