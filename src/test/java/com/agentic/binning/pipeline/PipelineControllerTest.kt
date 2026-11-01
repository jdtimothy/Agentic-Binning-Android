package com.agentic.binning.pipeline

import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.SortUnavailable
import com.agentic.binning.model.SortingUnavailableException
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.model.describe
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.SortingModelChoice
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
    private val categorizer = FakeCategorizer(events)
    private val arbiter = ModelArbiter()
    private val warnings = mutableListOf<String>()

    private fun TestScope.controller(
        store: FakeStore,
        preparer: FakePreparer = this@PipelineControllerTest.preparer,
        categorizer: Categorizer = this@PipelineControllerTest.categorizer,
        isCurrent: (ProjectRef) -> Boolean = { true },
        maxFramesPerClip: () -> Int = { 16 },
    ) = PipelineController(
        // Not backgroundScope: advanceUntilIdle() does not wait for background work.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        engineFactory = { cap -> AnalysisEngine(model, preparer, store, maxFrames = cap) },
        model = model,
        store = store,
        categorizer = categorizer,
        arbiter = arbiter,
        log = PipelineLog { message, _ -> warnings += message },
        isCurrent = isCurrent,
        maxFramesPerClip = maxFramesPerClip,
    )

    private fun store(vararg items: ItemEntity) = FakeStore(items.toList(), events)

    /** The real categorizer over [store], sorting with [text] (LFM3-1.7B budgets). */
    private fun sorting(store: FakeStore, text: FakeTextModel, sorts: FakeSortStore = FakeSortStore(store, events)) =
        SortingCategorizer(sorts, modelFor = { text }, choice = { SortingModelChoice.Lfm3_1_7B }, log = PipelineLog.None)

    @Test
    fun capChange_appliesNextBatch() = runTest {
        var cap = 32
        val store = store(item(1, kind = ItemKind.Video))
        val controller = controller(store, maxFramesPerClip = { cap })

        controller.start(project)
        advanceUntilIdle()
        assertEquals(listOf(32), model.loadCaps)
        assertEquals(listOf(32), preparer.capsSeen)

        cap = 64 // Changed between batches; this one reads it at its start.
        store.add(item(2, kind = ItemKind.Video))
        controller.start(project)
        advanceUntilIdle()

        assertEquals(listOf(32, 64), model.loadCaps)
        assertEquals(listOf(32, 64), preparer.capsSeen)
    }

    @Test
    fun capChangeMidBatch_doesNotApplyUntilNextBatch() = runTest {
        var cap = 32
        val store = store(item(1, kind = ItemKind.Video), item(2, kind = ItemKind.Video))
        val gate = model.gate(1)
        val controller = controller(store, maxFramesPerClip = { cap })

        controller.start(project)
        advanceUntilIdle()
        cap = 64
        gate.complete(false)
        advanceUntilIdle()

        assertEquals(listOf(32), model.loadCaps)
        assertEquals(listOf(32, 32), preparer.capsSeen)
    }

    @Test
    fun start_resetsInterruptedItemsToPending() = runTest {
        val store = store(item(1, ItemStatus.Analyzing), item(2))
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        assertEquals(listOf(PipelineController.FAIL_INTERRUPTED_AFTER), store.recoveries)
        assertEquals("reset", events.log.first())
        assertTrue(events.log.indexOf("reset") < events.log.indexOf("load"))
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals(ItemStatus.Analyzed, store.status(2))
    }

    @Test
    fun processesAllPending_thenReleases_thenCategorizesOnce() = runTest {
        val store = store(item(1), item(2), item(3))
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        (1L..3L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(listOf("generate:1", "generate:2", "generate:3"), events.log.filter { it.startsWith("generate") })
        assertEquals(1, events.count("load"))
        assertEquals(1, events.count("release"))
        assertEquals(1, events.count("categorize"))
        assertEquals(listOf(project), categorizer.projects)
        val state = controller.state.value
        assertEquals(PipelinePhase.Idle, state.phase)
        assertEquals(0, state.pending)
        assertEquals(3, state.analyzed)
        assertEquals(3, state.total)
        assertNull(state.currentItemId)
        assertNotNull(state.lastProfile)
    }

    @Test
    fun release_happensBeforeCategorizing() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        val phases = mutableListOf<PipelinePhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.state.collect { phases += it.phase } }

        controller.start(project)
        advanceUntilIdle()

        assertEquals(listOf("release", "categorize"), events.log.filter { it == "release" || it == "categorize" })
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
        assertEquals(0, events.count("categorize"))
        assertEquals(1, model.generateCalls)

        controller.resume()
        advanceUntilIdle()

        (1L..3L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(1, events.count("load"))
        assertEquals(1, events.count("categorize"))
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
        assertEquals(0, events.count("categorize"))
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
        assertEquals(1, events.count("categorize"))
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
        assertEquals(1, events.count("categorize"))
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
        // Leftovers from an earlier run are still categorized.
        assertEquals(1, events.count("categorize"))
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
        model.loadFailure = VisionModelException("Loading LFM3-VL-2B on HTP failed: QNN error 7 at /data/user/0/models")
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Stopped, state.phase)
        assertEquals("The vision model couldn't start", state.error)
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(1, events.count("release"))
        assertFalse(events.log.contains("categorize"))
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

    /** I2: media added while the batch is being released is analyzed, not orphaned; categorizing still runs once. */
    @Test
    fun categorizeRunsOncePerRun_evenWithReruns() = runTest {
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
        assertEquals(1, events.count("categorize"))
        assertEquals(listOf("release", "load", "generate:2", "release", "categorize"), events.log.dropWhile { it != "release" })
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    /** I2: media added while categorizing is analyzed too, by a fresh run that waits for this one. */
    @Test
    fun startDuringCategorizing_runsAnotherRun() = runTest {
        val store = store(item(1))
        lateinit var controller: PipelineController
        categorizer.onCategorize = {
            if (events.count("categorize") == 1) {
                store.add(item(2))
                controller.start(project)
            }
        }
        controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        assertEquals(ItemStatus.Analyzed, store.status(2))
        assertEquals(2, events.count("categorize"))
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
        assertEquals(1, events.count("categorize"))
    }

    /** Task 12 (a): a stale start (old service intent, deferred retry) must not run against another project's database. */
    @Test
    fun start_refusesAProjectThatIsNotOpen() = runTest {
        val store = store(item(1))
        var open: ProjectRef? = ProjectRef("Other", "/sdcard/Agentic Binning/Other")
        val controller = controller(store, isCurrent = { it == open })

        controller.start(project)
        advanceUntilIdle()

        assertTrue(events.log.isEmpty())
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.project)

        open = project
        controller.start(project)
        advanceUntilIdle()
        assertEquals(ItemStatus.Analyzed, store.status(1))
    }

    /** Task 12 (a): a start that arrives while stopAndReset waits for cleanup is refused, not queued behind it. */
    @Test
    fun startDuringStopAndReset_isRefused() = runTest {
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
        assertFalse(returned)

        controller.start(project)
        cleanup.complete(Unit)
        advanceUntilIdle()

        assertTrue(returned)
        assertEquals(1, events.count("load"))
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(ItemStatus.Pending, store.status(2))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.project)
    }

    /** Task 12: the card's frame strip fills as the preparer extracts each frame, read from state. */
    @Test
    fun videoFrameProgress_reachesState() = runTest {
        val store = store(item(1, kind = ItemKind.Video), item(2, kind = ItemKind.Video))
        val controller = controller(store, preparer = FakePreparer(videoFrames = 3))
        val seen = mutableMapOf<Long, MutableList<Pair<Int, Int>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { s ->
                val id = s.currentItemId ?: return@collect
                if (s.phase == PipelinePhase.Preparing) seen.getOrPut(id) { mutableListOf() } += s.framesReady to s.framesTotal
            }
        }

        controller.start(project)
        advanceUntilIdle()

        val expected = listOf(0 to 0, 1 to 3, 2 to 3, 3 to 3)
        assertEquals(expected, seen.getValue(1L).distinct())
        // The next clip starts again from no frames.
        assertEquals(expected, seen.getValue(2L).distinct())
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    // --- Final review fixes ---

    /** I1: an NPU/SDK error stops the run instead of failing every item in turn. */
    @Test
    fun modelErrorDuringGeneration_endsStopped_andLeavesEveryItemPending() = runTest {
        val store = store(item(1), item(2), item(3))
        model.failure = VisionModelException("Generation failed: QNN error 1002")
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Stopped, state.phase)
        assertEquals("The vision model failed", state.error)
        assertEquals(1, model.generateCalls)
        (1L..3L).forEach { assertEquals(ItemStatus.Pending, store.status(it)) }
        assertEquals(0, store.attempts(1))
        assertEquals(1, events.count("release"))
        assertEquals(0, events.count("categorize"))
        assertEquals(0, state.failed)
    }

    /** I1: a full disk while writing work files or thumbnails stops the run; nothing is marked Failed. */
    @Test
    fun workFileWriteFailure_endsStopped_andLeavesEveryItemPending() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store, FakePreparer(unwritable = setOf(1, 2)))

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Stopped, state.phase)
        assertEquals("The phone ran out of space for analysis files", state.error)
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(ItemStatus.Pending, store.status(2))
        assertEquals(0, model.generateCalls)
    }

    /** I1: three Failed items in a row stop the run with a readable reason; the rest stay Pending. */
    @Test
    fun threeFailuresInARow_stopTheRun() = runTest {
        val store = store(item(1), item(2), item(3), item(4), item(5))
        val controller = controller(store, FakePreparer(undecodable = setOf(1, 2, 3, 4, 5)))

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Stopped, state.phase)
        assertEquals("Analysis stopped after repeated failures: Couldn't read this file.", state.error)
        (1L..3L).forEach { assertEquals(ItemStatus.Failed, store.status(it)) }
        assertEquals(ItemStatus.Pending, store.status(4))
        assertEquals(ItemStatus.Pending, store.status(5))
        assertEquals(1, events.count("release"))
        assertEquals(0, events.count("categorize"))
        assertEquals(3, state.failed)
    }

    /** I1: a success in between resets the count, and Missing files do not count. */
    @Test
    fun failuresBrokenBySuccess_doNotStopTheRun() = runTest {
        val store = store(item(1), item(2), item(3), item(4), item(5), item(6), item(7))
        val controller = controller(store, FakePreparer(undecodable = setOf(1, 2, 4, 6), missing = setOf(5)))

        controller.start(project)
        advanceUntilIdle()

        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.error)
        assertEquals(ItemStatus.Analyzed, store.status(3))
        assertEquals(ItemStatus.Missing, store.status(5))
        assertEquals(ItemStatus.Analyzed, store.status(7))
    }

    /** I2: each start of an item's analysis is counted. */
    @Test
    fun analyzingAnItem_countsTheAttempt_andAStopClearsIt() = runTest {
        val store = store(item(1), item(2))
        val controller = controller(store)
        model.gate(1)

        controller.start(project)
        runCurrent()
        assertEquals(ItemStatus.Analyzing, store.status(1))
        assertEquals(1, store.attempts(1))

        controller.stop()
        advanceUntilIdle()
        // A clean stop is not the item's fault.
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(0, store.attempts(1))
    }

    /** I2: an item that was being analyzed when the process died twice is failed, not retried forever. */
    @Test
    fun start_failsItemsInterruptedTwice_andRetriesTheRest() = runTest {
        val store = store(item(1, ItemStatus.Analyzing, attempts = 2), item(2, ItemStatus.Analyzing, attempts = 1), item(3))
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        assertEquals(ItemStatus.Failed, store.status(1))
        assertEquals("interrupted", store.reason(1))
        assertEquals(ItemStatus.Analyzed, store.status(2))
        assertEquals(ItemStatus.Analyzed, store.status(3))
        assertEquals(listOf("generate:2", "generate:3"), events.log.filter { it.startsWith("generate") })
    }

    // --- Task 7: categorizing ---

    @Test
    fun visionReleasedBeforeSortingLoads() = runTest {
        val store = store(item(1), item(2))
        val text = FakeTextModel(events)
        val controller = controller(store, categorizer = sorting(store, text))
        val phases = mutableListOf<PipelinePhase>()
        val progress = mutableListOf<Pair<Int, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect {
                phases += it.phase
                if (it.phase == PipelinePhase.Categorizing) progress += it.sortDone to it.sortTotal
            }
        }

        controller.start(project)
        advanceUntilIdle()

        assertEquals(
            listOf("load", "generate:1", "generate:2", "release", "text.load", "text.complete", "apply", "text.release"),
            events.log.filter { it != "reset" },
        )
        assertEquals(ItemStatus.Sorted, store.status(1))
        assertEquals(ItemStatus.Sorted, store.status(2))
        assertTrue(phases.indexOf(PipelinePhase.Categorizing) > phases.indexOf(PipelinePhase.Releasing))
        assertEquals(listOf(0 to 2, 2 to 2), progress.distinct())
        val state = controller.state.value
        assertEquals(PipelinePhase.Idle, state.phase)
        assertEquals(2, state.analyzed)
        assertNull(state.sortNotice)
    }

    @Test
    fun claudeUnavailable_leavesItemsUncategorized_withReason() = runTest {
        val store = store(item(1))
        val text = FakeTextModel(events).apply {
            failure = SortingUnavailableException(SortUnavailable.NoApiKey, "No Claude API key saved")
        }
        val controller = controller(store, categorizer = sorting(store, text))

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Idle, state.phase)
        assertEquals(describe(SortUnavailable.NoApiKey), state.sortNotice)
        assertNull(state.error)
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals("text.release", events.log.last())
    }

    @Test
    fun sortNotice_survivesTheNextStart_andClearsWhenSortingSucceeds() = runTest {
        val store = store(item(1))
        categorizer.outcome = CategorizeOutcome.Partial(sorted = 0, left = 1, message = "1 item couldn't be sorted")
        val controller = controller(store)
        controller.start(project)
        advanceUntilIdle()
        assertEquals("1 item couldn't be sorted", controller.state.value.sortNotice)

        val gate = model.gate(2)
        store.add(item(2))
        controller.start(project)
        runCurrent()
        assertEquals("1 item couldn't be sorted", controller.state.value.sortNotice)

        categorizer.outcome = CategorizeOutcome.Done(sorted = 2, newCategories = 1)
        gate.complete(false)
        advanceUntilIdle()
        assertNull(controller.state.value.sortNotice)
    }

    @Test
    fun categorizationFailure_neverSetsError_orFailsAnalysis() = runTest {
        val store = store(item(1))
        categorizer.failure = IllegalStateException("database is locked")
        val controller = controller(store)

        controller.start(project)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(PipelinePhase.Idle, state.phase)
        assertNull(state.error)
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertTrue(warnings.toString(), warnings.any { it.contains("Categorizing") })
    }

    @Test
    fun stopDuringCategorizing_releasesModel_keepsCommittedChunks() = runTest {
        val store = store(*(1L..30L).map { item(it, ItemStatus.Analyzed) }.toTypedArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val text = FakeTextModel(events)
        text.gate(2)
        val controller = controller(store, categorizer = sorting(store, text, sorts))

        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Categorizing, controller.state.value.phase)
        assertEquals(10 to 30, controller.state.value.sortDone to controller.state.value.sortTotal)

        controller.stop()
        advanceUntilIdle()

        (1L..10L).forEach { assertEquals(ItemStatus.Sorted, store.status(it)) }
        (11L..30L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertEquals(1, events.count("text.release"))
        assertEquals(1, sorts.applyCalls)
        val state = controller.state.value
        assertEquals(PipelinePhase.Stopped, state.phase)
        assertNull(state.error)
        assertNull(state.sortNotice)
        val run = sorts.runs.single()
        assertEquals("partial", run.outcome)
        assertEquals(10, run.sorted)
    }

    @Test
    fun runWithNothingAnalyzed_stillCategorizesLeftovers() = runTest {
        val store = store(item(1, ItemStatus.Analyzed), item(2, ItemStatus.Failed))
        val text = FakeTextModel(events)
        val controller = controller(store, categorizer = sorting(store, text))

        controller.start(project)
        advanceUntilIdle()

        assertEquals(0, events.count("load"))
        assertEquals(ItemStatus.Sorted, store.status(1))
        assertEquals(ItemStatus.Failed, store.status(2))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test
    fun categorizeNow_skipsAnalysis() = runTest {
        val store = store(item(1), item(2, ItemStatus.Analyzed))
        val text = FakeTextModel(events)
        val controller = controller(store, categorizer = sorting(store, text))

        controller.categorizeNow(project)
        assertTrue(controller.state.value.phase.isRunning)
        advanceUntilIdle()

        assertEquals(0, events.count("load"))
        assertEquals(0, events.count("reset"))
        assertEquals(ItemStatus.Pending, store.status(1))
        assertEquals(ItemStatus.Sorted, store.status(2))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    /** Resume after a Stop while categorizing (nothing Pending) goes straight back to categorizing the rest. */
    @Test
    fun categorizeNow_afterStopDuringCategorizing_sortsTheLeftovers() = runTest {
        val store = store(*(1L..30L).map { item(it, ItemStatus.Analyzed) }.toTypedArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val text = FakeTextModel(events)
        text.gate(2)
        val controller = controller(store, categorizer = sorting(store, text, sorts))
        controller.categorizeNow(project)
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)

        controller.categorizeNow(project)
        assertEquals(PipelinePhase.Categorizing, controller.state.value.phase)
        advanceUntilIdle()

        (1L..30L).forEach { assertEquals(ItemStatus.Sorted, store.status(it)) }
        assertEquals(0, events.count("load"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    /** A Resume double-tap: the second categorize-only start is absorbed, so left-out items are not sent twice. */
    @Test
    fun categorizeNow_twice_categorizesOnce() = runTest {
        val store = store(item(1, ItemStatus.Analyzed))
        val controller = controller(store)

        controller.categorizeNow(project)
        controller.categorizeNow(project)
        advanceUntilIdle()
        assertEquals(1, events.count("categorize"))

        // Started again later (nothing running): categorizes again.
        controller.categorizeNow(project)
        advanceUntilIdle()
        assertEquals(2, events.count("categorize"))
    }

    /** A categorize-only start while the run is already categorizing (its gate held) is absorbed too. */
    @Test
    fun categorizeNow_whileCategorizing_isAbsorbed() = runTest {
        val store = store(*(1L..30L).map { item(it, ItemStatus.Analyzed) }.toTypedArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val text = FakeTextModel(events)
        val gate = text.gate(2)
        val controller = controller(store, categorizer = sorting(store, text, sorts))
        controller.categorizeNow(project)
        runCurrent()
        assertEquals(PipelinePhase.Categorizing, controller.state.value.phase)

        controller.categorizeNow(project)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, sorts.runs.size)
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    /** New media during a categorize-only run still gets its own analysis run afterwards. */
    @Test
    fun start_duringCategorizeOnly_analyzesAfterIt() = runTest {
        val store = store(item(1, ItemStatus.Analyzed))
        val controller = controller(store)
        controller.categorizeNow(project)
        store.add(item(2))
        controller.start(project)
        advanceUntilIdle()

        assertEquals(ItemStatus.Analyzed, store.status(2))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test
    fun categorizeNow_refusesAProjectThatIsNotOpen() = runTest {
        val store = store(item(1, ItemStatus.Analyzed))
        val controller = controller(store, isCurrent = { false })

        controller.categorizeNow(project)
        advanceUntilIdle()

        assertEquals(0, events.count("categorize"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test
    fun categorizeNow_duringAnalysis_categorizesOnceAtTheEnd() = runTest {
        val store = store(item(1))
        val gate = model.gate(1)
        val controller = controller(store)
        controller.start(project)
        runCurrent()

        controller.categorizeNow(project)
        gate.complete(false)
        advanceUntilIdle()

        assertEquals(1, events.count("categorize"))
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    @Test
    fun arbiter_runsOneJobAtATime() = runTest {
        val store = store(item(1))
        val controller = controller(store)
        val binning = CompletableDeferred<Unit>()
        val other = launch { arbiter.withJob(JobKind.Binning) { binning.await() } }
        runCurrent()
        assertEquals(JobKind.Binning, arbiter.active.value)

        controller.start(project)
        runCurrent()
        // Analysis waits for the other job to let go of the models.
        assertEquals(0, events.count("load"))

        binning.complete(Unit)
        advanceUntilIdle()
        other.join()

        assertEquals(1, events.count("load"))
        assertEquals(1, events.count("categorize"))
        assertNull(arbiter.active.value)
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
    }

    // --- Task 7 fix round 1 ---

    /** 30 Analyzed items, an existing category, and the sorting model held at its second chunk. */
    private class Sorting(val store: FakeStore, val sorts: FakeSortStore, val text: FakeTextModel, val secondChunk: CompletableDeferred<Unit>)

    private fun sortingAtSecondChunk(): Sorting {
        val store = store(*(1L..30L).map { item(it, ItemStatus.Analyzed) }.toTypedArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val text = FakeTextModel(events)
        return Sorting(store, sorts, text, text.gate(2))
    }

    /** I1: a start while categorizing launches a run that waits; Stop must still stop the categorization. */
    @Test
    fun stopDuringCategorizing_withAWaitingRun_stopsBoth() = runTest {
        val s = sortingAtSecondChunk()
        val controller = controller(s.store, categorizer = sorting(s.store, s.text, s.sorts))
        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Categorizing, controller.state.value.phase)
        controller.start(project) // Media added: a fresh run waits for this one.
        runCurrent()

        controller.stop()
        runCurrent()
        s.secondChunk.complete(Unit) // Would let a categorization that was not cancelled go on.
        advanceUntilIdle()

        assertEquals(1, s.sorts.applyCalls)
        (11L..30L).forEach { assertEquals(ItemStatus.Analyzed, s.store.status(it)) }
        assertEquals(1, events.count("text.release"))
        assertEquals(1, events.count("text.load"))
        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)
    }

    /** I1: a project switch during categorizing (with a run waiting) does not wait for the categorization to end. */
    @Test
    fun stopAndReset_duringCategorizingWithAWaitingRun_returnsPromptly() = runTest {
        val s = sortingAtSecondChunk()
        val controller = controller(s.store, categorizer = sorting(s.store, s.text, s.sorts))
        controller.start(project)
        runCurrent()
        controller.start(project)
        runCurrent()

        var returned = false
        launch {
            controller.stopAndReset()
            returned = true
        }
        runCurrent()

        assertTrue("stopAndReset waited for the categorization", returned)
        assertEquals(1, events.count("text.release"))
        assertEquals(1, s.sorts.applyCalls)
        s.secondChunk.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, s.sorts.applyCalls)
    }

    /** M3: Pause has nothing to hold during Categorizing; it is ignored rather than latched. */
    @Test
    fun pauseDuringCategorizing_isIgnored() = runTest {
        val s = sortingAtSecondChunk()
        val controller = controller(s.store, categorizer = sorting(s.store, s.text, s.sorts))
        controller.start(project)
        runCurrent()

        controller.pause()
        s.secondChunk.complete(Unit)
        advanceUntilIdle()

        (1L..30L).forEach { assertEquals(ItemStatus.Sorted, s.store.status(it)) }
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertFalse(PipelinePhase.Categorizing.canPause)
        assertTrue(PipelinePhase.Describing.canPause)
        assertFalse(PipelinePhase.Paused.canPause)
        assertFalse(PipelinePhase.Idle.canPause)
    }
}
