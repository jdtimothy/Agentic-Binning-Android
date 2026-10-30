package com.agentic.binning.db

import androidx.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

class ProjectDatabaseTest {
    private lateinit var dbFile: File
    private lateinit var db: ProjectDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File(context.cacheDir, "project-test-${System.nanoTime()}.db")
        db = openProjectDatabase(context, dbFile)
    }

    @After fun tearDown() {
        db.close()
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbFile.name) }?.forEach { it.delete() }
    }

    private fun item(
        hash: String,
        path: String,
        status: ItemStatus = ItemStatus.Pending,
    ) = ItemEntity(
        contentHash = hash,
        originalPath = path,
        originalName = path.substringAfterLast('/'),
        currentPath = path,
        kind = ItemKind.Video,
        takenAt = null,
        durationMs = null,
        width = null,
        height = null,
        status = status,
        addedAt = 1L,
    )

    private fun analysis(itemId: Long, description: String) = AnalysisEntity(
        itemId = itemId,
        description = description,
        actionsJson = "[]",
        suggestedName = "name",
        shotType = "wide",
        rawJson = "{}",
        modelId = "m",
        promptVersion = "1",
        stopReason = "end",
        ttftMs = 1.0,
        generatedTokens = 1,
        analyzedAt = 1L,
    )

    @Test fun fts5_isAvailable_andSearchFindsAnalyzedItem() = runTest {
        val id = db.items().insert(item(hash = "h1", path = "/x/a.mp4"))
        db.analyses().saveAnalysis(analysis(id, description = "Sea lions resting on flat rocks"), listOf("sea lions", "rocks"))
        assertEquals(listOf(id), db.analyses().search("lion*"))
        assertEquals(listOf(id), db.analyses().search("rocks"))
        assertEquals(ItemStatus.Analyzed, db.items().findByHashes(listOf("h1")).single().status)
    }

    @Test fun duplicateHash_isRejected() = runTest {
        db.items().insert(item(hash = "h1", path = "/x/a.mp4"))
        assertThrows(SQLiteException::class.java) { runBlocking { db.items().insert(item(hash = "h1", path = "/x/b.mp4")) } }
    }

    @Test fun resetStatus_movesAnalyzingBackToPending() = runTest {
        val id = db.items().insert(item(hash = "h2", path = "/x/c.jpg", status = ItemStatus.Analyzing))
        assertEquals(1, db.items().resetStatus(ItemStatus.Analyzing, ItemStatus.Pending))
        assertEquals(ItemStatus.Pending, db.items().nextPending()!!.status)
    }

    @Test fun resave_normalizesTags_andReplacesFtsRow() = runTest {
        val id = db.items().insert(item(hash = "h3", path = "/x/d.mp4"))
        db.analyses().saveAnalysis(analysis(id, description = "A heron wading"), listOf("  Heron ", "Water"))
        assertEquals(listOf("heron", "water"), db.analyses().tagsFor(id))
        db.analyses().saveAnalysis(analysis(id, description = "A surfer on a wave"), listOf("surf"))
        assertEquals(listOf("surf"), db.analyses().tagsFor(id))
        assertEquals(emptyList<Long>(), db.analyses().search("heron"))
        assertEquals(listOf(id), db.analyses().search("surfers"))
    }

    /** I2: each start is counted; a clean stop clears the count. */
    @Test fun markAnalyzing_countsAttempts_andReturnToPendingClearsThem() = runTest {
        val id = db.items().insert(item(hash = "h4", path = "/x/e.jpg"))
        db.items().markAnalyzing(id)
        db.items().markAnalyzing(id)
        val analyzing = db.items().findByHashes(listOf("h4")).single()
        assertEquals(ItemStatus.Analyzing, analyzing.status)
        assertEquals(2, analyzing.attempts)

        db.items().returnToPending(id)
        val pending = db.items().findByHashes(listOf("h4")).single()
        assertEquals(ItemStatus.Pending, pending.status)
        assertEquals(0, pending.attempts)
    }

    /** I2: the startup reset fails an item the process died on twice and retries the others. */
    @Test fun recoverInterrupted_failsRepeatOffenders_andResetsTheRest() = runTest {
        val twice = db.items().insert(item(hash = "h5", path = "/x/f.jpg"))
        val once = db.items().insert(item(hash = "h6", path = "/x/g.jpg"))
        val done = db.items().insert(item(hash = "h7", path = "/x/h.jpg", status = ItemStatus.Analyzed))
        repeat(2) { db.items().markAnalyzing(twice) }
        db.items().markAnalyzing(once)

        assertEquals(2, db.items().recoverInterrupted(failAfter = 2, reason = "interrupted"))

        val byHash = db.items().findByHashes(listOf("h5", "h6", "h7")).associateBy { it.contentHash }
        assertEquals(ItemStatus.Failed, byHash.getValue("h5").status)
        assertEquals("interrupted", byHash.getValue("h5").failureReason)
        assertEquals(ItemStatus.Pending, byHash.getValue("h6").status)
        assertEquals(ItemStatus.Analyzed, byHash.getValue("h7").status)
        assertEquals(listOf(twice, once, done).size, 3)
    }

    /** M3: the Stop flag lives in project_meta. */
    @Test fun stoppedByUser_roundTrips() = runTest {
        db.meta().insert(ProjectMetaEntity(name = "Trip", createdAt = 1L, schemaVersion = PROJECT_DB_VERSION))
        assertEquals(false, db.meta().isStoppedByUser())
        db.meta().setStoppedByUser(true)
        assertEquals(true, db.meta().isStoppedByUser())
        db.meta().setStoppedByUser(false)
        assertEquals(false, db.meta().isStoppedByUser())
    }
}
