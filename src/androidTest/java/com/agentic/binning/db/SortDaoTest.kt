package com.agentic.binning.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SortDaoTest {
    private lateinit var dbFile: File
    private lateinit var db: ProjectDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File(context.cacheDir, "sort-test-${System.nanoTime()}.db")
        db = openProjectDatabase(context, dbFile)
    }

    @After fun tearDown() {
        db.close()
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbFile.name) }?.forEach { it.delete() }
    }

    private suspend fun addItem(
        hash: String,
        status: ItemStatus = ItemStatus.Analyzed,
        description: String = "Waves on sand",
        tags: List<String> = listOf("waves"),
        nameSetByUser: Boolean = false,
        friendlyName: String? = null,
    ): Long {
        val id = db.items().insert(
            ItemEntity(
                contentHash = hash,
                originalPath = "/x/$hash.jpg",
                originalName = "$hash.jpg",
                currentPath = "/x/$hash.jpg",
                kind = ItemKind.Photo,
                takenAt = null,
                durationMs = null,
                width = null,
                height = null,
                status = ItemStatus.Pending,
                addedAt = 1L,
                nameSetByUser = nameSetByUser,
                friendlyName = friendlyName,
            ),
        )
        if (status == ItemStatus.Analyzed) {
            db.analyses().saveAnalysis(analysis(id, description), tags)
        } else {
            db.items().setStatus(id, status)
        }
        return id
    }

    private fun analysis(itemId: Long, description: String) = AnalysisEntity(
        itemId = itemId,
        description = description,
        actionsJson = "[\"walking\"]",
        suggestedName = "Suggested $itemId",
        shotType = "wide",
        rawJson = "{}",
        modelId = "m",
        promptVersion = "1",
        stopReason = "eos",
        ttftMs = 1.0,
        generatedTokens = 1,
        analyzedAt = 1L,
    )

    private suspend fun item(id: Long) = db.items().observeAll().first().single { it.id == id }

    private fun newCategory(name: String) =
        CategoryEntity(name = name, description = "$name things", createdBy = "model", folderName = null)

    @Test fun applyChunk_assignsAndRefreshesFts() = runTest {
        val id = addItem("a")

        db.sort().applyChunk(
            listOf(newCategory("Beach")),
            listOf(Assignment(id, "Beach", "Waves at noon")),
        )

        val saved = item(id)
        assertEquals(ItemStatus.Sorted, saved.status)
        assertEquals("Waves at noon", saved.friendlyName)
        assertNotNull(saved.categoryId)
        assertEquals(listOf(id), db.analyses().search("category:beach"))
        assertEquals(listOf(id), db.analyses().search("beach"))
        assertEquals(listOf(id), db.analyses().search("friendly_name:noon"))
        // The analysis text is still searchable.
        assertEquals(listOf(id), db.analyses().search("sand"))
        assertEquals(listOf(id), db.analyses().search("waves"))
        val created = db.sort().categories().single()
        assertEquals("Beach", created.name)
        assertEquals("model", created.createdBy)
        assertEquals(1, created.items)
    }

    @Test fun applyChunk_keepsUserName() = runTest {
        val id = addItem("a", nameSetByUser = true, friendlyName = "My sunset")

        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(id, "Beach", "Model name")))

        val saved = item(id)
        assertEquals("My sunset", saved.friendlyName)
        assertEquals(ItemStatus.Sorted, saved.status)
        assertEquals(listOf(id), db.analyses().search("friendly_name:sunset"))
        assertEquals(emptyList<Long>(), db.analyses().search("friendly_name:model"))
        assertEquals(listOf(id), db.analyses().search("category:beach"))
    }

    @Test fun applyChunk_usesExistingCategories() = runTest {
        val first = addItem("a")
        val second = addItem("b")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(first, "Beach", "One")))
        db.sort().applyChunk(emptyList(), listOf(Assignment(second, "Beach", "Two")))

        assertEquals(1, db.sort().categories().size)
        assertEquals(2, db.sort().categories().single().items)
    }

    @Test fun applyChunk_isAllOrNothing() = runTest {
        val a = addItem("a")
        val b = addItem("b")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                db.sort().applyChunk(
                    listOf(newCategory("Beach")),
                    listOf(Assignment(a, "Beach", "One"), Assignment(b, "Nope", "Two")),
                )
            }
        }

        assertEquals(emptyList<CategoryInfo>(), db.sort().categories())
        assertEquals(ItemStatus.Analyzed, item(a).status)
        assertNull(item(a).categoryId)
        assertNull(item(a).friendlyName)
        assertEquals(emptyList<Long>(), db.analyses().search("category:beach"))
        assertEquals(2, db.sort().uncategorized().size)
    }

    @Test fun uncategorized_excludesSortedAndFailed() = runTest {
        val analyzed = addItem("a", tags = listOf("waves", "sand"))
        val sorted = addItem("b")
        addItem("c", status = ItemStatus.Failed)
        addItem("d", status = ItemStatus.Pending)
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(sorted, "Beach", "Done")))

        val list = db.sort().uncategorized()

        assertEquals(listOf(analyzed), list.map { it.id })
        val only = list.single()
        assertEquals(ItemKind.Photo, only.kind)
        assertEquals("Waves on sand", only.description)
        assertEquals("[\"walking\"]", only.actionsJson)
        assertEquals("Suggested $analyzed", only.suggestedName)
        assertEquals(listOf("sand", "waves"), only.tags.sorted())
        assertEquals(false, only.nameSetByUser)
        assertEquals(1, db.sort().observeUncategorizedCount().first())
    }

    @Test fun uncategorized_isOrderedById() = runTest {
        val ids = listOf(addItem("a"), addItem("b"), addItem("c"))
        assertEquals(ids, db.sort().uncategorized().map { it.id })
    }

    @Test fun saveAnalysis_keepsFriendlyNameAndCategoryInFts() = runTest {
        val id = addItem("a")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(id, "Beach", "Waves at noon")))

        db.analyses().saveAnalysis(analysis(id, "A heron wading"), listOf("bird"))

        assertEquals(listOf(id), db.analyses().search("category:beach"))
        assertEquals(listOf(id), db.analyses().search("friendly_name:noon"))
        assertEquals(listOf(id), db.analyses().search("heron"))
        assertEquals(emptyList<Long>(), db.analyses().search("sand"))
    }

    @Test fun saveAnalysis_resetsAttempts() = runTest {
        val id = db.items().insert(
            ItemEntity(
                contentHash = "h", originalPath = "/x/h.jpg", originalName = "h.jpg", currentPath = "/x/h.jpg",
                kind = ItemKind.Photo, takenAt = null, durationMs = null, width = null, height = null,
                status = ItemStatus.Pending, addedAt = 1L,
            ),
        )
        repeat(2) { db.items().markAnalyzing(id) }
        assertEquals(2, item(id).attempts)

        db.analyses().saveAnalysis(analysis(id, "Something"), listOf("x"))

        assertEquals(0, item(id).attempts)
        assertEquals(ItemStatus.Analyzed, item(id).status)
    }

    @Test fun applyChunk_writesOnlyTheNewCategoriesItUses() = runTest {
        val id = addItem("a")

        val applied = db.sort().applyChunk(
            listOf(newCategory("Beach"), newCategory("Mountains")),
            listOf(Assignment(id, "Beach", "Waves")),
        )

        assertEquals(1, applied)
        assertEquals(listOf("Beach"), db.sort().categories().map { it.name })
        assertEquals("Beach things", db.sort().categories().single().description)
    }

    @Test fun applyChunk_skipsAnItemCategorizedMeanwhile_neverOverwrites() = runTest {
        val first = addItem("a")
        val second = addItem("b")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(first, "Beach", "One")))

        // A second run (or a stale reply) assigns the same item again.
        val applied = db.sort().applyChunk(
            listOf(newCategory("Food")),
            listOf(Assignment(first, "Food", "Lunch"), Assignment(second, "Food", "Dinner")),
        )

        assertEquals(1, applied)
        val beach = db.sort().categories().single { it.name == "Beach" }
        assertEquals(beach.id, item(first).categoryId)
        assertEquals("One", item(first).friendlyName)
        assertEquals(listOf(first), db.analyses().search("category:beach"))
        assertEquals(listOf(second), db.analyses().search("category:food"))
    }

    @Test fun applyChunk_skipsAnItemWhoseCategoryTheUserSet() = runTest {
        val id = db.items().insert(
            ItemEntity(
                contentHash = "u", originalPath = "/x/u.jpg", originalName = "u.jpg", currentPath = "/x/u.jpg",
                kind = ItemKind.Photo, takenAt = null, durationMs = null, width = null, height = null,
                status = ItemStatus.Pending, addedAt = 1L, categorySetByUser = true,
            ),
        )
        db.analyses().saveAnalysis(analysis(id, "Waves on sand"), listOf("waves"))

        val applied = db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(id, "Beach", "Model name")))

        assertEquals(0, applied)
        assertNull(item(id).categoryId)
        assertNull(item(id).friendlyName)
        assertEquals(ItemStatus.Analyzed, item(id).status)
        // Nothing used the new category, so it was not written.
        assertEquals(emptyList<CategoryInfo>(), db.sort().categories())
    }

    @Test fun applyChunk_canAddItemsToAUserCategory() = runTest {
        val catId = db.media().insertCategory(
            CategoryEntity(name = "Beach", description = "Mine", createdBy = "user", folderName = null),
        )
        val id = addItem("a")

        val applied = db.sort().applyChunk(emptyList(), listOf(Assignment(id, "Beach", "Waves at noon")))

        assertEquals(1, applied)
        val saved = item(id)
        assertEquals(catId, saved.categoryId)
        assertEquals(ItemStatus.Sorted, saved.status)
        assertEquals("Waves at noon", saved.friendlyName)
        val cat = db.sort().categories().single()
        assertEquals("Beach", cat.name)
        assertEquals("user", cat.createdBy)
        assertEquals("Mine", cat.description)
        assertEquals(1, cat.items)
    }

    /** M6: an item removed meanwhile is skipped; the rest of the chunk is written. */
    @Test fun applyChunk_unknownItem_isSkipped() = runTest {
        val a = addItem("a")

        val applied = db.sort().applyChunk(
            listOf(newCategory("Beach")),
            listOf(Assignment(a, "Beach", "One"), Assignment(9999, "Beach", "Ghost")),
        )

        assertEquals(1, applied)
        assertEquals("Beach", db.sort().categories().single().name)
        assertEquals(ItemStatus.Sorted, item(a).status)
    }

    @Test fun saveAnalysis_keepsASortedItemSorted() = runTest {
        val id = addItem("a")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(id, "Beach", "Waves at noon")))
        assertEquals(ItemStatus.Sorted, item(id).status)

        // Re-analysis (e.g. after a prompt change) must not send it back to sorting.
        db.analyses().saveAnalysis(analysis(id, "A heron wading"), listOf("bird"))

        assertEquals(ItemStatus.Sorted, item(id).status)
        assertNotNull(item(id).categoryId)
        assertEquals(emptyList<UncategorizedItem>(), db.sort().uncategorized())
        // An uncategorized item still lands in Analyzed.
        val other = addItem("b")
        assertEquals(ItemStatus.Analyzed, item(other).status)
    }

    @Test fun uncategorized_excludesItemsWhoseCategoryTheUserSet() = runTest {
        val open = addItem("a", tags = listOf("waves"))
        val userSet = db.items().insert(
            ItemEntity(
                contentHash = "u", originalPath = "/x/u.jpg", originalName = "u.jpg", currentPath = "/x/u.jpg",
                kind = ItemKind.Photo, takenAt = null, durationMs = null, width = null, height = null,
                status = ItemStatus.Pending, addedAt = 1L, categorySetByUser = true,
            ),
        )
        db.analyses().saveAnalysis(analysis(userSet, "Kept out by the user"), listOf("kept"))

        val list = db.sort().uncategorized()

        assertEquals(listOf(open), list.map { it.id })
        assertEquals(listOf("waves"), list.single().tags)
        assertEquals(1, db.sort().observeUncategorizedCount().first())
    }

    /** M7: rows a process death left open are closed; finished ones are left alone. */
    @Test fun closeOpenRuns_closesOnlyUnfinishedRows() = runTest {
        fun run(finishedAt: Long?) = SortRunEntity(
            startedAt = 10L, finishedAt = finishedAt, modelId = "m", promptVersion = "1",
            items = 5, sorted = 0, newCategories = 0, outcome = if (finishedAt == null) "partial" else "done", message = null,
        )
        val open = db.sort().startRun(run(null))
        val finished = db.sort().startRun(run(20L))

        assertEquals(1, db.sort().closeOpenRuns(finishedAt = 30L, message = "Interrupted"))

        val closed = db.sort().run(open)!!
        assertEquals(30L, closed.finishedAt)
        assertEquals("partial", closed.outcome)
        assertEquals("Interrupted", closed.message)
        assertEquals(run(20L).copy(id = finished), db.sort().run(finished))
        assertEquals(0, db.sort().closeOpenRuns(finishedAt = 40L, message = "Interrupted"))
    }

    /** M4: finished runs only, newest first. */
    @Test fun observeRecentRuns_finishedNewestFirst() = runTest {
        fun run(finishedAt: Long?) = SortRunEntity(
            startedAt = 10L, finishedAt = finishedAt, modelId = "m", promptVersion = "1",
            items = 5, sorted = 0, newCategories = 0, outcome = "partial", message = null,
        )
        val first = db.sort().startRun(run(20L))
        val second = db.sort().startRun(run(30L))
        db.sort().startRun(run(null))

        assertEquals(listOf(second, first), db.sort().observeRecentRuns().first().map { it.id })
    }

    @Test fun runs_startAndFinish() = runTest {
        val runId = db.sort().startRun(
            SortRunEntity(
                startedAt = 10L, finishedAt = null, modelId = "m", promptVersion = "1",
                items = 5, sorted = 0, newCategories = 0, outcome = "partial", message = null,
            ),
        )
        db.sort().finishRun(runId, finishedAt = 20L, sorted = 4, newCategories = 2, outcome = "done", message = null)

        val run = db.sort().run(runId)!!
        assertEquals(20L, run.finishedAt)
        assertEquals(4, run.sorted)
        assertEquals(2, run.newCategories)
        assertEquals("done", run.outcome)
        assertEquals(5, run.items)
    }

    @Test fun prepareResort_deletesEmptyModelCategoriesAndRefreshesFts() = runTest {
        val a = addItem("a")
        val b = addItem("b")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(a, "Beach", "One")))
        db.sort().applyChunk(listOf(newCategory("Food")), listOf(Assignment(b, "Food", "Two")))
        assertEquals(listOf(a), db.analyses().search("category:beach"))

        val waiting = db.sort().prepareResort(keepEdits = true)

        assertEquals(2, waiting)
        assertEquals(ItemStatus.Analyzed, item(a).status)
        assertNull(item(a).categoryId)
        assertEquals(emptyList<CategoryInfo>(), db.sort().categories())
        assertEquals(emptyList<Long>(), db.analyses().search("category:beach"))
        assertEquals(listOf(a), db.analyses().search("friendly_name:one"))
        assertEquals(2, db.sort().uncategorized().size)
    }

    @Test fun prepareResort_keepEdits_leavesLockedItems() = runTest {
        val locked = addItem("a")
        val open = addItem("b")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(locked, "Beach", "One")))
        db.sort().applyChunk(listOf(newCategory("Food")), listOf(Assignment(open, "Food", "Two")))
        val beachId = item(locked).categoryId!!
        db.media().updateItemEdit(
            locked, "One", nameSetByUser = true, categoryId = beachId,
            categorySetByUser = true, status = ItemStatus.Sorted,
        )

        val waiting = db.sort().prepareResort(keepEdits = true)

        assertEquals(1, waiting)
        assertEquals(beachId, item(locked).categoryId)
        assertEquals(ItemStatus.Sorted, item(locked).status)
        assertTrue(item(locked).categorySetByUser)
        assertEquals(ItemStatus.Analyzed, item(open).status)
        assertEquals("Beach", db.sort().categories().single().name)
    }

    /** Plan 3 B10: Re-sort reaches an unlocked binned item; its file stays put until Bin Files. Not yet run on the phone. */
    @Test fun prepareResort_includesUnlockedBinnedItems() = runTest {
        val binned = addItem("a")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(binned, "Beach", "One")))
        db.items().setStatus(binned, ItemStatus.Binned)

        assertEquals(1, db.sort().prepareResort(keepEdits = true))

        assertEquals(ItemStatus.Analyzed, item(binned).status)
        assertNull(item(binned).categoryId)
        assertEquals("/x/a.jpg", item(binned).currentPath)
        assertEquals(listOf(binned), db.sort().uncategorized().map { it.id })
    }

    /** Plan 3 B10 with Keep my edits: a binned item whose category the user set stays binned. Not yet run on the phone. */
    @Test fun prepareResort_keepEdits_leavesLockedBinnedItems() = runTest {
        val locked = addItem("a")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(locked, "Beach", "One")))
        val beachId = item(locked).categoryId!!
        db.media().updateItemEdit(locked, "One", nameSetByUser = true, categoryId = beachId, categorySetByUser = true, status = ItemStatus.Binned)

        assertEquals(0, db.sort().prepareResort(keepEdits = true))

        assertEquals(ItemStatus.Binned, item(locked).status)
        assertEquals(beachId, item(locked).categoryId)
    }

    @Test fun prepareResort_withoutKeepEdits_clearsLocks() = runTest {
        val locked = addItem("a")
        db.sort().applyChunk(listOf(newCategory("Beach")), listOf(Assignment(locked, "Beach", "One")))
        val beachId = item(locked).categoryId!!
        db.media().updateItemEdit(
            locked, "One", nameSetByUser = true, categoryId = beachId,
            categorySetByUser = true, status = ItemStatus.Sorted,
        )

        val waiting = db.sort().prepareResort(keepEdits = false)

        assertEquals(1, waiting)
        assertFalse(item(locked).nameSetByUser)
        assertFalse(item(locked).categorySetByUser)
        assertEquals(ItemStatus.Analyzed, item(locked).status)
        assertNull(item(locked).categoryId)
        assertEquals(emptyList<CategoryInfo>(), db.sort().categories())
    }

    /** A failed item set by hand (Q19): Sorted with both locks, but no analysis for a sorter to read. */
    private suspend fun setByHand(name: String): Long {
        val id = addItem("hand-$name", status = ItemStatus.Failed)
        val cat = db.media().insertCategory(CategoryEntity(name = "Mine", description = "", createdBy = "user", folderName = null))
        db.media().updateItemEdit(id, name, nameSetByUser = true, categoryId = cat, categorySetByUser = true, status = ItemStatus.Sorted)
        db.media().refreshFts(id)
        return id
    }

    /**
     * Review finding: Re-sort without Keep my edits made a set-by-hand item Analyzed with no category,
     * which no sorter can pick up (no analysis), and dropped its search row. It is left as it is.
     */
    @Test fun prepareResort_withoutKeepEdits_leavesAnItemWithoutAnalysisSorted() = runTest {
        val hand = setByHand("Broken Card")
        val cat = item(hand).categoryId

        val waiting = db.sort().prepareResort(keepEdits = false)

        assertEquals(0, waiting)
        assertEquals(ItemStatus.Sorted, item(hand).status)
        assertEquals(cat, item(hand).categoryId)
        assertEquals(0, db.sort().observeUncategorizedCount().first())
        assertEquals(listOf(hand), db.analyses().search("friendly_name:broken"))
    }

    @Test fun prepareResort_keepEdits_leavesAnUnlockedItemWithoutAnalysisSorted() = runTest {
        val hand = setByHand("Broken Card")
        val cat = item(hand).categoryId!!
        // The user unlocked its category in the detail sheet (P2): the category stays, the lock goes.
        db.media().updateItemEdit(hand, "Broken Card", nameSetByUser = true, categoryId = cat, categorySetByUser = false, status = ItemStatus.Sorted)

        val waiting = db.sort().prepareResort(keepEdits = true)

        assertEquals(0, waiting)
        assertEquals(ItemStatus.Sorted, item(hand).status)
        assertEquals(cat, item(hand).categoryId)
        assertEquals(listOf(hand), db.analyses().search("friendly_name:broken"))
    }
}
