package com.agentic.binning.project

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.agentic.binning.db.AnalysisEntity
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.PROJECT_DB_IDENTITY_HASHES
import com.agentic.binning.db.PROJECT_DB_VERSION
import com.agentic.binning.db.ProjectDatabase
import com.agentic.binning.db.ProjectMetaEntity
import com.agentic.binning.db.openProjectDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Plan 2b Task 6A on the phone: the read-only probe against real SQLite files, reopening a saved
 * project untouched, the backup before an older schema is upgraded, and removal leaving every file in
 * place on shared storage. Fixtures live under the app's external files folder and are deleted
 * afterwards; no user project and no real `projects.json` is touched (these tests never build
 * `AppGraph`, which would use the app's own registry).
 *
 * Run by direct instrumentation (plan "Device tests"):
 * `adb shell am instrument -w -e class com.agentic.binning.project.ProjectOpenRemoveTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner`
 */
class ProjectOpenRemoveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = File(checkNotNull(context.getExternalFilesDir(null)), "task6a-${System.nanoTime()}")
    private val projectDir = File(base, "Fixture Project")
    private val dbFile = ProjectFolders.databaseFile(projectDir)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = dbFile.also { it.parentFile?.mkdirs() },
        driver = BundledSQLiteDriver(),
        databaseClass = ProjectDatabase::class,
    )

    @After fun tearDown() {
        base.deleteRecursively()
    }

    private fun decide(facts: DatabaseFacts) = ProjectOpenRules.decide(facts, PROJECT_DB_VERSION, PROJECT_DB_IDENTITY_HASHES)

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Relative path -> size and SHA-256 of every file under [dir]. */
    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to "${it.length()}:${sha(it)}" }

    /** A current-schema project with one analyzed item, its tags and FTS row, and a remembered Stop. */
    private suspend fun makeSavedProject(): Long {
        val db = openProjectDatabase(context, dbFile)
        try {
            db.meta().insert(ProjectMetaEntity(name = "Fixture Trip", createdAt = 123L, schemaVersion = PROJECT_DB_VERSION))
            val id = db.items().insert(
                ItemEntity(
                    contentHash = "h1", originalPath = "/x/lion.jpg", originalName = "lion.jpg", currentPath = "/x/lion.jpg",
                    kind = ItemKind.Photo, takenAt = null, durationMs = null, width = 10, height = 20,
                    status = ItemStatus.Pending, addedAt = 1L,
                ),
            )
            db.analyses().saveAnalysis(
                AnalysisEntity(
                    itemId = id, description = "A sea lion resting on rocks", actionsJson = "[]", suggestedName = "Sea Lion",
                    shotType = "wide", rawJson = "{}", modelId = "m", promptVersion = "1", stopReason = "eos",
                    ttftMs = 1.0, generatedTokens = 9, analyzedAt = 7L,
                ),
                listOf("sea lion", "rocks"),
            )
            db.meta().setStoppedByUser(true)
            return id
        } finally {
            db.close()
        }
    }

    @Test fun probe_missingDatabase_createsNothing() {
        projectDir.mkdirs()
        File(projectDir, "beach.jpg").writeText("not really a photo")
        File(projectDir, ".binning").deleteRecursively()
        val facts = ProjectDatabaseProbe.probe(dbFile)
        assertEquals(DatabaseDecision.Refused(OpenRefusal.NoProject), decide(facts))
        assertFalse("no .binning folder is created", File(projectDir, ".binning").exists())
    }

    @Test fun openExisting_reopensSavedAnalysis() = runTest {
        val id = makeSavedProject()
        val before = sha(dbFile)

        val facts = ProjectDatabaseProbe.probe(dbFile)
        assertEquals("the probe never writes", before, sha(dbFile))
        assertEquals(DatabaseDecision.Open(backupFromVersion = null), decide(facts))
        assertEquals("Fixture Trip", ProjectOpenRules.listedName(facts, projectDir))

        val db = openProjectDatabase(context, dbFile)
        try {
            val meta = checkNotNull(db.meta().get())
            assertEquals(123L, meta.createdAt)
            assertEquals("Fixture Trip", meta.name)
            assertEquals(true, db.meta().isStoppedByUser())
            assertEquals(1, db.items().countByStatus(ItemStatus.Analyzed))
            assertEquals("A sea lion resting on rocks", db.analyses().get(id)?.description)
            assertEquals(listOf("rocks", "sea lion"), db.analyses().tagsFor(id))
            assertEquals(listOf(id), db.analyses().search("lion"))
        } finally {
            db.close()
        }
    }

    @Test fun openExisting_newerVersion_refusedAndByteIdentical() = runTest {
        makeSavedProject()
        BundledSQLiteDriver().open(dbFile.absolutePath).use { it.execSQL("PRAGMA user_version = 99") }
        val before = snapshot(projectDir)
        assertEquals(DatabaseDecision.Refused(OpenRefusal.Newer), decide(ProjectDatabaseProbe.probe(dbFile)))
        assertEquals(before, snapshot(projectDir))
    }

    @Test fun openExisting_invalidFile_refusedAndByteIdentical() {
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("this is not a database")
        val before = snapshot(projectDir)
        assertEquals(DatabaseDecision.Refused(OpenRefusal.CantOpen), decide(ProjectDatabaseProbe.probe(dbFile)))
        assertEquals(before, snapshot(projectDir))
    }

    @Test fun openExisting_foreignDatabase_refusedAndByteIdentical() {
        dbFile.parentFile?.mkdirs()
        BundledSQLiteDriver().open(dbFile.absolutePath).use { c ->
            c.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
            c.execSQL("PRAGMA user_version = $PROJECT_DB_VERSION")
        }
        val before = snapshot(projectDir)
        val facts = ProjectDatabaseProbe.probe(dbFile)
        assertTrue(facts.readable)
        assertEquals(DatabaseDecision.Refused(OpenRefusal.CantOpen), decide(facts))
        assertEquals(before, snapshot(projectDir))
    }

    @Test fun openExisting_noMetaRow_refused() = runTest {
        // Room is lazy: build()+close() does not create the file. A real query does, with no meta row.
        val db = openProjectDatabase(context, dbFile)
        try {
            assertEquals(null, db.meta().get())
        } finally {
            db.close()
        }
        assertTrue(dbFile.isFile)
        assertEquals(DatabaseDecision.Refused(OpenRefusal.CantOpen), decide(ProjectDatabaseProbe.probe(dbFile)))
    }

    @Test fun openExisting_olderVersion_backsUpThenMigrates() = runTest {
        helper.createDatabase(2).apply {
            execSQL("INSERT INTO project_meta (id, name, createdAt, schemaVersion, stoppedByUser) VALUES (1, 'Old Trip', 4, 2, 0)")
            close()
        }
        val original = sha(dbFile)

        val decision = decide(ProjectDatabaseProbe.probe(dbFile))
        assertEquals(DatabaseDecision.Open(backupFromVersion = 2), decision)
        val backup = ProjectOpenRules.backupFile(dbFile, 2)

        val db = openProjectDatabase(context, dbFile)
        try {
            assertEquals("Old Trip", db.meta().get()?.name)
        } finally {
            db.close()
        }
        assertEquals("the backup is the untouched v2 file", original, sha(backup))
        assertEquals(DatabaseDecision.Open(backupFromVersion = null), decide(ProjectDatabaseProbe.probe(dbFile)))
    }

    @Test fun registry_sdcardAndStorageSpellings_areOneEntry() {
        projectDir.mkdirs()
        val storagePath = projectDir.absolutePath
        val sdcardPath = storagePath.replaceFirst("/storage/emulated/0/", "/sdcard/")
        val registry = ProjectRegistry(File(base, "projects.json"), base)
        registry.setCurrent(ProjectRef("Fixture Trip", storagePath))
        registry.setCurrent(ProjectRef("Same folder", sdcardPath))
        assertEquals(1, registry.list().size)
        assertEquals("Fixture Trip", registry.current()?.name)
    }

    @Test fun remove_preservesFiles() = runTest {
        makeSavedProject()
        File(projectDir, "Beach/sea lion.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(4096) { it.toByte() }) }
        File(ProjectFolders.thumbsDir(projectDir), "1.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(512) { 3 }) }
        val registry = ProjectRegistry(File(base, "projects.json"), base)
        registry.setCurrent(ProjectRef("Fixture Trip", projectDir.absolutePath))
        val before = snapshot(projectDir)

        val removed = registry.remove(projectDir.absolutePath)

        assertEquals(true, removed?.wasCurrent)
        assertEquals(emptyList<ProjectRef>(), registry.list())
        assertEquals(before, snapshot(projectDir))
    }

    @Test fun remove_missingFolder_doesNotRecreateIt() {
        val gone = File(base, "Gone Project")
        val registry = ProjectRegistry(File(base, "projects.json"), base)
        registry.setCurrent(ProjectRef("Gone", gone.absolutePath))
        assertEquals(0, registry.remove(gone.absolutePath)?.index)
        assertFalse(gone.exists())
    }
}

