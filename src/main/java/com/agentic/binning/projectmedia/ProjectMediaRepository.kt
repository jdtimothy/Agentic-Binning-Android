package com.agentic.binning.projectmedia

import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.MediaGridRow
import com.agentic.binning.db.ProjectDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Project Media grid, chips, and edits. Every edit is one transaction that refreshes the FTS row of
 * each changed item (left-join rebuild, so a set-by-hand item with no analysis is still searchable).
 *
 * [onRetryFailed] is how a Retry lifts Stop and starts analysis (P6), wired by AppGraph; tests leave
 * it as a no-op.
 */
class ProjectMediaRepository(
    private val db: ProjectDatabase,
    private val onRetryFailed: suspend () -> Unit = {},
) {
    fun observeRows(filter: MediaFilter, ftsQuery: String?): Flow<List<MediaRow>> = flow {
        db.media().observeGrid().collect { grid ->
            emit(rowsFrom(grid, filter, ftsQuery))
        }
    }

    fun observeCounts(): Flow<MediaCounts> = db.media().observeGrid().map { grid ->
        mediaCountsOf(grid, { it.status }, ::waiting)
    }

    fun observeChips(filter: MediaFilter): Flow<List<ChipCount>> = flow {
        db.media().observeGrid().collect { grid ->
            val base = applyFilter(grid, filter.copy(chip = MediaChip.All))
            val chips = mutableListOf<ChipCount>()
            chips += ChipCount(MediaChip.All, "All", base.size)
            val waitingN = base.count { waiting(it) }
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

    suspend fun saveItem(
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
        val nameSet = if (unlockName) false else true
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

    suspend fun moveItems(ids: List<Long>, category: CategoryChoice): EditResult {
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
            val status = if (item.status == ItemStatus.Analyzed || item.status == ItemStatus.Sorted) ItemStatus.Sorted else item.status
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

    suspend fun removeItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.media().deleteFtsRows(ids)
        db.media().deleteItems(ids)
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

    suspend fun retryFailed(id: Long): EditResult {
        val item = db.media().item(id) ?: return EditResult.NotFound
        if (item.status != ItemStatus.Failed) return EditResult.NotFound
        db.media().retryFailed(id)
        db.meta().setStoppedByUser(false)
        onRetryFailed()
        return EditResult.Ok
    }

    suspend fun setByHand(id: Long, name: String, category: CategoryChoice): EditResult {
        val item = db.media().item(id) ?: return EditResult.NotFound
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
        val narrowed = applyFilter(grid, filter)
        if (ftsQuery == null) return narrowed.map { it.toRow() }
        if (narrowed.isEmpty()) return emptyList()
        val hits = db.analyses().search(ftsQuery).toSet()
        val shown = narrowed.filter { it.id in hits }
        val matched = matchedIn(shown.map { it.id }, ftsQuery)
        return shown.map { it.toRow(matched[it.id]) }
    }

    private fun waiting(row: MediaGridRow): Boolean = isWaiting(row.status, row.categoryId, row.categorySetByUser)

    private fun applyFilter(grid: List<MediaGridRow>, filter: MediaFilter): List<MediaGridRow> =
        grid.filter { row ->
            when (filter.chip) {
                MediaChip.All -> true
                MediaChip.Waiting -> waiting(row)
                MediaChip.Failed -> row.status == ItemStatus.Failed
                is MediaChip.Category -> row.categoryId == filter.chip.id
            } &&
                (filter.kind == null || row.kind == filter.kind) &&
                (filter.shotType == null || row.shotType == filter.shotType) &&
                (filter.takenFrom == null || (row.takenAt != null && row.takenAt >= filter.takenFrom)) &&
                (filter.takenTo == null || (row.takenAt != null && row.takenAt <= filter.takenTo))
        }

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

    private fun MediaGridRow.toRow(matchedIn: MatchedIn? = null) = MediaRow(
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
        matchedIn = matchedIn,
    )
}
