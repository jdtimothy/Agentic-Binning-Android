package com.agentic.binning.pipeline

import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.projectmedia.RESORT_BUSY
import com.agentic.binning.projectmedia.RESORT_NOT_OPEN
import com.agentic.binning.projectmedia.ResortRequest
import com.agentic.binning.projectmedia.ResortStart
import com.agentic.binning.settings.SortingModelChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 2b Task 9: Categorize now and Re-sort as Sorting jobs of [PipelineController]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResortJobTest {
    private val project = ProjectRef("La Jolla Trip", "/sdcard/Agentic Binning/La Jolla Trip")
    private val events = Events()
    private val model = FakeModel(events)
    private val text = FakeTextModel(events).apply { answer = { assignAll(it, "Park") } }

    /** Settings' sorting model; Re-sort must not read it. */
    private var settingsReads = 0
    private val modelsUsed = mutableListOf<SortingModelChoice>()

    private fun TestScope.controller(
        store: FakeStore,
        sorts: FakeSortStore,
        isCurrent: (ProjectRef) -> Boolean = { true },
        sortingAvailable: suspend (SortingModelChoice) -> String? = { null },
    ) = PipelineController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        engineFactory = { cap -> AnalysisEngine(model, FakePreparer(missing = emptySet(), undecodable = emptySet()), store, maxFrames = cap) },
        model = model,
        store = store,
        categorizer = SortingCategorizer(
            sorts,
            modelFor = { modelsUsed += it; text },
            choice = { settingsReads++; SortingModelChoice.Lfm3_1_7B },
            log = PipelineLog.None,
        ),
        arbiter = ModelArbiter(),
        isCurrent = isCurrent,
        prepareResort = sorts::prepareResort,
        sortingAvailable = sortingAvailable,
    )

    private fun sorted(id: Long, categoryId: Long, name: String = "Name $id") =
        item(id, ItemStatus.Sorted).copy(categoryId = categoryId, friendlyName = name)

    /**
     * Four sorted items: 1 and 4 in the model's "Beach"; 2 placed by the user in their own "Mine";
     * 3 in "Beach" with a name the user typed.
     */
    private class Library(val store: FakeStore, val sorts: FakeSortStore)

    private fun library(): Library {
        val store = FakeStore(emptyList(), events)
        val sorts = FakeSortStore(store, events)
        val beach = sorts.seedCategory("Beach")
        val mine = sorts.seedCategory("Mine", createdBy = "user")
        store.add(sorted(1, beach))
        store.add(sorted(2, mine).copy(categorySetByUser = true))
        store.add(sorted(3, beach, name = "Dad's kite").copy(nameSetByUser = true))
        store.add(sorted(4, beach))
        return Library(store, sorts)
    }

    private fun FakeStore.get(id: Long): ItemEntity = items.getValue(id)

    @Test
    fun resort_keepsLockedNamesAndCategories() = runTest {
        val lib = library()
        val controller = controller(lib.store, lib.sorts)

        assertEquals(ResortStart.Started, controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B, keepEdits = true)))
        advanceUntilIdle()

        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.sortNotice)
        // The user's category and its item are untouched; everything else is sorted again.
        assertEquals("Mine", lib.sorts.categoryOf(2))
        assertTrue(lib.store.get(2).categorySetByUser)
        listOf(1L, 3L, 4L).forEach { assertEquals("Park", lib.sorts.categoryOf(it)) }
        // A name the user typed survives the reassignment; the others take the model's.
        assertEquals("Dad's kite", lib.store.get(3).friendlyName)
        assertTrue(lib.store.get(3).nameSetByUser)
        assertEquals("Item 1", lib.store.get(1).friendlyName)
        // The emptied model category is gone; the user's stays theirs.
        assertEquals(listOf("Mine", "Park"), lib.sorts.categoryNames().sorted())
        assertEquals("user", lib.sorts.categories().single { it.name == "Mine" }.createdBy)
        // The clearing ran inside the Sorting job, right before the sorting model loaded.
        assertEquals(listOf("prepareResort:true", "text.load"), events.log.take(2))
    }

    @Test
    fun resort_withoutKeepEdits_clearsLocks() = runTest {
        val lib = library()
        val controller = controller(lib.store, lib.sorts)

        assertEquals(ResortStart.Started, controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B, keepEdits = false)))
        advanceUntilIdle()

        (1L..4L).forEach {
            assertEquals("Park", lib.sorts.categoryOf(it))
            assertFalse(lib.store.get(it).nameSetByUser)
            assertFalse(lib.store.get(it).categorySetByUser)
        }
        assertEquals("Item 3", lib.store.get(3).friendlyName)
        assertEquals(listOf("Park"), lib.sorts.categoryNames())
    }

    @Test
    fun resort_usesTheChosenModel_notSettings() = runTest {
        val lib = library()
        val controller = controller(lib.store, lib.sorts)

        controller.resort(project, ResortRequest(SortingModelChoice.Claude))
        advanceUntilIdle()

        assertEquals(listOf(SortingModelChoice.Claude), modelsUsed)
        assertEquals(0, settingsReads)

        // Categorize now still follows Settings.
        lib.store.add(item(5, ItemStatus.Analyzed))
        controller.categorizeNow(project)
        advanceUntilIdle()

        assertEquals(listOf(SortingModelChoice.Claude, SortingModelChoice.Lfm3_1_7B), modelsUsed)
        assertEquals(1, settingsReads)
    }

    @Test
    fun resort_isRefusedWhileARunIsInProgress() = runTest {
        // Analyzing.
        val lib = library()
        lib.store.add(item(5))
        lib.store.add(item(6))
        val controller = controller(lib.store, lib.sorts)
        val gate = model.gate(5)
        controller.start(project)
        runCurrent()
        assertEquals(PipelinePhase.Describing, controller.state.value.phase)
        assertEquals(ResortStart.Refused(RESORT_BUSY), controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B)))

        // Paused (still holding the vision model).
        controller.pause()
        gate.complete(false)
        advanceUntilIdle()
        assertEquals(PipelinePhase.Paused, controller.state.value.phase)
        assertEquals(ResortStart.Refused(RESORT_BUSY), controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B)))

        // Categorizing.
        val sortGate = text.gate(1)
        controller.resume()
        runCurrent()
        assertEquals(PipelinePhase.Categorizing, controller.state.value.phase)
        assertEquals(ResortStart.Refused(RESORT_BUSY), controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B)))

        sortGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals(0, events.log.count { it.startsWith("prepareResort") })
        // The refusals left the user's edits and the sorted items as they were.
        assertEquals("Beach", lib.sorts.categoryOf(1))
        assertEquals("Mine", lib.sorts.categoryOf(2))
    }

    @Test
    fun resort_isRefusedForAProjectThatIsNotOpen() = runTest {
        val lib = library()
        val controller = controller(lib.store, lib.sorts, isCurrent = { false })

        assertEquals(ResortStart.Refused(RESORT_NOT_OPEN), controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B)))
        advanceUntilIdle()

        assertTrue(events.log.isEmpty())
        assertEquals("Beach", lib.sorts.categoryOf(1))
    }

    @Test
    fun categorizeNow_sortsOnlyWaitingItems() = runTest {
        val lib = library()
        lib.store.add(item(5, ItemStatus.Analyzed))
        val controller = controller(lib.store, lib.sorts)

        controller.categorizeNow(project)
        advanceUntilIdle()

        assertEquals("Park", lib.sorts.categoryOf(5))
        // Already sorted items keep their categories; nothing is cleared.
        listOf(1L, 3L, 4L).forEach { assertEquals("Beach", lib.sorts.categoryOf(it)) }
        assertEquals("Mine", lib.sorts.categoryOf(2))
        assertEquals(listOf(5L), idsIn(text.prompts.single()))
        assertEquals(0, events.log.count { it.startsWith("prepareResort") })
    }

    @Test
    fun resort_releasesItsModelOnCancel() = runTest {
        val lib = library()
        val controller = controller(lib.store, lib.sorts)
        text.gate(1)

        controller.resort(project, ResortRequest(SortingModelChoice.Lfm3_1_7B))
        runCurrent()
        assertEquals(PipelinePhase.Categorizing, controller.state.value.phase)

        controller.stop()
        advanceUntilIdle()

        assertEquals(PipelinePhase.Stopped, controller.state.value.phase)
        assertEquals(1, events.count("text.release"))
        // P1: what was cleared stays waiting for the next Categorize now; the user's item stays put.
        listOf(1L, 3L, 4L).forEach {
            assertEquals(ItemStatus.Analyzed, lib.store.status(it))
            assertNull(lib.store.get(it).categoryId)
        }
        assertEquals("Mine", lib.sorts.categoryOf(2))
    }

    @Test
    fun resort_unavailableBackend_clearsNothing() = runTest {
        val lib = library()
        val controller = controller(lib.store, lib.sorts, sortingAvailable = { "Add your API key to use Claude" })

        controller.resort(project, ResortRequest(SortingModelChoice.Claude))
        advanceUntilIdle()

        assertEquals(PipelinePhase.Idle, controller.state.value.phase)
        assertEquals("Add your API key to use Claude", controller.state.value.sortNotice)
        assertTrue(events.log.isEmpty())
        assertTrue(modelsUsed.isEmpty())
        listOf(1L, 3L, 4L).forEach { assertEquals("Beach", lib.sorts.categoryOf(it)) }
        assertEquals("Mine", lib.sorts.categoryOf(2))
    }
}
