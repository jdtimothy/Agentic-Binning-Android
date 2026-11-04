package com.agentic.binning.move

import com.agentic.binning.db.ItemStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveEngineTest {
    private val p = "/storage/emulated/0/Agentic Binning/Trip"
    private val fs = FakeMoveFiles()
    private val store = FakeMoveStore()
    private val notified = ArrayList<Pair<String?, String>>()
    private val engine = MoveEngine(store, fs, MoveNotifier { from, to -> notified += from to to }, p)
    private val progress = ArrayList<MoveProgress>()

    private suspend fun run(control: MoveControl = MoveControl()) = engine.run(1, MoveKind.Bin, control) { progress += it }

    private fun file(path: String, content: String = "data:$path"): String { fs.put(path, content); return content }

    // ---------------------------------------------------------------- Task 6

    @Test fun rename_movesAndRecords() = runTest {
        val c = file("/storage/emulated/0/DCIM/Camera/a.jpg")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/Camera/a.jpg", "$p/Beach/Morning.jpg", c.length.toLong())

        assertEquals(MoveOutcome.Done, run())

        assertNull(fs.content("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertEquals(c, fs.content("$p/Beach/Morning.jpg"))
        assertEquals("Done", store.step(id).state)
        assertEquals("$p/Beach/Morning.jpg", store.items.getValue(1).path)
        assertEquals(ItemStatus.Binned, store.items.getValue(1).status)
        assertEquals(FakeMoveFiles.hash(c), store.items.getValue(1).hash)
        assertTrue(fs.log.none { it.startsWith("copy") })
    }

    @Test fun rename_destTaken_takesTheNextNumber() = runTest {
        val c = file("/storage/emulated/0/DCIM/a.jpg")
        file("$p/Beach/Morning.jpg", "someone else's")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/Morning.jpg", c.length.toLong())

        assertEquals(MoveOutcome.Done, run())

        assertEquals("someone else's", fs.content("$p/Beach/Morning.jpg"))
        assertEquals(c, fs.content("$p/Beach/Morning 2.jpg"))
        assertEquals("$p/Beach/Morning 2.jpg", store.step(id).dest)
    }

    @Test fun rename_checkFails_failsTheRun() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        file("/storage/emulated/0/DCIM/b.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        val b = store.addStep(2, "/storage/emulated/0/DCIM/b.jpg", "$p/Beach/B.jpg", 10)
        fs.renameLies = true

        val outcome = run()

        assertTrue(outcome is MoveOutcome.Failed)
        assertEquals("Failed", store.step(a).state)
        assertEquals(StepErrors.RENAME_CHECK, store.step(a).error)
        assertEquals("Planned", store.step(b).state)
        assertEquals("/storage/emulated/0/DCIM/a.jpg", store.items.getValue(1).path)
    }

    @Test fun rename_ioError_skipsAndCarriesOn() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        fs.renameThrows = true

        assertEquals(MoveOutcome.Done, run())

        assertEquals("Skipped", store.step(a).state)
        assertEquals(StepErrors.IO, store.step(a).error)
        assertTrue(fs.exists("/storage/emulated/0/DCIM/a.jpg"))
    }

    @Test fun copy_acrossVolumes_movesAndDeletesTheSource() = runTest {
        val c = file("/sd/DCIM/a.mp4")
        fs.files.getValue("/sd/DCIM/a.mp4").mtime = 42L
        val id = store.addStep(1, "/sd/DCIM/a.mp4", "$p/Surf/Wave.mp4", c.length.toLong())

        assertEquals(MoveOutcome.Done, run())

        assertEquals(c, fs.content("$p/Surf/Wave.mp4"))
        assertEquals(42L, fs.lastModified("$p/Surf/Wave.mp4"))
        assertFalse(fs.exists("/sd/DCIM/a.mp4"))
        assertTrue(fs.files.keys.none { it.contains(".binning/moving") })
        assertEquals("Done", store.step(id).state)
        assertEquals(listOf("/sd/DCIM/a.mp4" to "$p/Surf/Wave.mp4"), notified)
    }

    @Test fun copy_neverOverwrites() = runTest {
        val c = file("/sd/a.jpg")
        file("$p/Beach/A.jpg", "keep me")
        store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong())

        assertEquals(MoveOutcome.Done, run())

        assertEquals("keep me", fs.content("$p/Beach/A.jpg"))
        assertEquals(c, fs.content("$p/Beach/A 2.jpg"))
    }

    @Test fun copy_mismatchOnce_retriesAndSucceeds() = runTest {
        val c = file("/sd/a.jpg")
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong())
        fs.corruptCopies = 1

        assertEquals(MoveOutcome.Done, run())

        assertEquals(c, fs.content("$p/Beach/A.jpg"))
        assertEquals("Done", store.step(id).state)
        assertEquals(2, fs.log.count { it.startsWith("copy") })
    }

    /** B4 (owner): two bad copies skip the file; its original is untouched and the run carries on. */
    @Test fun copy_mismatchTwice_skipsAndKeepsTheSource() = runTest {
        val c = file("/sd/a.jpg")
        file("/sd/b.jpg")
        val a = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong())
        val b = store.addStep(2, "/sd/b.jpg", "$p/Beach/B.jpg", 10)
        fs.corruptCopies = 2

        assertEquals(MoveOutcome.Done, run())

        assertEquals("Skipped", store.step(a).state)
        assertEquals(StepErrors.COPY_MISMATCH, store.step(a).error)
        assertEquals(c, fs.content("/sd/a.jpg"))
        assertFalse(fs.exists("$p/Beach/A.jpg"))
        assertEquals(ItemStatus.Sorted, store.items.getValue(1).status)
        assertTrue(fs.files.keys.none { it.contains(".binning/moving") })
        assertEquals("Done", store.step(b).state)
    }

    @Test fun copy_spaceShort_stopsBeforeCopying() = runTest {
        file("/sd/a.mp4", "x".repeat(1000))
        val id = store.addStep(1, "/sd/a.mp4", "$p/Surf/A.mp4", 1000)
        fs.free = MoveEngine.COPY_MARGIN_BYTES + 500

        val outcome = run()

        assertEquals(MoveOutcome.StorageFull(500), outcome)
        assertEquals("Planned", store.step(id).state)
        assertTrue(fs.log.none { it.startsWith("copy") })
    }

    @Test fun copy_spaceRunsOut_deletesTheHalfCopy() = runTest {
        file("/sd/a.mp4", "x".repeat(1000))
        val id = store.addStep(1, "/sd/a.mp4", "$p/Surf/A.mp4", 1000)
        fs.noSpaceDuringCopy = true

        assertTrue(run() is MoveOutcome.StorageFull)

        assertTrue(fs.files.keys.none { it.contains(".binning/moving") })
        assertTrue(fs.exists("/sd/a.mp4"))
        assertEquals("Planned", store.step(id).state)
    }

    /** P8: another project also holds the source; it is copied and never deleted. */
    @Test fun keepSource_neverDeletesTheSource() = runTest {
        val c = file("/storage/emulated/0/DCIM/a.jpg")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), keepSource = true)

        assertEquals(MoveOutcome.Done, run())

        assertEquals(c, fs.content("/storage/emulated/0/DCIM/a.jpg"))
        assertEquals(c, fs.content("$p/Beach/A.jpg"))
        assertTrue(fs.log.none { it.startsWith("delete /storage/emulated/0/DCIM") })
        assertTrue(fs.log.any { it.startsWith("copy") })
        assertEquals("Done", store.step(id).state)
        assertEquals(listOf(null to "$p/Beach/A.jpg"), notified)
    }

    @Test fun missingSource_marksMissingAndCarriesOn() = runTest {
        file("/storage/emulated/0/DCIM/b.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        val b = store.addStep(2, "/storage/emulated/0/DCIM/b.jpg", "$p/Beach/B.jpg", 10)

        assertEquals(MoveOutcome.Done, run())

        assertEquals("Missing", store.step(a).state)
        assertEquals(ItemStatus.Missing, store.items.getValue(1).status)
        assertEquals("Done", store.step(b).state)
    }

    @Test fun lowSpace_stopsEveryRun() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        fs.free = MoveEngine.MIN_FREE_BYTES - 1

        assertEquals(MoveOutcome.StorageFull(1), run())
        assertEquals("Planned", store.step(a).state)
    }

    /** B2 (owner): Pause finishes the file in progress, then holds before the next one. */
    @Test fun pause_finishesTheCurrentFileFirst() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        file("/storage/emulated/0/DCIM/b.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        val b = store.addStep(2, "/storage/emulated/0/DCIM/b.jpg", "$p/Beach/B.jpg", 10)
        val control = MoveControl()
        val paused = CompletableDeferred<Unit>()
        fs.before = { op, path -> if (op == "rename" && path.endsWith("a.jpg")) control.pause() }
        val result = async { engine.run(1, MoveKind.Bin, control) { if (it.paused) paused.complete(Unit) } }

        paused.await()
        assertEquals("Done", store.step(a).state)
        assertEquals("Planned", store.step(b).state)
        assertEquals("Paused", store.runState)

        control.resume()
        assertEquals(MoveOutcome.Done, result.await())
        assertEquals("Done", store.step(b).state)
        assertEquals(listOf("Paused", "Running"), store.runStates)
    }

    @Test fun stop_finishesTheCurrentFileFirst() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        file("/storage/emulated/0/DCIM/b.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        val b = store.addStep(2, "/storage/emulated/0/DCIM/b.jpg", "$p/Beach/B.jpg", 10)
        val control = MoveControl()
        fs.before = { op, path -> if (op == "rename" && path.endsWith("a.jpg")) control.stop() }

        assertEquals(MoveOutcome.Stopped, run(control))
        assertEquals("Done", store.step(a).state)
        assertEquals("Planned", store.step(b).state)
    }

    @Test fun stop_whilePaused_endsWithoutMovingMore() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        val a = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        val control = MoveControl(startPaused = true)
        val result = async { engine.run(1, MoveKind.Bin, control) {} }
        yield()
        control.stop()
        assertEquals(MoveOutcome.Stopped, result.await())
        assertEquals("Planned", store.step(a).state)
    }

    /** A cancelled coroutine (service destroyed, project switch) still finishes the step in progress. */
    @Test fun cancel_finishesTheCurrentStep() = runTest {
        val c = file("/sd/a.jpg")
        file("/sd/b.jpg")
        val a = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong())
        val b = store.addStep(2, "/sd/b.jpg", "$p/Beach/B.jpg", 10)
        lateinit var job: kotlinx.coroutines.Deferred<MoveOutcome>
        fs.before = { op, path -> if (op == "copy" && path == "/sd/a.jpg") job.cancel() }
        job = async { engine.run(1, MoveKind.Bin, MoveControl()) {} }

        try {
            job.await()
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals("Done", store.step(a).state)
        assertFalse(fs.exists("/sd/a.jpg"))
        assertEquals("Planned", store.step(b).state)
        // The run stays open, so it resumes when the project opens (B3).
        assertEquals("Running", store.runState)
    }

    @Test fun thirdStart_isSkipped() = runTest {
        file("/sd/a.jpg")
        fs.put("$p/.binning/moving/1.tmp", "half")
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", 10, state = StepState.Copied, attempts = 2)

        assertEquals(MoveOutcome.Done, run())

        assertEquals("Skipped", store.step(id).state)
        assertEquals(StepErrors.INTERRUPTED, store.step(id).error)
        assertTrue(fs.exists("/sd/a.jpg"))
        assertFalse(fs.exists("$p/.binning/moving/1.tmp"))
    }

    @Test fun progress_countsEveryStep() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        file("/sd/b.jpg")
        store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        store.addStep(2, "/sd/b.jpg", "$p/Beach/B.jpg", 10)
        store.addStep(3, "/x/done.jpg", "$p/Beach/C.jpg", 10, state = StepState.Done)

        run()

        assertEquals(3, progress.last().total)
        assertEquals(3, progress.last().done)
        assertEquals(1, progress.first().done)
        assertTrue(progress.any { it.phase == MovePhase.Moving && it.current == "A.jpg" })
        assertTrue(progress.any { it.phase == MovePhase.Copying && it.current == "B.jpg" })
        assertTrue(progress.any { it.phase == MovePhase.DeletingOriginal })
    }

    @Test fun notifier_getsBothPaths() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        run()
        assertEquals(listOf("/storage/emulated/0/DCIM/a.jpg" to "$p/Beach/A.jpg"), notified)
    }

    // ---------------------------------------------------------------- Task 7: resume

    @Test fun resume_planned_redoesTheMove() = runTest {
        val c = file("/storage/emulated/0/DCIM/a.jpg")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), attempts = 1)
        assertEquals(MoveOutcome.Done, run())
        assertEquals("Done", store.step(id).state)
        assertEquals(c, fs.content("$p/Beach/A.jpg"))
    }

    /** The app died between the rename and recording it: the file is found at its target. */
    @Test fun resume_planned_renameAlreadyDone_recordsIt() = runTest {
        val c = file("$p/Beach/A.jpg", "data")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), attempts = 1)

        assertEquals(MoveOutcome.Done, run())

        assertEquals("Done", store.step(id).state)
        assertEquals("$p/Beach/A.jpg", store.items.getValue(1).path)
        assertEquals(c, fs.content("$p/Beach/A.jpg"))
    }

    /** A first start never claims a file it didn't put there. */
    @Test fun firstStart_sourceGoneButTargetThere_isMissing() = runTest {
        file("$p/Beach/A.jpg", "data")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 4)
        run()
        assertEquals("Missing", store.step(id).state)
    }

    @Test fun resume_copied_discardsTheTempAndRedoes() = runTest {
        val c = file("/sd/a.jpg")
        fs.put("$p/.binning/moving/1.tmp", "half")
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), state = StepState.Copied, attempts = 1)

        assertEquals(MoveOutcome.Done, run())

        assertEquals("Done", store.step(id).state)
        assertEquals(c, fs.content("$p/Beach/A.jpg"))
        assertFalse(fs.exists("$p/.binning/moving/1.tmp"))
    }

    @Test fun resume_verified_deletesTheSourceOnly() = runTest {
        val c = file("/sd/a.jpg")
        fs.put("$p/Beach/A.jpg", c)
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), state = StepState.Verified, attempts = 1)

        assertEquals(MoveOutcome.Done, run())

        assertFalse(fs.exists("/sd/a.jpg"))
        assertEquals(c, fs.content("$p/Beach/A.jpg"))
        assertEquals("Done", store.step(id).state)
    }

    /** Even interrupted twice, a recorded file is finished, never skipped: skipping would leave two copies. */
    @Test fun resume_verified_isFinishedEvenAfterTwoStarts() = runTest {
        val c = file("/sd/a.jpg")
        fs.put("$p/Beach/A.jpg", c)
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), state = StepState.Verified, attempts = 2)
        run()
        assertEquals("Done", store.step(id).state)
        assertFalse(fs.exists("/sd/a.jpg"))
    }

    @Test fun resume_verified_keepSource_keepsIt() = runTest {
        val c = file("/storage/emulated/0/DCIM/a.jpg")
        fs.put("$p/Beach/A.jpg", c)
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), state = StepState.Verified, keepSource = true, attempts = 1)
        run()
        assertTrue(fs.exists("/storage/emulated/0/DCIM/a.jpg"))
        assertEquals("Done", store.step(id).state)
    }

    /** The destination vanished after it was recorded: the source is kept and the item points back at it. */
    @Test fun resume_verified_destGone_keepsTheSource() = runTest {
        val c = file("/sd/a.jpg")
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", c.length.toLong(), state = StepState.Verified, attempts = 1)
        store.items.getValue(1).path = "$p/Beach/A.jpg"
        run()
        assertTrue(fs.exists("/sd/a.jpg"))
        assertEquals("Failed", store.step(id).state)
        assertEquals("/sd/a.jpg", store.items.getValue(1).path)
    }

    @Test fun resume_bothGone_marksMissing() = runTest {
        val id = store.addStep(1, "/sd/a.jpg", "$p/Beach/A.jpg", 10, attempts = 1)
        run()
        assertEquals("Missing", store.step(id).state)
    }

    /** B2/B3: a run that was paused when the app died resumes paused. */
    @Test fun resume_pausedRun_staysPaused() = runTest {
        file("/storage/emulated/0/DCIM/a.jpg")
        val id = store.addStep(1, "/storage/emulated/0/DCIM/a.jpg", "$p/Beach/A.jpg", 10)
        val control = MoveControl(startPaused = true)
        val result = async { engine.run(1, MoveKind.Bin, control) { progress += it } }
        yield()
        assertEquals("Planned", store.step(id).state)
        assertTrue(progress.last().paused)
        control.resume()
        assertEquals(MoveOutcome.Done, result.await())
        assertEquals("Done", store.step(id).state)
    }
}
