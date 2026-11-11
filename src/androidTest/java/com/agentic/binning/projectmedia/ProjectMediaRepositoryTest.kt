package com.agentic.binning.projectmedia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentic.binning.db.AnalysisEntity
import com.agentic.binning.db.CategoryEntity
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

    /** Plan 3 Q2: a removed binned item's file stays, and its path is remembered for the folder check. Not yet run on the phone. */
    // ---------------------------------------------------------------- Save on binned files (Plan 3 Task 14)

    private class Hooks(val runId: Long? = 7, val blocked: String? = null) : SaveMoveHooks {
        val planned = ArrayList<Long>()
        val asked = ArrayList<List<Long>>()
        override fun blockedReason() = blocked
        override suspend fun plan(ids: Collection<Long>): Long? {
            asked += ids.toList()
            return runId
        }
        override suspend fun started(runId: Long) { planned += runId }
    }

    private suspend fun binnedItem(name: String): Long {
        val category = db.media().insertCategory(CategoryEntity(name = "Beach $name", description = "", createdBy = "model", folderName = null))
        val id = photo(name, friendlyName = name)
        db.media().updateItemEdit(id, name, nameSetByUser = false, categoryId = category, categorySetByUser = false, status = ItemStatus.Binned)
        return id
    }

    @Test fun saveRename_binnedItem_startsItsPlanAfterTheEditCommits() = runTest {
        val hooks = Hooks()
        val repo = ProjectMediaRepository(db, saveMoves = hooks)
        val id = binnedItem("a")

        assertEquals(EditResult.Ok, repo.saveItem(id, "New name", null, unlockName = false, unlockCategory = false))

        assertEquals("New name", db.media().item(id)?.friendlyName)
        assertEquals(listOf(listOf(id)), hooks.asked)
        assertEquals(listOf(7L), hooks.planned)
    }

    @Test fun save_withNothingToMove_startsNothing() = runTest {
        val hooks = Hooks(runId = null)
        val repo = ProjectMediaRepository(db, saveMoves = hooks)
        val id = binnedItem("a")
        assertEquals(EditResult.Ok, repo.saveItem(id, "New name", null, unlockName = false, unlockCategory = false))
        assertTrue(hooks.planned.isEmpty())
    }

    /** Refused while a run is in progress, never queued: the edit rolls back with its plan. */
    @Test fun save_refusedWhileBusy_rollsTheEditBack() = runTest {
        val hooks = Hooks(blocked = "Busy")
        val repo = ProjectMediaRepository(db, saveMoves = hooks)
        val id = binnedItem("a")

        assertEquals(EditResult.Busy("Busy"), repo.saveItem(id, "New name", null, unlockName = false, unlockCategory = false))

        assertEquals("a", db.media().item(id)?.friendlyName)
        assertTrue(hooks.planned.isEmpty())
    }

    /**
     * A failure writing the plan takes the edit with it: never a name with no plan to match. It is refused,
     * not thrown: planning reads the other listed projects, and one unreadable project mustn't crash a Save.
     */
    @Test fun save_planFails_rollsTheEditBack() = runTest {
        val repo = ProjectMediaRepository(db, saveMoves = object : SaveMoveHooks {
            override fun blockedReason() = null
            override suspend fun plan(ids: Collection<Long>): Long? = throw java.io.IOException("disk")
            override suspend fun started(runId: Long) = Unit
        })
        val id = binnedItem("a")
        assertEquals(
            EditResult.Busy("Couldn't work out the file moves, so nothing was saved"),
            repo.saveItem(id, "New name", null, unlockName = false, unlockCategory = false),
        )
        assertEquals("a", db.media().item(id)?.friendlyName)
    }

    @Test fun moveItems_binnedItem_startsItsPlan() = runTest {
        val hooks = Hooks()
        val repo = ProjectMediaRepository(db, saveMoves = hooks)
        val id = binnedItem("a")
        val other = db.media().insertCategory(CategoryEntity(name = "Other", description = "", createdBy = "model", folderName = null))

        assertEquals(EditResult.Ok, repo.moveItems(listOf(id), CategoryChoice.Existing(other)))

        assertEquals(other, db.media().item(id)?.categoryId)
        assertEquals(listOf(listOf(id)), hooks.asked)
        assertEquals(listOf(7L), hooks.planned)
    }

    @Test fun renameCategory_startsAPlanForEveryItemInIt() = runTest {
        val hooks = Hooks()
        val repo = ProjectMediaRepository(db, saveMoves = hooks)
        val id = binnedItem("a")
        val category = db.media().item(id)!!.categoryId!!

        assertEquals(EditResult.Ok, repo.applyCategoryEdits(mapOf(category to "Coast"), emptyList()))

        assertEquals(listOf(listOf(id)), hooks.asked)
        assertEquals(listOf(7L), hooks.planned)
    }

    @Test fun merge_startsAPlanForTheDroppedCategorysItems() = runTest {
        val hooks = Hooks()
        val repo = ProjectMediaRepository(db, saveMoves = hooks)
        val a = binnedItem("a")
        val b = binnedItem("b")
        val keep = db.media().item(a)!!.categoryId!!
        val drop = db.media().item(b)!!.categoryId!!

        assertEquals(EditResult.Ok, repo.applyCategoryEdits(emptyMap(), listOf(keep to drop)))

        assertEquals(keep, db.media().item(b)?.categoryId)
        assertEquals(setOf(a, b), hooks.asked.single().toSet())
    }

    @Test fun remove_binnedItem_recordsItsPath() = runTest {
        val id = photo("hb")
        db.items().setStatus(id, ItemStatus.Binned)
        repo.removeItems(listOf(id))
        assertEquals(listOf("/x/hb.jpg"), db.moves().removedPaths())
    }

    /** Plan 3 Q2: an item outside the project folder leaves no record. Not yet run on the phone. */
    @Test fun remove_unbinnedItem_recordsNothing() = runTest {
        val id = photo("hu")
        ProjectMediaRepository(db, projectDir = "/p").removeItems(listOf(id))
        assertEquals(emptyList<String>(), db.moves().removedPaths())
    }

    /** Plan 3 Q2/B10: an item inside the project folder but no longer binned (a Re-sort moved it) is recorded too. Not yet run on the phone. */
    @Test fun remove_itemInsideTheProjectFolder_recordsItsPath() = runTest {
        val id = photo("hi")
        ProjectMediaRepository(db, projectDir = "/x").removeItems(listOf(id))
        assertEquals(listOf("/x/hi.jpg"), db.moves().removedPaths())
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

    @Test fun saveItem_categoryOnly_leavesTheNameUnlocked() = runTest {
        val id = photo("n1", friendlyName = "Sea Lion")
        val cat = (repo.ensureCategory("Wildlife") as EditResult.Created).id

        assertEquals(EditResult.Ok, repo.saveItem(id, name = "Sea Lion", category = CategoryChoice.Existing(cat), unlockName = false, unlockCategory = false))

        val item = db.items().observeAll().first().single()
        // Q5: only what the user set is locked; the untouched name stays the model's.
        assertFalse(item.nameSetByUser)
        assertTrue(item.categorySetByUser)
        assertEquals(cat, item.categoryId)
        assertEquals(ItemStatus.Sorted, item.status)
    }

    @Test fun detail_readsAnalysisTagsAndCategory() = runTest {
        val id = photo("d1", tags = listOf("rocks", "sea lion"), friendlyName = "Sea Lion")
        val cat = (repo.ensureCategory("Wildlife") as EditResult.Created).id
        repo.moveItems(listOf(id), CategoryChoice.Existing(cat))

        val d = repo.detail(id)!!

        assertEquals("A sea lion on rocks", d.description)
        assertEquals(listOf("rocks", "sea lion"), d.tags.sorted())
        assertEquals("Sea Lion", d.suggestedName)
        assertEquals("wide", d.shotType)
        assertEquals("Wildlife", d.categoryName)
        assertEquals("user", d.categoryCreatedBy)
        assertEquals("d1.jpg", d.item.originalName)
        // The fixture's actions are not ActionEntry JSON: read as none, not a crash.
        assertEquals(emptyList<Any>(), d.actions)
        assertEquals(null, repo.detail(id + 99))
    }

    @Test fun moveItems_skipsItemsNotAnalyzedYet() = runTest {
        val analyzed = photo("m1")
        val pending = photo("m2", status = ItemStatus.Pending)
        val failed = photo("m3", status = ItemStatus.Failed, failureReason = "decode")
        val cat = (repo.ensureCategory("Wildlife") as EditResult.Created).id

        assertEquals(EditResult.Ok, repo.moveItems(listOf(analyzed, pending, failed), CategoryChoice.Existing(cat)))

        val items = db.items().observeAll().first().associateBy { it.id }
        assertEquals(cat, items.getValue(analyzed).categoryId)
        assertEquals(ItemStatus.Sorted, items.getValue(analyzed).status)
        // A category on an item with no analysis would leave it neither waiting nor ready to bin.
        assertEquals(null, items.getValue(pending).categoryId)
        assertFalse(items.getValue(pending).categorySetByUser)
        assertEquals(ItemStatus.Pending, items.getValue(pending).status)
        assertEquals(null, items.getValue(failed).categoryId)
        assertEquals(ItemStatus.Failed, items.getValue(failed).status)
    }

    private suspend fun category(name: String, vararg items: Long): Long {
        val id = (repo.ensureCategory(name) as EditResult.Created).id
        if (items.isNotEmpty()) repo.moveItems(items.toList(), CategoryChoice.Existing(id))
        return id
    }

    @Test fun applyCategoryEdits_renamesAndMergesInOneSave() = runTest {
        val a = photo("c1")
        val b = photo("c2")
        val c = photo("c3")
        val turtle = category("Turtle Beach", a)
        val scenes = category("Beach Scenes", b)
        val luau = category("Luau", c)

        assertEquals(EditResult.Ok, repo.applyCategoryEdits(renames = mapOf(luau to "Fire Show"), merges = listOf(scenes to turtle)))

        val cats = db.media().categories().associateBy { it.id }
        assertEquals(setOf(scenes, luau), cats.keys)
        assertEquals("Fire Show", cats.getValue(luau).name)
        assertEquals("user", cats.getValue(luau).createdBy)
        assertEquals("user", cats.getValue(scenes).createdBy)
        val items = db.items().observeAll().first().associateBy { it.id }
        assertEquals(scenes, items.getValue(a).categoryId)
        assertEquals(scenes, items.getValue(b).categoryId)
        // FTS follows: the new name is found, the old one is not.
        assertEquals(listOf(c), rows(query = "Fire Show").map { it.id })
        assertEquals(emptyList<Long>(), rows(query = "Luau").map { it.id })
        assertEquals(listOf(a, b), rows(query = "Beach Scenes").map { it.id }.sorted())
    }

    /** A model category as sorting writes it: with the model's description of the old name. */
    private suspend fun modelCategory(name: String, description: String): Long =
        db.media().insertCategory(CategoryEntity(name = name, description = description, createdBy = "model", folderName = null))

    /**
     * A renamed category drops the model's description: it describes the old name (Transport renamed
     * Vehicles kept "Transport", and a Re-sort made a new Transport from it).
     */
    @Test fun renameCategory_clearsTheModelsDescription() = runTest {
        val cat = modelCategory("Transport", "Transport")
        assertEquals(EditResult.Ok, repo.renameCategory(cat, "Vehicles"))
        val renamed = db.media().category(cat)!!
        assertEquals("Vehicles", renamed.name)
        assertEquals("", renamed.description)
    }

    @Test fun applyCategoryEdits_renameClearsTheDescription_mergeKeepsTheKeptOnes() = runTest {
        val transport = modelCategory("Transport", "Transport")
        val nature = modelCategory("Nature", "Outdoor scenes")
        val sports = modelCategory("Sports", "Ball games")
        assertEquals(
            EditResult.Ok,
            repo.applyCategoryEdits(renames = mapOf(transport to "Vehicles"), merges = listOf(nature to sports)),
        )
        assertEquals("", db.media().category(transport)!!.description)
        assertEquals("Outdoor scenes", db.media().category(nature)!!.description)
    }

    @Test fun applyCategoryEdits_swapsTwoNames() = runTest {
        val x = category("Reef")
        val y = category("Lagoon")

        assertEquals(EditResult.Ok, repo.applyCategoryEdits(renames = mapOf(x to "Lagoon", y to "Reef"), merges = emptyList()))

        val cats = db.media().categories().associate { it.id to it.name }
        assertEquals("Lagoon", cats.getValue(x))
        assertEquals("Reef", cats.getValue(y))
    }

    @Test fun applyCategoryEdits_clashWithAnUnchangedName_changesNothing() = runTest {
        val a = photo("c4")
        val x = category("Reef", a)
        val y = category("Lagoon")
        val z = category("Shore")

        val result = repo.applyCategoryEdits(renames = mapOf(x to "lagoons"), merges = listOf(y to z))

        assertEquals(EditResult.Clash("Lagoon"), result)
        val cats = db.media().categories().associate { it.id to it.name }
        // The merge staged with it did not happen either.
        assertEquals(mapOf(x to "Reef", y to "Lagoon", z to "Shore"), cats)
        assertEquals(x, db.items().observeAll().first().single().categoryId)
    }
}
