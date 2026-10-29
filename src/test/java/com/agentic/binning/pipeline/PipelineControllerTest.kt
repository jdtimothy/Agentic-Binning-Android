package com.agentic.binning.pipeline

import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.project.ProjectRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineControllerTest {
    private val project = ProjectRef("La Jolla Trip", "/sdcard/Agentic Binning/La Jolla Trip")
    private val events = Events()
    private val model = FakeModel(events)
    private val preparer = FakePreparer(missing = emptySet(), undecodable = emptySet())
    private val batches = mutableListOf<ProjectRef>()

    private fun TestScope.controller(
        store: FakeStore,
        preparer: FakePreparer = this@PipelineControllerTest.preparer,
        batchComplete: BatchCompleteHandler = BatchCompleteHandler { events += "batchComplete"; batches += it },
    ) = PipelineController(
        // Not backgroundScope: advanceUntilIdle() does not wait for background work.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        engineFactory = { AnalysisEngine(model, preparer, store) },
        model = model,
        store = store,
        batchComplete = batchComplete,
    )

    private fun store(vararg items: ItemEntity) = FakeStore(items.toList(), events)

    @Test
    fun start_resetsInterruptedItemsToPending() = runTest {
        val store = store(item(1, ItemStatus.Analyzing), item(2))
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        assertEquals(listOf(ItemStatus.Analyzing to ItemStatus.Pending), store.resets)
        assertEquals("reset", events.log.first())
        assertTrue(events.log.indexOf("reset") < events.log.indexOf("load"))
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals(ItemStatus.Analyzed, store.status(2))
    }

    @Test
    fun processesAllPending_thenReleases_thenCallsBatchCompleteOnce() = runTest {
        val store = store(item(1), item(2), item(3))
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        (1L..3L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(listOf("generate:1", "generate:2", "generate:3"), events.log.filter { it.startsWith("generate") })
        assertEquals(1, events.count("load"))
        assertEquals(1, events.count("release"))
        assertEquals(1, events.count("batchComplete"))
        assertEquals(listOf(project), batches)
        val state = controller.state.value
        assertEquals(PipelinePhase.Idle, state.phase)
        assertEquals(0, state.pending)
        assertEquals(3, state.analyzed)
        assertEquals(3, state.total)
        assertNull(state.currentItemId)
        assertNotNull(state.lastProfile)
    }

    @Test
    fun release_happensBeforeBatchComplete() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        val phases = mutableListOf<PipelinePhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.state.collect { phases += it.phase } }

        controller.start(project)
        advanceUntilIdle()

        assertEquals(listOf("release", "batchComplete"), events.log.filter { it == "release" || it == "batchComplete" })
        assertTrue(phases.containsAll(listOf(PipelinePhase.Releasing, PipelinePhase.Idle)))
        assertTrue(phases.indexOf(PipelinePhase.Releasing) < phases.lastIndexOf(PipelinePhase.Idle))
    }

    @Test
    fun pause_finishesCurrentItem_thenHolds_withModelLoaded() = runTest {
        val store = store(item(1), item(2), item(3))
        val controller = controller(store)
        val gate = model.gate(1)

        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Describing, controller.state.value.phase)
        assertEquals(1L, controller.state.value.currentItemId)

        controller.pause()
        gate.complete(false)
        advanceUntilIdle()

        assertEquals(PipelinePhase.Paused, controller.state.value.phase)
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals(ItemStatus.Pending, store.status(2))
        assertEquals(ItemStatus.Pending, store.status(3))
        assertEquals(0, events.count("release"))
        assertEquals(0, events.count("batchComplete"))
        assertEquals(1, model.generateCalls)

        controller.resume()
        advanceUntilIdle()

        (1L..3L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(1, events.count("load"))
        assertEquals(1, events.count("batchComplete"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test
    fun stop_cancelsCurrent_returnsItToPending_andReleases() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        model.gate(1)

        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Describing, controller.state.value.phase)

        controller.stop()
        advanceUntilIdle()

        assertTrue("model.cancel() not called", events.count("cancel") >= 1)
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(ItemStatus.Pending, store.status(2))
        assertEquals(1, events.count("release"))
        assertEquals(0, events.count("batchComplete"))
        assertTrue(store.saved.isEmpty())
        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)
        assertNull(controller.state.value.currentItemId)
        assertEquals(2, controller.state.value.pending)
    }

    @Test
    fun resumeAfterStop_continuesWithRemainingItems() = runTest {
        val store = store(item(1), item(2), item(3))
        val controller = controller(store)
        model.gate(1)
        controller.start(project)
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)

        controller.resume()
        advanceUntilIdle()

        (1L..3L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(2, events.count("reset"))
        assertEquals(2, events.count("load"))
        assertEquals(2, events.count("release"))
        assertEquals(1, events.count("batchComplete"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test
    fun failedItem_doesNotStopTheBatch() = runTest {
        val store = store(item(1), item(2), item(3), item(4))
        model.script(2, reply("not json"), reply("still not json"))
        val controller = controller(store, FakePreparer(missing = setOf(4), undecodable = setOf(1)))

        controller.start(project)
        advanceUntilIdle()

        assertEquals(ItemStatus.Failed, store.status(1))
        assertEquals("decode", store.reason(1))
        assertEquals(ItemStatus.Failed, store.status(2))
        assertEquals("invalid-json", store.reason(2))
        assertEquals(ItemStatus.Analyzed, store.status(3))
        assertEquals(ItemStatus.Missing, store.status(4))
        assertEquals(1, events.count("batchComplete"))
        val state = controller.state.value
        assertEquals(PipelinePhase.Idle, state.phase)
        // Failed and Missing count as done, so progress can reach the end.
        assertEquals(1, state.analyzed)
        assertEquals(3, state.failed)
        assertEquals(state.total, state.analyzed + state.failed)
    }

    @Test
    fun start_withNothingPending_doesNotLoadModel() = runTest {
        val store = store(item(1, ItemStatus.Analyzed))
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        assertEquals(0, events.count("load"))
        assertEquals(0, events.count("batchComplete"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals(1, controller.state.value.analyzed)
    }

    @Test
    fun start_isRunningSynchronously_soTheServiceDoesNotStopAtOnce() = runTest {
        val controller = controller(store(item(1)))

        controller.start(project)

        assertTrue(controller.state.value.phase.isRunning)
        assertEquals(project, controller.state.value.project)
    }

    @Test
    fun loadFailure_endsStopped_withError_andItemsStayPending() = runTest {
        val store = store(item(1))
        model.loadFailure = VisionModelException("LFM3-VL-2B is not downloaded")
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Stopped, state.phase)
        assertEquals("LFM3-VL-2B is not downloaded", state.error)
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(1, events.count("release"))
        assertFalse(events.log.contains("batchComplete"))
    }

    // --- Fix round 1 ---

    /** I1: a run cancelled before it got going (waiting for the previous run's cleanup) still ends Stopped. */
    @Test
    fun stopWhileWaitingForPreviousCleanup_endsStopped() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        model.gate(1)
        controller.start(project)
        runCurrent()
        val cleanup = CompletableDeferred<Unit>().also { model.releaseGate = it }
        controller.stop()
        runCurrent() // first run is now suspended in release() during its cleanup
        controller.start(project)
        runCurrent() // second run waits for the first to finish
        controller.stop()
        runCurrent()

        cleanup.complete(Unit)
        advanceUntilIdle()

        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(1, events.count("load"))
    }

    /** I1: stop() before the launched run is even dispatched. */
    @Test
    fun stopBeforeRunIsDispatched_endsStopped() = runTest {
        val store = store(item(1))
        val controller = controller(store)

        controller.start(project)
        controller.stop()
        advanceUntilIdle()

        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)
        assertEquals(0, events.count("load"))
        assertEquals(ItemStatus.Pending, store.status(1))
    }

    /** I1: AppGraph waits on this before closing the database; it must return once everything is done. */
    @Test
    fun stopAndReset_waitsForCleanup_andForgetsTheProject() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        model.gate(1)
        controller.start(project)
        runCurrent()
        val cleanup = CompletableDeferred<Unit>().also { model.releaseGate = it }
        var returned = false
        launch {
            controller.stopAndReset()
            returned = true
        }
        runCurrent()
        assertFalse("returned before the cleanup finished", returned)

        cleanup.complete(Unit)
        advanceUntilIdle()

        assertTrue(returned)
        assertEquals(ItemStatus.Pending, store.status(1))
        // M8: resume() must not analyze the next project's database under the old ref.
        assertNull(controller.state.value.project)
        controller.resume()
        advanceUntilIdle()
        assertEquals(1, events.count("load"))
        assertFalse(controller.state.value.phase.isRunning)
    }

    /** I2: media added while the batch is being released is analyzed, not orphaned. */
    @Test
    fun startDuringRelease_runsAnotherBatch() = runTest {
        val store = store(item(1))
        val controller = controller(store)
        val releasing = CompletableDeferred<Unit>().also { model.releaseGate = it }
        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Releasing, controller.state.value.phase)

        store.add(item(2))
        controller.start(project)
        releasing.complete(Unit)
        advanceUntilIdle()

        assertEquals(ItemStatus.Analyzed, store.status(2))
        assertEquals(2, events.count("load"))
        assertEquals(2, events.count("release"))
        assertEquals(2, events.count("batchComplete"))
        assertEquals(listOf("release", "batchComplete", "load", "generate:2", "release", "batchComplete"), events.log.dropWhile { it != "release" })
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    /** I2: media added while the batch-complete handler runs is analyzed too. */
    @Test
    fun startDuringBatchComplete_runsAnotherBatch() = runTest {
        val store = store(item(1))
        lateinit var controller: PipelineController
        controller = controller(store, batchComplete = BatchCompleteHandler {
            events += "batchComplete"
            if (events.count("batchComplete") == 1) {
                store.add(item(2))
                controller.start(project)
            }
        })

        controller.start(project)
        advanceUntilIdle()

        assertEquals(ItemStatus.Analyzed, store.status(2))
        assertEquals(2, events.count("batchComplete"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    /** M7: adding media while paused must not resume the run; only resume() does. */
    @Test
    fun startWhilePaused_staysPaused() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        val gate = model.gate(1)
        controller.start(project)
        runCurrent()
        controller.pause()
        gate.complete(false)
        advanceUntilIdle()
        assertEquals(PipelinePhase.Paused, controller.state.value.phase)

        store.add(item(3))
        controller.start(project)
        advanceUntilIdle()

        assertEquals(PipelinePhase.Paused, controller.state.value.phase)
        assertEquals(ItemStatus.Pending, store.status(2))

        controller.resume()
        advanceUntilIdle()
        (1L..3L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(1, events.count("batchComplete"))
    }
}
