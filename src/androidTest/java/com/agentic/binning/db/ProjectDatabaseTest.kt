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
}
