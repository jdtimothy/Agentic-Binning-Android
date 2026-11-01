package com.agentic.binning.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [SortDao.applyChunk]'s own logic on the JVM, over an in-memory stand-in for its queries (the SQL
 * itself is covered by the device test `SortDaoTest`).
 */
class SortDaoApplyChunkTest {
    private class Row(var categoryId: Long? = null, var categorySetByUser: Boolean = false, var friendlyName: String? = null)

    private class InMemorySortDao : SortDao() {
        val items = LinkedHashMap<Long, Row>()
        val categories = ArrayList<CategoryEntity>()

        override suspend fun categories(): List<CategoryInfo> =
            categories.map { c -> CategoryInfo(c.id, c.name, c.description, c.createdBy, items.values.count { it.categoryId == c.id }) }

        override fun observeCategories(): Flow<List<CategoryEntity>> = flowOf(categories)
        override fun observeUncategorizedCount(): Flow<Int> = flowOf(0)
        override suspend fun startRun(run: SortRunEntity): Long = error("unused")
        override suspend fun finishRun(id: Long, finishedAt: Long, sorted: Int, newCategories: Int, outcome: String, message: String?) = error("unused")
        override suspend fun run(id: Long): SortRunEntity? = error("unused")
        override suspend fun closeOpenRuns(finishedAt: Long, message: String): Int = error("unused")
        override fun observeRecentRuns(): Flow<List<SortRunEntity>> = error("unused")
        override suspend fun uncategorizedRows(): List<UncategorizedRow> = error("unused")
        override suspend fun uncategorizedTags(): List<ItemTagName> = error("unused")
        override suspend fun categoryIdByName(name: String): Long? = categories.firstOrNull { it.name == name }?.id
        override suspend fun insertCategory(category: CategoryEntity): Long {
            val id = categories.size + 1L
            categories += category.copy(id = id)
            return id
        }
        override suspend fun itemExists(itemId: Long): Boolean = itemId in items
        override suspend fun isAssignable(itemId: Long): Boolean = items[itemId]?.let { !it.categorySetByUser && it.categoryId == null } == true
        override suspend fun assignItem(itemId: Long, categoryId: Long, friendlyName: String): Int {
            val row = items[itemId]?.takeIf { !it.categorySetByUser && it.categoryId == null } ?: return 0
            row.categoryId = categoryId
            row.friendlyName = friendlyName
            return 1
        }
        override suspend fun deleteFtsRow(itemId: Long) = Unit
        override suspend fun rebuildFtsRow(itemId: Long) = Unit
    }

    private fun newCategory(name: String) = CategoryEntity(name = name, description = "$name things", createdBy = "model", folderName = null)

    /** M6: an item removed meanwhile is skipped; the rest of the paid chunk is still written. */
    @Test fun applyChunk_skipsAnItemThatNoLongerExists() = runTest {
        val dao = InMemorySortDao().apply { items[1] = Row(); items[2] = Row() }

        val applied = dao.applyChunk(
            listOf(newCategory("Beach")),
            listOf(Assignment(1, "Beach", "One"), Assignment(99, "Beach", "Gone"), Assignment(2, "Beach", "Two")),
        )

        assertEquals(2, applied)
        assertEquals(listOf("Beach"), dao.categories.map { it.name })
        assertEquals(1L, dao.items.getValue(1).categoryId)
        assertEquals(1L, dao.items.getValue(2).categoryId)
    }

    @Test fun applyChunk_onlyARemovedItemUsesACategory_itIsNotWritten() = runTest {
        val dao = InMemorySortDao().apply { items[1] = Row() }

        val applied = dao.applyChunk(listOf(newCategory("Ghosts")), listOf(Assignment(99, "Ghosts", "Gone")))

        assertEquals(0, applied)
        assertEquals(emptyList<CategoryEntity>(), dao.categories)
        assertNull(dao.items.getValue(1).categoryId)
    }

    @Test fun applyChunk_unknownCategory_stillThrows() = runTest {
        val dao = InMemorySortDao().apply { items[1] = Row() }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.applyChunk(emptyList(), listOf(Assignment(1, "Nope", "One"))) }
        }
    }
}
