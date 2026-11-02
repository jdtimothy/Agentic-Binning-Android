package com.agentic.binning.projectmedia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentic.binning.db.AnalysisEntity
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.ProjectDatabase
import com.agentic.binning.db.ProjectMetaEntity
import com.agentic.binning.db.PROJECT_DB_VERSION
import com.agentic.binning.db.openProjectDatabase
import com.agentic.binning.search.FtsQuery
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plan 2b Task 7: Project Media edits and FTS. Fixtures in cacheDir; never a user project.
 *
 * `adb shell am instrument -w -e class com.agentic.binning.projectmedia.ProjectMediaRepositoryTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner`
 */
class ProjectMediaRepositoryTest {
    private lateinit var dbFile: File
    private lateinit var db: ProjectDatabase
    private lateinit var repo: ProjectMediaRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File(context.cacheDir, "media-repo-${System.nanoTime()}.db")
        db = openProjectDatabase(context, dbFile)
        repo = ProjectMediaRepository(db)
    }

    @After fun tearDown() {
        db.close()
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbFile.name) }?.forEach { it.delete() }
    }

    private suspend fun photo(
        hash: String,
        status: ItemStatus = ItemStatus.Analyzed,
        description: String = "A sea lion on rocks",
        tags: List<String> = listOf("sea lion"),
        shotType: String = "wide",
        takenAt: Long? = 1_000L,
        kind: ItemKind = ItemKind.Photo,
        friendlyName: String? = null,
        nameSetByUser: Boolean = false,
        categoryId: Long? = null,
        categorySetByUser: Boolean = false,
        attempts: Int = 0,
        failureReason: String? = null,
    ): Long {
        val id = db.items().insert(
            ItemEntity(
                contentHash = hash,
                originalPath = "/x/$hash.jpg",
                originalName = "$hash.jpg",
                currentPath = "/x/$hash.jpg",
                kind = kind,
                takenAt = takenAt,
                durationMs = if (kind == ItemKind.Video) 3_000 else null,
                width = 10,
                height = 20,
                status = ItemStatus.Pending,
                categoryId = categoryId,
                friendlyName = friendlyName,
                nameSetByUser = nameSetByUser,
                categorySetByUser = categorySetByUser,
                addedAt = 1L,
                failureReason = failureReason,
                attempts = attempts,
            ),
        )
        when (status) {
            ItemStatus.Analyzed, ItemStatus.Sorted -> {
                db.analyses().saveAnalysis(
                    AnalysisEntity(
                        itemId = id,
                        description = description,
                        actionsJson = "[\"resting\"]",
                        suggestedName = "Sea Lion",
                        shotType = shotType,
                        rawJson = "{}",
                        modelId = "m",
                        promptVersion = "1",
                        stopReason = "eos",
                        ttftMs = 1.0,
                        generatedTokens = 9,
                        analyzedAt = 7L,
                    ),
                    tags,
                )
                if (status == ItemStatus.Sorted || categoryId != null) {
                    // saveAnalysis may set Analyzed; category is applied via repo edits in tests.
                }
            }
            else -> db.items().setStatus(id, status, failureReason)
        }
        return id
    }

    private suspend fun rows(filter: MediaFilter = MediaFilter(), query: String? = null) =
        repo.observeRows(filter, query?.let { FtsQuery.from(it) }).first()

    @Test fun rename_refreshesFtsRow() = runTest {
        val id = photo("h1")
        assertEquals(EditResult.Ok, repo.saveItem(id, name = "Harbor Seal", category = null, unlockName = false, unlockCategory = false))
        assertEquals(listOf(id), rows(query = "Harbor").map { it.id })
        assertEquals("Harbor Seal", db.items().observeAll().first().single().friendlyName)
        assertTrue(db.items().observeAll().first().single().nameSetByUser)
    }

    @Test fun remove_deletesFtsAndTagRows() = runTest {
        val id = photo("h2", tags = listOf("rocks", "sea lion"))
        assertEquals(listOf(id), db.analyses().search("lion"))
        repo.removeItems(listOf(id))
        assertEquals(emptyList<Long>(), db.analyses().search("lion"))
        assertEquals(0, db.items().observeAll().first().size)
        assertEquals(emptyList<String>(), db.analyses().tagsFor(id))
    }

    @Test fun moveItems_setsCategoryLock() = runTest {
        val id = photo("h3")
        val created = repo.ensureCategory("Beach")
        assertTrue(created is EditResult.Created)
        val catId = (created as EditResult.Created).id
        assertEquals(EditResult.Ok, repo.moveItems(listOf(id), CategoryChoice.Existing(catId)))
        val item = db.items().observeAll().first().single()
        assertEquals(catId, item.categoryId)
        assertTrue(item.categorySetByUser)
        assertEquals(ItemStatus.Sorted, item.status)
    }

    @Test fun newCategory_clashIsRejected() = runTest {
        assertTrue(repo.ensureCategory("Beach") is EditResult.Created)
        val clash = repo.ensureCategory("the Beach!")
        assertTrue(clash is EditResult.Clash)
        assertEquals("Beach", (clash as EditResult.Clash).existing)
    }

    @Test fun merge_movesItemsAndDropsTheOther() = runTest {
        val keep = (repo.ensureCategory("Keep") as EditResult.Created).id
        val drop = (repo.ensureCategory("Drop") as EditResult.Created).id
        val a = photo("ha")
        val b = photo("hb")
        repo.moveItems(listOf(a), CategoryChoice.Existing(keep))
        repo.moveItems(listOf(b), CategoryChoice.Existing(drop))
        assertEquals(EditResult.Ok, repo.mergeCategories(keep = keep, drop = drop))
        val items = db.items().observeAll().first().associateBy { it.id }
        assertEquals(keep, items.getValue(a).categoryId)
        assertEquals(keep, items.getValue(b).categoryId)
        assertTrue(repo.observeChips(MediaFilter()).first().none { it.chip == MediaChip.Category(drop) })
    }

    @Test fun renameCategory_updatesFtsCategoryColumn() = runTest {
        val cat = (repo.ensureCategory("Old Name") as EditResult.Created).id
        val id = photo("hn")
        repo.moveItems(listOf(id), CategoryChoice.Existing(cat))
        assertEquals(EditResult.Ok, repo.renameCategory(cat, "New Name"))
        assertEquals(listOf(id), rows(query = "New").map { it.id })
        assertEquals(MatchedIn.Category, rows(query = "New").single().matchedIn)
    }

    @Test fun setByHand_withoutAnalysis_isFoundByName() = runTest {
        val id = photo("hf", status = ItemStatus.Failed, failureReason = "decode")
        val cat = (repo.ensureCategory("Hand") as EditResult.Created).id
        assertEquals(EditResult.Ok, repo.setByHand(id, name = "By Hand Shot", category = CategoryChoice.Existing(cat)))
        val item = db.items().observeAll().first().single()
        assertEquals(ItemStatus.Sorted, item.status)
        assertTrue(item.nameSetByUser && item.categorySetByUser)
        assertEquals(listOf(id), rows(query = "Hand Shot").map { it.id })
    }

    @Test fun unlockCategory_keepsTheItemSorted() = runTest {
        val cat = (repo.ensureCategory("Locked") as EditResult.Created).id
        val id = photo("hu")
        repo.saveItem(id, name = "Named", category = CategoryChoice.Existing(cat), unlockName = false, unlockCategory = false)
        assertEquals(EditResult.Ok, repo.saveItem(id, name = "Named", category = CategoryChoice.Existing(cat), unlockName = false, unlockCategory = true))
        val item = db.items().observeAll().first().single()
        assertEquals(cat, item.categoryId)
        assertFalse(item.categorySetByUser)
        assertEquals(ItemStatus.Sorted, item.status)
    }

    @Test fun search_withFtsSyntax_doesNotThrow() = runTest {
        photo("hs", description = "turtles on the beach")
        val inputs = listOf(
            "", "   ", " - * ( \" ", "turt", "sea turt", "sea turtle ",
            "AND", "sea OR", "-x", "a:b", "(x", "NEAR(",
            "say \"hi\"", "turtle\"", "sea - turtle", "mama's", "café hoʻokipa",
            (1..30).joinToString(" ") { "w$it" },
        )
        for (raw in inputs) {
            rows(query = raw)
        }
    }

    @Test fun filters_kindShotDate() = runTest {
        val photoId = photo("fp", kind = ItemKind.Photo, shotType = "wide", takenAt = 100)
        val videoId = photo("fv", kind = ItemKind.Video, shotType = "close", takenAt = 200, description = "A clip of bars")
        assertEquals(listOf(photoId), rows(MediaFilter(kind = ItemKind.Photo)).map { it.id })
        assertEquals(listOf(videoId), rows(MediaFilter(shotType = "close")).map { it.id })
        assertEquals(listOf(videoId), rows(MediaFilter(takenFrom = 150)).map { it.id })
        assertEquals(listOf(photoId), rows(MediaFilter(takenTo = 150)).map { it.id })
    }

    @Test fun retryFailed_resetsAttemptsAndReason() = runTest {
        db.meta().insert(ProjectMetaEntity(name = "T", createdAt = 1L, schemaVersion = PROJECT_DB_VERSION, stoppedByUser = true))
        val id = photo("fr", status = ItemStatus.Failed, failureReason = "decode", attempts = 2)
        // photo() then setStatus Failed may not keep attempts; set via retry path after a raw update
        assertEquals(EditResult.Ok, repo.retryFailed(id))
        val item = db.items().observeAll().first().single()
        assertEquals(ItemStatus.Pending, item.status)
        assertEquals(0, item.attempts)
        assertEquals(null, item.failureReason)
        assertEquals(false, db.meta().isStoppedByUser())
    }
}
