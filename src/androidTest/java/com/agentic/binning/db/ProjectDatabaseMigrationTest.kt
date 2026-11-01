package com.agentic.binning.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The phone already holds v1 project databases with real analyses: every version must open them
 * without losing anything. Schemas come from `schemas/` (androidTest assets).
 */
class ProjectDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File(context.cacheDir, "migration-test-${System.nanoTime()}.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = dbFile,
        driver = BundledSQLiteDriver(),
        databaseClass = ProjectDatabase::class,
    )

    @After fun tearDown() {
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbFile.name) }?.forEach { it.delete() }
    }

    /** A v1 database as the app wrote it: one analyzed item with tags, one pending, the meta row. */
    private fun SQLiteConnection.fillV1() {
        execSQL(
            "INSERT INTO item (id, contentHash, originalPath, originalName, currentPath, kind, takenAt, durationMs, " +
                "width, height, status, categoryId, friendlyName, nameSetByUser, categorySetByUser, addedAt, failureReason) " +
                "VALUES (1, 'h1', '/x/a.jpg', 'a.jpg', '/x/a.jpg', 'Photo', NULL, NULL, 10, 20, 'Analyzed', NULL, NULL, 0, 0, 5, NULL), " +
                "(2, 'h2', '/x/b.mp4', 'b.mp4', '/x/b.mp4', 'Video', NULL, 3000, NULL, NULL, 'Pending', NULL, NULL, 0, 0, 6, NULL)",
        )
        execSQL(
            "INSERT INTO analysis (itemId, description, actionsJson, suggestedName, shotType, rawJson, modelId, " +
                "promptVersion, stopReason, ttftMs, generatedTokens, analyzedAt) " +
                "VALUES (1, 'A pier at dusk', '[]', 'Pier', 'wide', '{}', 'm', '1', 'eos', 1.0, 9, 7)",
        )
        execSQL("INSERT INTO tag (id, name) VALUES (1, 'pier')")
        execSQL("INSERT INTO item_tag (itemId, tagId) VALUES (1, 1)")
        execSQL("INSERT INTO project_meta (id, name, createdAt, schemaVersion) VALUES (1, 'Trip', 4, 1)")
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }

    @Test fun migrate1To2_addsColumns_andKeepsEverything() {
        helper.createDatabase(1).apply {
            fillV1()
            close()
        }

        val db = helper.runMigrationsAndValidate(2)

        assertEquals(2, db.long("SELECT COUNT(*) FROM item"))
        assertEquals(0, db.long("SELECT SUM(attempts) FROM item"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM analysis WHERE description = 'A pier at dusk'"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM item_tag"))
        assertEquals(0, db.long("SELECT stoppedByUser FROM project_meta WHERE id = 1"))
        db.close()
    }

    /** The real open path (openProjectDatabase) migrates a v1 file on its own. */
    @Test fun openProjectDatabase_migratesAV1File() = runTest {
        helper.createDatabase(1).apply {
            fillV1()
            close()
        }

        val db = openProjectDatabase(context, dbFile)
        try {
            assertEquals(1, db.items().countByStatus(ItemStatus.Analyzed))
            assertEquals(1, db.items().countByStatus(ItemStatus.Pending))
            assertEquals("A pier at dusk", db.analyses().get(1)!!.description)
            assertEquals(listOf("pier"), db.analyses().tagsFor(1))
            assertEquals(false, db.meta().isStoppedByUser())
            assertEquals(0, db.items().findByHashes(listOf("h2")).single().attempts)
        } finally {
            db.close()
        }
    }

    /** A v2 database with an FTS row (the FTS5 table is not in Room's schema, so the test creates it). */
    private fun SQLiteConnection.fillV2() {
        fillV1()
        execSQL(
            "CREATE VIRTUAL TABLE item_fts USING fts5(description, actions, tags, friendly_name, category, " +
                "tokenize='porter unicode61')",
        )
        execSQL(
            "INSERT INTO item_fts(rowid, description, actions, tags, friendly_name, category) " +
                "VALUES (1, 'A pier at dusk', '[]', 'pier', '', '')",
        )
    }

    @Test fun migrate2To3_keepsData() {
        helper.createDatabase(2).apply {
            fillV2()
            close()
        }

        val db = helper.runMigrationsAndValidate(3)

        assertEquals(3, db.long("PRAGMA user_version"))
        assertEquals(2, db.long("SELECT COUNT(*) FROM item"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM analysis WHERE description = 'A pier at dusk'"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM item_tag"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM item_fts WHERE item_fts MATCH 'pier'"))
        assertEquals(0, db.long("SELECT COUNT(*) FROM sort_run"))
        db.close()
    }

    /** The real open path opens a v2 file at v3 and the new DAO works on it. */
    @Test fun openProjectDatabase_migratesAV2File() = runTest {
        helper.createDatabase(2).apply {
            fillV2()
            close()
        }

        val db = openProjectDatabase(context, dbFile)
        try {
            assertEquals(listOf(1L), db.analyses().search("pier"))
            assertEquals(listOf(1L), db.sort().uncategorized().map { it.id })
            assertEquals(listOf("pier"), db.sort().uncategorized().single().tags)
            val runId = db.sort().startRun(
                SortRunEntity(
                    startedAt = 1L, finishedAt = null, modelId = "m", promptVersion = "1",
                    items = 1, sorted = 0, newCategories = 0, outcome = "partial", message = null,
                ),
            )
            assertEquals(1L, runId)
        } finally {
            db.close()
        }
    }
}
