package com.agentic.binning.ui.media

import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.projectmedia.MediaChip
import com.agentic.binning.projectmedia.MediaRow
import com.agentic.binning.projectmedia.MatchedIn
import com.agentic.binning.projectmedia.RESORT_BUSY
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectMediaViewModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun day(d: Int) = LocalDate.of(2026, 7, d).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    private fun row(
        id: Long,
        status: ItemStatus = ItemStatus.Sorted,
        categoryId: Long? = 1,
        kind: ItemKind = ItemKind.Photo,
        shot: String? = "wide",
        takenAt: Long? = day(14),
        name: String? = "Item $id",
        durationMs: Long? = null,
        matchedIn: MatchedIn? = null,
    ) = MediaRow(
        id = id, kind = kind, durationMs = durationMs, takenAt = takenAt, status = status, categoryId = categoryId,
        friendlyName = name, suggestedName = null, shotType = shot, nameSetByUser = false, categorySetByUser = false,
        currentPath = "/sdcard/DCIM/$id.jpg", matchedIn = matchedIn,
    )

    /** Beach (1, model), Mine (2, user), Empty (3, no items). */
    private val categories = MutableStateFlow(
        listOf(
            CategoryEntity(1, "Beach", "", "model", null),
            CategoryEntity(2, "Mine", "", "user", null),
            CategoryEntity(3, "Empty", "", "model", null),
        ),
    )

    private val rows = MutableStateFlow(
        listOf(
            row(1),
            row(2, categoryId = 2, kind = ItemKind.Video, shot = "close", takenAt = day(15), durationMs = 42_000),
            row(3, status = ItemStatus.Analyzed, categoryId = null),
            row(4, status = ItemStatus.Failed, categoryId = null, shot = null),
            row(5, categoryId = 1, shot = "close", takenAt = day(15)),
        ),
    )

    private inner class FakeSource : MediaSource {
        val queries = mutableListOf<String?>()
        override val projectName = "Hawaii Photos"

        override fun rows(ftsQuery: String?): Flow<List<MediaRow>> {
            queries += ftsQuery
            // A search keeps the rows whose name has the query's first word.
            val word = ftsQuery?.trim('"', '*', ' ')?.substringBefore('"')?.lowercase()
            return this@ProjectMediaViewModelTest.rows.map { all ->
                if (word == null) all else all.filter { it.friendlyName.orEmpty().lowercase().contains(word) }
            }
        }

        override fun categories(): Flow<List<CategoryEntity>> = this@ProjectMediaViewModelTest.categories

        override fun image(row: MediaRow): File = File(row.currentPath)
    }

    private val source = FakeSource()
    private val sources = MutableStateFlow<MediaSource?>(source)
    private val phase = MutableStateFlow<PipelinePhase?>(PipelinePhase.Idle)
    private var categorizeCalls = 0

    private fun TestScope.vm() = ProjectMediaViewModel(
        source = sources,
        phase = phase,
        onCategorizeNow = { categorizeCalls++ },
        zone = zone,
        io = StandardTestDispatcher(testScheduler),
        // Not backgroundScope: advanceUntilIdle() does not wait for background work.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun defaultView_hasChipsResultLineAndTiles() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value

        assertEquals("Hawaii Photos", s.projectName)
        assertEquals(5, s.itemCount)
        assertEquals(3, s.categoryCount)
        // All, then the attention chips, then every category (yours locked), in order.
        assertEquals(listOf("All", "Waiting", "Failed", "Beach", "Mine", "Empty"), s.chips.map { it.label })
        assertEquals(listOf(5, 1, 1, 2, 1, 0), s.chips.map { it.count })
        assertEquals(listOf(false, true, true, false, false, false), s.chips.map { it.attention })
        assertEquals(listOf(false, false, false, false, true, false), s.chips.map { it.locked })
        assertTrue(s.chips.first().selected)
        assertEquals("All categories · 5 items", s.resultText)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), s.tiles.map { it.id })
        // Badges: duration on the video, Waiting and Failed on those items, none on sorted photos.
        assertEquals("0:42", s.tiles[1].duration)
        assertEquals(TileBadge.Waiting, s.tiles[2].badge)
        assertEquals(TileBadge.Failed, s.tiles[3].badge)
        assertNull(s.tiles[0].badge)
        assertFalse(s.empty)
        assertNull(s.busyReason)
    }

    @Test
    fun chip_togglesAndTappingTheSelectedOneGoesBackToAll() = runTest {
        val vm = vm()
        advanceUntilIdle()

        vm.onChip(MediaChip.Category(1))
        advanceUntilIdle()
        assertEquals(listOf(1L, 5L), vm.state.value.tiles.map { it.id })
        assertEquals("Beach · 2 items", vm.state.value.resultText)
        assertTrue(vm.state.value.chips.single { it.chip == MediaChip.Category(1) }.selected)

        vm.onChip(MediaChip.Waiting)
        advanceUntilIdle()
        assertEquals(listOf(3L), vm.state.value.tiles.map { it.id })
        assertEquals("Waiting to be categorized · 1 item", vm.state.value.resultText)

        vm.onChip(MediaChip.Waiting)
        advanceUntilIdle()
        assertEquals(MediaChip.All, vm.state.value.chips.single { it.selected }.chip)
        assertEquals(5, vm.state.value.tiles.size)
    }

    @Test
    fun attentionChips_hideAtZeroUnlessSelected() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onChip(MediaChip.Failed)
        advanceUntilIdle()

        rows.value = rows.value.filter { it.status != ItemStatus.Failed && it.status != ItemStatus.Analyzed }
        advanceUntilIdle()

        val labels = vm.state.value.chips.map { it.label }
        assertEquals(listOf("All", "Failed", "Beach", "Mine", "Empty"), labels)
        assertEquals(0, vm.state.value.chips.single { it.chip == MediaChip.Failed }.count)
        assertEquals("No items match these filters.", vm.state.value.noMatchText)
    }

    @Test
    fun filters_kindToggleShotAndDate() = runTest {
        val vm = vm()
        advanceUntilIdle()

        vm.onKind(ItemKind.Video)
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.state.value.tiles.map { it.id })
        // Chip counts follow the other filters.
        assertEquals(1, vm.state.value.chips.first().count)

        vm.onKind(null)
        vm.openFilters()
        advanceUntilIdle()
        val sheet = vm.state.value.filtersSheet!!
        // Each choice with its count; only shot types and days that occur are offered.
        assertEquals(listOf("Any shot type" to 5, "Wide" to 2, "Close-up" to 2), sheet.shots.map { it.label to it.count })
        assertEquals(listOf("Any date" to 5, "Jul 14" to 3, "Jul 15" to 2), sheet.days.map { it.label to it.count })
        assertEquals(5, sheet.showCount)

        vm.onDraftShot("close")
        advanceUntilIdle()
        assertEquals(2, vm.state.value.filtersSheet!!.showCount)
        // The draft does not touch the grid until Show.
        assertEquals(5, vm.state.value.tiles.size)
        // Day counts follow the drafted shot type.
        assertEquals(listOf(2, 0, 2), vm.state.value.filtersSheet!!.days.map { it.count })

        vm.onDraftDay(LocalDate.of(2026, 7, 15))
        vm.applyFilters()
        advanceUntilIdle()
        assertNull(vm.state.value.filtersSheet)
        assertEquals(listOf(2L, 5L), vm.state.value.tiles.map { it.id })
        assertEquals(listOf("Close-up", "Jul 15"), vm.state.value.setFilters.map { it.label })

        vm.onClearFilter(SetFilterKey.Shot)
        advanceUntilIdle()
        assertEquals(listOf("Jul 15"), vm.state.value.setFilters.map { it.label })
        assertEquals(listOf(2L, 5L), vm.state.value.tiles.map { it.id })

        vm.onClearFilter(SetFilterKey.Day)
        advanceUntilIdle()
        assertTrue(vm.state.value.setFilters.isEmpty())
        assertEquals(5, vm.state.value.tiles.size)
    }

    @Test
    fun closingTheFiltersSheet_dropsTheDraft() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.openFilters()
        vm.onDraftShot("wide")
        vm.closeFilters()
        advanceUntilIdle()

        assertNull(vm.state.value.filtersSheet)
        assertEquals(5, vm.state.value.tiles.size)
        vm.openFilters()
        advanceUntilIdle()
        assertNull(vm.state.value.filtersSheet!!.draftShot)
    }

    @Test
    fun search_isDebouncedAndGoesThroughFtsQuery() = runTest {
        val vm = vm()
        advanceUntilIdle()
        source.queries.clear()

        vm.onQuery("i")
        vm.onQuery("it")
        vm.onQuery("item 3")
        advanceTimeBy(100)
        runCurrent()
        // Nothing searched yet: still inside the debounce.
        assertTrue(source.queries.isEmpty())
        assertEquals("item 3", vm.state.value.query)

        advanceTimeBy(100)
        advanceUntilIdle()
        // One search, of the last text, made safe by FtsQuery.
        assertEquals(listOf("\"item\" \"3\"*"), source.queries)
        val s = vm.state.value
        assertEquals("5 results for “item 3”", s.resultText)
        // Categories with no matches drop out while searching; Empty never matches.
        assertFalse(s.chips.any { it.label == "Empty" })
    }

    @Test
    fun search_clearIsImmediate_andShowsNoMatchText() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onQuery("gecko")
        advanceUntilIdle()
        assertEquals("Nothing in Hawaii Photos matches “gecko”.", vm.state.value.noMatchText)
        assertTrue(vm.state.value.tiles.isEmpty())

        vm.onClearQuery()
        // No debounce on clearing: every item is back without waiting.
        runCurrent()
        assertEquals("", vm.state.value.query)
        assertEquals(5, vm.state.value.tiles.size)
        assertNull(vm.state.value.noMatchText)
    }

    @Test
    fun search_showsWhereATileMatched() = runTest {
        rows.value = listOf(row(1, matchedIn = MatchedIn.Tags), row(2, matchedIn = MatchedIn.Name))
        val vm = vm()
        vm.onQuery("item")
        advanceUntilIdle()

        assertEquals(listOf("Matched in tags", null), vm.state.value.tiles.map { it.matchedText })
    }

    @Test
    fun selectMode_toggleSelectAllAndExit() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.state.value.selection)

        vm.onTileLongPress(2)
        advanceUntilIdle()
        assertEquals(setOf(2L), vm.state.value.selection)
        assertTrue(vm.state.value.tiles.single { it.id == 2L }.selected)

        vm.onTileClick(3)
        vm.onTileClick(2)
        advanceUntilIdle()
        assertEquals(setOf(3L), vm.state.value.selection)

        // Select all respects the current filter.
        vm.onChip(MediaChip.Category(1))
        advanceUntilIdle()
        vm.onToggleSelectAll()
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L, 5L), vm.state.value.selection)
        assertTrue(vm.state.value.allVisibleSelected)

        vm.onToggleSelectAll()
        advanceUntilIdle()
        assertEquals(setOf(3L), vm.state.value.selection)

        vm.onExitSelect()
        advanceUntilIdle()
        assertNull(vm.state.value.selection)

        vm.onStartSelect()
        advanceUntilIdle()
        assertEquals(emptySet<Long>(), vm.state.value.selection)
    }

    @Test
    fun removedItems_leaveTheSelection() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onTileLongPress(4)
        vm.onTileClick(5)
        rows.value = rows.value.filter { it.id != 4L }
        advanceUntilIdle()

        assertEquals(setOf(5L), vm.state.value.selection)
    }

    @Test
    fun dock_followsCountsAndTheRunningJob() = runTest {
        val messages = mutableListOf<String>()
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.toList(messages) }
        advanceUntilIdle()

        // 3 sorted, 1 waiting, 1 failed: Categorize now is offered and Bin Files waits.
        val idle = vm.state.value.status
        assertEquals("3 files ready to bin · 1 waiting to be categorized", idle.text)
        assertFalse(idle.binFilesEnabled)
        vm.onCategorizeNow()
        assertEquals(1, categorizeCalls)
        vm.onBinFiles()
        advanceUntilIdle()
        assertEquals("Bin Files waits for 1 item to be categorized", messages.last())

        phase.value = PipelinePhase.Categorizing
        advanceUntilIdle()
        assertEquals("Categorizing…", vm.state.value.status.text)
        assertEquals(RESORT_BUSY, vm.state.value.busyReason)
        // Disabled while a run is in progress, never queued.
        vm.onCategorizeNow()
        vm.onResort()
        advanceUntilIdle()
        assertEquals(1, categorizeCalls)
        assertEquals(RESORT_BUSY, messages.last())

        phase.value = PipelinePhase.Idle
        rows.value = rows.value.map { if (it.status == ItemStatus.Analyzed) it.copy(status = ItemStatus.Sorted, categoryId = 1) else it }
        advanceUntilIdle()
        assertTrue(vm.state.value.status.binFilesEnabled)
        vm.onBinFiles()
        advanceUntilIdle()
        assertEquals(BIN_FILES_LATER, messages.last())
    }

    @Test
    fun emptyProject_andNoProject() = runTest {
        rows.value = emptyList()
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        assertEquals("Nothing to bin yet", vm.state.value.status.text)
        assertNull(vm.state.value.noMatchText)

        sources.value = null
        advanceUntilIdle()
        assertFalse(vm.state.value.hasProject)
    }

    @Test
    fun projectSwitch_resetsQueryFiltersAndSelection() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onChip(MediaChip.Category(1))
        vm.onKind(ItemKind.Photo)
        vm.onQuery("item")
        vm.onTileLongPress(1)
        advanceUntilIdle()

        sources.value = FakeSource()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("", s.query)
        assertEquals(MediaChip.All, s.chips.single { it.selected }.chip)
        assertNull(s.kind)
        assertNull(s.selection)
        assertEquals(5, s.tiles.size)
    }
}
