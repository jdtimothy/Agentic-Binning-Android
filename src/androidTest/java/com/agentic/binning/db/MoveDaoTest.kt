package com.agentic.binning.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentic.binning.move.RunState
import com.agentic.binning.move.StepState
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plan 3 Task 4. Written in the cloud; not yet run on the phone. */
class MoveDaoTest {
    private lateinit var dbFile: File
    private lateinit var db: ProjectDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File(context.cacheDir, "move-test-${System.nanoTime()}.db")
        db = openProjectDatabase(context, dbFile)
    }

    @After fun tearDown() {
        db.close()
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbFile.name) }?.forEach { it.delete() }
    }

    private suspend fun addItem(hash: String, path: String = "/sdcard/DCIM/Camera/$hash.jpg", status: ItemStatus = ItemStatus.Sorted): Long =
        db.items().insert(
            ItemEntity(
                contentHash = hash, originalPath = path, originalName = path.substringAfterLast('/'), currentPath = path,
                kind = ItemKind.Photo, takenAt = null, durationMs = null, width = null, height = null,
                status = status, addedAt = 1L,
            ),
        )

    private fun step(itemId: Long, source: String, dest: String, bytes: Long = 10) =
        MoveStepEntity(runId = 0, itemId = itemId, source = source, dest = dest, state = StepState.Planned.name, bytes = bytes)

    private fun run() = MoveRunEntity(kind = "Bin", startedAt = 5L, finishedAt = null, state = RunState.Running.name)

    @Test fun writeRun_isOneTransaction() = runTest {
        val a = addItem("a")
        val b = addItem("b")
        val c = addItem("c", path = "/p/Beach/C.jpg")
        val d = addItem("d")
        val runId = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/Beach/A.jpg"), step(b, "/b", "/p/Beach/B.jpg")), inPlace = listOf(c), missing = listOf(d))

        val steps = db.moves().steps(runId)
        assertEquals(listOf(a, b), steps.map { it.itemId })
        assertTrue(steps.all { it.runId == runId && it.state == "Planned" && it.attempts == 0 })
        assertEquals(ItemStatus.Binned, db.items().findByHashes(listOf("c")).single().status)
        assertEquals(ItemStatus.Missing, db.items().findByHashes(listOf("d")).single().status)
        assertEquals(runId, db.moves().openRun()?.id)
    }

    @Test fun recordMoved_updatesItemAndStepTogether() = runTest {
        val a = addItem("a")
        val runId = db.moves().writeRun(run(), listOf(step(a, "/sdcard/DCIM/Camera/a.jpg", "/p/Beach/A.jpg")), emptyList(), emptyList())
        val stepId = db.moves().steps(runId).single().id

        db.moves().recordMoved(stepId, a, "/p/Beach/A.jpg", "newhash", StepState.Done.name)

        val item = db.items().findByPaths(listOf("/p/Beach/A.jpg")).single()
        assertEquals(ItemStatus.Binned, item.status)
        assertEquals("newhash", item.contentHash)
        assertEquals("/sdcard/DCIM/Camera/a.jpg", item.originalPath)
        assertEquals("Done", db.moves().steps(runId).single().state)
    }

    @Test fun recordMoved_keepsHashOnClash() = runTest {
        val a = addItem("a")
        addItem("taken")
        val runId = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/Beach/A.jpg")), emptyList(), emptyList())
        db.moves().recordMoved(db.moves().steps(runId).single().id, a, "/p/Beach/A.jpg", "taken", StepState.Done.name)
        assertEquals("a", db.items().findByPaths(listOf("/p/Beach/A.jpg")).single().contentHash)
    }

    @Test fun recordMissing_marksItemAndStep() = runTest {
        val a = addItem("a")
        val runId = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/Beach/A.jpg")), emptyList(), emptyList())
        db.moves().recordMissing(db.moves().steps(runId).single().id, a)
        assertEquals(ItemStatus.Missing, db.items().findByHashes(listOf("a")).single().status)
        assertEquals("Missing", db.moves().steps(runId).single().state)
    }

    @Test fun startStep_countsAttempts() = runTest {
        val a = addItem("a")
        val runId = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/Beach/A.jpg")), emptyList(), emptyList())
        val id = db.moves().steps(runId).single().id
        assertEquals(1, db.moves().startStep(id))
        assertEquals(2, db.moves().startStep(id))
        db.moves().setStepDest(id, "/p/Beach/A 2.jpg")
        db.moves().setStepState(id, StepState.Skipped.name, "copy-mismatch")
        val s = db.moves().steps(runId).single()
        assertEquals("/p/Beach/A 2.jpg", s.dest)
        assertEquals("copy-mismatch", s.error)
    }

    @Test fun openRun_findsRunningAndPaused() = runTest {
        val a = addItem("a")
        val first = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/B/A.jpg")), emptyList(), emptyList())
        db.moves().finishRun(first, RunState.Done.name, 9L, null, "Turtle Beach", true)
        assertNull(db.moves().openRun())
        val second = db.moves().writeRun(run(), listOf(step(a, "/p/B/A.jpg", "/p/C/A.jpg")), emptyList(), emptyList())
        db.moves().setRunState(second, RunState.Paused.name, null)
        assertEquals(second, db.moves().openRun()?.id)
        assertEquals("Turtle Beach", db.moves().run(first)?.removedFolders)
        assertEquals(first, db.moves().lastFinishedRun()?.id)
    }

    /** The newest finished run's folder check, which keeps Bin Files on while it found something. */
    @Test fun lastCheckOk_followsTheNewestFinishedRun() = runTest {
        val a = addItem("a")
        assertNull(db.moves().observeLastCheckOk().first())
        val first = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/B/A.jpg")), emptyList(), emptyList())
        db.moves().finishRun(first, RunState.Done.name, 9L, null, null, false)
        assertEquals(false, db.moves().observeLastCheckOk().first())
        val open = db.moves().writeRun(run(), emptyList(), emptyList(), emptyList())
        assertEquals("a run still going doesn't count", false, db.moves().observeLastCheckOk().first())
        db.moves().finishRun(open, RunState.Done.name, 10L, null, null, true)
        assertEquals(true, db.moves().observeLastCheckOk().first())
    }

    /** B8: the newest finished run's report is unread until closed; closing it closes older ones too. */
    @Test fun unreadRun_isTheNewestUntilClosed() = runTest {
        val a = addItem("a")
        assertNull(db.moves().unreadRun())
        val first = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/B/A.jpg")), emptyList(), emptyList())
        assertNull("a run still going has no report", db.moves().unreadRun())
        db.moves().finishRun(first, RunState.Stopped.name, 9L, null, null, true)
        val second = db.moves().writeRun(run(), emptyList(), emptyList(), emptyList())
        db.moves().finishRun(second, RunState.Done.name, 10L, null, null, false)

        assertEquals(second, db.moves().unreadRun()?.id)
        assertEquals(false, db.moves().unreadRun()?.checkOk)

        db.moves().closeReport(second)
        assertNull(db.moves().unreadRun())
    }

    @Test fun binnedFolders_areDoneDestinationsOnly() = runTest {
        val a = addItem("a")
        val b = addItem("b")
        val runId = db.moves().writeRun(run(), listOf(step(a, "/a", "/p/Beach/A.jpg"), step(b, "/b", "/p/Sunset/B.jpg")), emptyList(), emptyList())
        db.moves().recordMoved(db.moves().steps(runId).first().id, a, "/p/Beach/A.jpg", null, StepState.Done.name)
        assertEquals(listOf("/p/Beach"), db.moves().binnedFolders())
    }

    @Test fun removedPaths_addAndDrop() = runTest {
        db.moves().addRemoved(listOf("/p/Beach/x.jpg", "/p/Beach/y.jpg"), "removed", 3L)
        db.moves().addRemoved(listOf("/p/Beach/y.jpg"), "left", 4L)
        assertEquals(setOf("/p/Beach/x.jpg", "/p/Beach/y.jpg"), db.moves().removedPaths().toSet())
        db.moves().dropRemoved(listOf("/p/Beach/x.jpg"))
        assertEquals(listOf("/p/Beach/y.jpg"), db.moves().removedPaths())
    }

    @Test fun binItems_andCategoryNames_readThePlannerInput() = runTest {
        val a = addItem("a")
        val cat = db.media().insertCategory(CategoryEntity(name = "Beach", description = "", createdBy = "model", folderName = null))
        db.media().updateItemEdit(a, "Waves", nameSetByUser = false, categoryId = cat, categorySetByUser = false, status = ItemStatus.Sorted)
        val item = db.moves().binItems().single()
        assertEquals(a, item.id)
        assertEquals(cat, item.categoryId)
        assertEquals("Waves", item.friendlyName)
        assertEquals("a.jpg", item.originalName)
        assertEquals(mapOf(cat to "Beach"), db.moves().categoryNames().associate { it.id to it.name })
    }
}
