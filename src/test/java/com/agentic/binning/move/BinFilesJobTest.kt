package com.agentic.binning.move

import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.MoveRunEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A [BinStore] over [FakeMoveStore]: items with categories and names, runs, removed files. */
class FakeBinStore(val ms: FakeMoveStore = FakeMoveStore()) : BinStore, MoveStore by ms {
    data class Meta(val categoryId: Long?, val name: String?, val original: String)

    val meta = HashMap<Long, Meta>()
    val categories = LinkedHashMap<Long, String>()
    val removed = LinkedHashSet<String>()
    val runs = ArrayList<MoveRunEntity>()
    var shared: Set<String> = emptySet()

    /** Folders earlier runs put files in (their steps are history, not in [ms]). */
    val madeFolders = LinkedHashSet<String>()

    fun item(id: Long, path: String, category: Long?, name: String? = "Item $id", status: ItemStatus = ItemStatus.Sorted) {
        ms.items[id] = FakeMoveStore.Item(path, status, "h$id")
        meta[id] = Meta(category, name, path.substringAfterLast('/'))
    }

    fun status(id: Long) = ms.items.getValue(id).status
    fun path(id: Long) = ms.items.getValue(id).path

    override suspend fun binItems() = ms.items.keys.sorted().map { id ->
        val i = ms.items.getValue(id)
        val m = meta.getValue(id)
        BinItem(id, i.status, m.categoryId, m.name, i.path, m.original)
    }

    override suspend fun categoryNames(): Map<Long, String> = categories
    override suspend fun removedPaths() = removed.toList()
    override suspend fun dropRemoved(paths: List<String>) { removed -= paths.toSet() }
    override suspend fun sharedPaths(paths: List<String>) = shared.intersect(paths.toSet())

    override suspend fun writeRun(kind: MoveKind, startedAt: Long, steps: List<PlannedStep>, bytes: Map<Long, Long>, inPlace: List<Long>, missing: List<Long>): Long {
        val runId = runs.size + 1L
        runs += MoveRunEntity(id = runId, kind = kind.name, startedAt = startedAt, finishedAt = null, state = RunState.Running.name)
        ms.steps.clear()
        steps.forEach { ms.addStep(it.itemId, it.source, it.dest, bytes[it.itemId] ?: 0, keepSource = it.keepSource) }
        markItems(inPlace, missing)
        return runId
    }

    override suspend fun markItems(inPlace: List<Long>, missing: List<Long>) {
        inPlace.forEach { ms.items.getValue(it).status = ItemStatus.Binned }
        missing.forEach { ms.items.getValue(it).status = ItemStatus.Missing }
    }

    override suspend fun openRun() = runs.lastOrNull { RunState.of(it.state).isOpen }
    override suspend fun run(id: Long) = runs.firstOrNull { it.id == id }

    override suspend fun finishRun(runId: Long, state: RunState, finishedAt: Long, message: String?, removedFolders: List<String>) {
        val i = runs.indexOfFirst { it.id == runId }
        runs[i] = runs[i].copy(state = state.name, finishedAt = finishedAt, message = message, removedFolders = removedFolders.joinToString("\n").ifEmpty { null })
    }

    override suspend fun binnedFolders() =
        (madeFolders + ms.steps.filter { it.state == "Done" || it.state == "Verified" }.map { it.dest.substringBeforeLast('/') }).distinct()
}

class BinFilesJobTest {
    private val p = "/storage/emulated/0/Agentic Binning/Trip"
    private val fs = FakeMoveFiles()
    private val store = FakeBinStore()
    private val job = BinFilesJob(store, fs, MoveNotifier { _, _ -> }, p, clock = { 100L })

    private suspend fun bin() = job.binFiles(MoveControl()) {}

    init {
        store.categories[1] = "Beach"
        store.categories[2] = "Turtle Beach"
        fs.dirs += p
    }

    @Test fun binFiles_movesEverySortedItemAndFinishesTheRun() = runTest {
        fs.put("/storage/emulated/0/DCIM/Camera/a.jpg", "A")
        fs.put("/storage/emulated/0/Download/b.jpg", "B")
        store.item(1, "/storage/emulated/0/DCIM/Camera/a.jpg", 1, "Morning")
        store.item(2, "/storage/emulated/0/Download/b.jpg", 2, "Resting")
        store.item(3, "/storage/emulated/0/DCIM/Camera/bad.mp4", null, null, ItemStatus.Failed)

        assertEquals(MoveOutcome.Done, bin())

        assertEquals("A", fs.content("$p/Beach/Morning.jpg"))
        assertEquals("B", fs.content("$p/Turtle Beach/Resting.jpg"))
        assertEquals(ItemStatus.Binned, store.status(1))
        assertEquals(ItemStatus.Failed, store.status(3))
        val run = store.runs.single()
        assertEquals("Done", run.state)
        assertEquals(100L, run.finishedAt)
        // Source folders are never removed (B12).
        assertTrue(fs.log.none { it.startsWith("rmdir /storage/emulated/0/DCIM") })
        val report = job.report(run.id)!!
        assertEquals("2 files binned into 2 category folders", report.headline)
        assertEquals(listOf(LeftOut(3, LeftOutReason.Failed)), report.leftOut)
        assertEquals(true, report.checkOk)
    }

    /** B5: an unknown file in a category folder blocks before anything is written or moved. */
    @Test fun unknownFiles_blockBeforeWriting() = runTest {
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        fs.put("$p/Beach/IMG_4410.jpg", "stray")
        store.item(1, "/storage/emulated/0/DCIM/a.jpg", 1)

        val outcome = bin()

        assertTrue(outcome is MoveOutcome.Blocked)
        assertEquals(listOf("$p/Beach/IMG_4410.jpg"), (outcome as MoveOutcome.Blocked).problems.unexpected)
        assertTrue(store.runs.isEmpty())
        assertTrue(fs.exists("/storage/emulated/0/DCIM/a.jpg"))
    }

    @Test fun leftFiles_doNotBlock() = runTest {
        fs.put("$p/Beach/IMG_4410.jpg", "stray")
        store.removed += "$p/Beach/IMG_4410.jpg"
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        store.item(1, "/storage/emulated/0/DCIM/a.jpg", 1)
        assertEquals(MoveOutcome.Done, bin())
    }

    /** B5: a binned file deleted in Gallery marks its item Missing; Bin Files carries on. */
    @Test fun missingBinned_isMarkedAndCarriesOn() = runTest {
        store.item(1, "$p/Beach/Item 1.jpg", 1, status = ItemStatus.Binned)
        fs.put("/storage/emulated/0/DCIM/b.jpg", "B")
        store.item(2, "/storage/emulated/0/DCIM/b.jpg", 1)

        assertEquals(MoveOutcome.Done, bin())

        assertEquals(ItemStatus.Missing, store.status(1))
        assertEquals(ItemStatus.Binned, store.status(2))
    }

    /** B12: an empty folder the app made is removed; a non-empty one and source folders stay. */
    @Test fun removesOnlyEmptyFoldersItMade() = runTest {
        // A Re-sort moved items 1 and 2 out of Turtle Beach into Beach; 3 is a removed file kept in Sunset.
        store.categories[3] = "Sunset"
        fs.put("$p/Turtle Beach/Item 1.jpg", "1")
        fs.put("$p/Sunset/Item 2.jpg", "2")
        fs.put("$p/Sunset/Old.jpg", "removed")
        store.removed += "$p/Sunset/Old.jpg"
        store.madeFolders += listOf("$p/Turtle Beach", "$p/Sunset")
        store.item(1, "$p/Turtle Beach/Item 1.jpg", 1)
        store.item(2, "$p/Sunset/Item 2.jpg", 1)
        fs.put("/storage/emulated/0/DCIM/c.jpg", "3")
        store.item(3, "/storage/emulated/0/DCIM/c.jpg", 1)

        assertEquals(MoveOutcome.Done, bin())

        assertFalse(fs.dirs.contains("$p/Turtle Beach"))
        assertTrue(fs.dirs.contains("$p/Sunset"))
        assertTrue(fs.dirs.contains("/storage/emulated/0/DCIM"))
        assertEquals("Turtle Beach", job.report(store.runs.single().id)!!.removedFolders.single())
    }

    /** B9: nothing to move; in-place items are only marked binned, and the report says so. */
    /** A folder the user made inside the project is never removed, even when Bin Files empties it (B12). */
    @Test fun keepsAnEmptiedFolderItDidNotMake() = runTest {
        fs.put("$p/Imports/a.jpg", "A")
        store.item(1, "$p/Imports/a.jpg", 1)
        assertEquals(MoveOutcome.Done, bin())
        assertTrue(fs.dirs.contains("$p/Imports"))
    }

    /** P8: a Copied from folder is the app's own, removed once Bin Files empties it. */
    @Test fun removesAnEmptiedCopiedFromFolder() = runTest {
        fs.put("$p/Copied from Maui/a.jpg", "A")
        store.item(1, "$p/Copied from Maui/a.jpg", 1)
        assertEquals(MoveOutcome.Done, bin())
        assertFalse(fs.dirs.contains("$p/Copied from Maui"))
    }

    @Test fun nothingToMove_marksInPlace() = runTest {
        fs.put("$p/Beach/Item 1.jpg", "1")
        store.item(1, "$p/Beach/Item 1.jpg", 1)

        assertEquals(MoveOutcome.NothingToMove(1), bin())

        assertEquals(ItemStatus.Binned, store.status(1))
        assertEquals("Nothing to move. 1 file was already in place.", job.report(store.runs.single().id)!!.headline)
    }

    @Test fun storageFull_stopsWithAMessage() = runTest {
        fs.put("/sd/a.mp4", "x".repeat(100))
        store.item(1, "/sd/a.mp4", 1)
        fs.free = MoveEngine.COPY_MARGIN_BYTES

        assertTrue(bin() is MoveOutcome.StorageFull)

        val run = store.runs.single()
        assertEquals("Stopped", run.state)
        assertTrue(run.message!!.startsWith("Storage is full. Free up "))
        assertTrue(fs.exists("/sd/a.mp4"))
    }

    /** P8's follow-on: an item another listed project also holds is copied, its original kept. */
    @Test fun sharedSource_isCopied() = runTest {
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        store.item(1, "/storage/emulated/0/DCIM/a.jpg", 1, "Morning")
        store.shared = setOf("/storage/emulated/0/DCIM/a.jpg")

        assertEquals(MoveOutcome.Done, bin())

        assertEquals("A", fs.content("/storage/emulated/0/DCIM/a.jpg"))
        assertEquals("A", fs.content("$p/Beach/Morning.jpg"))
    }

    /** B3: an open run is resumed, not planned again; a paused one stays paused until resumed. */
    @Test fun resume_finishesTheOpenRun() = runTest {
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        store.item(1, "/storage/emulated/0/DCIM/a.jpg", 1, "Morning")
        val runId = store.writeRun(MoveKind.Bin, 1, listOf(PlannedStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/Morning.jpg", false, false)), mapOf(1L to 1L), emptyList(), emptyList())

        assertEquals(MoveOutcome.Done, job.resume(runId, MoveControl()) {})

        assertEquals("A", fs.content("$p/Beach/Morning.jpg"))
        assertNull(store.openRun())
        assertEquals(1, store.runs.size)
    }

    @Test fun stop_leavesTheRestForTheNextBinFiles() = runTest {
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        fs.put("/storage/emulated/0/DCIM/b.jpg", "B")
        store.item(1, "/storage/emulated/0/DCIM/a.jpg", 1)
        store.item(2, "/storage/emulated/0/DCIM/b.jpg", 1)
        val control = MoveControl()
        fs.before = { op, path -> if (op == "rename" && path.endsWith("a.jpg")) control.stop() }

        assertEquals(MoveOutcome.Stopped, job.binFiles(control) {})

        val run = store.runs.single()
        assertEquals("Stopped", run.state)
        assertEquals(1, job.report(run.id)!!.stillToMove)
        assertEquals(ItemStatus.Sorted, store.status(2))
        fs.before = { _, _ -> }
        assertEquals(MoveOutcome.Done, bin())
        assertEquals(ItemStatus.Binned, store.status(2))
    }

    @Test fun goneRemovedRows_areDropped() = runTest {
        store.removed += "$p/Beach/gone.jpg"
        fs.put("/storage/emulated/0/DCIM/a.jpg", "A")
        store.item(1, "/storage/emulated/0/DCIM/a.jpg", 1)
        bin()
        assertTrue(store.removed.isEmpty())
    }
}
