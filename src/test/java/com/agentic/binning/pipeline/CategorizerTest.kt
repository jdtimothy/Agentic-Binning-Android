package com.agentic.binning.pipeline

import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.SortRunEntity
import com.agentic.binning.model.SortUnavailable
import com.agentic.binning.model.SortingUnavailableException
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.sorting.ChunkBudget
import com.agentic.binning.sorting.SORT_PROMPT_VERSION
import com.agentic.binning.sorting.SortBudgets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorizerTest {
    private val project = ProjectRef("La Jolla Trip", "/sdcard/Agentic Binning/La Jolla Trip")
    private val events = Events()
    private val warnings = mutableListOf<String>()
    private val loggedErrors = mutableListOf<Throwable?>()
    private val log = PipelineLog { message, error ->
        warnings += message
        loggedErrors += error
    }

    private fun analyzed(vararg ids: Long) = FakeStore(ids.map { item(it, ItemStatus.Analyzed) }, events)

    private fun categorizer(
        sortStore: SortStore,
        model: FakeTextModel,
        choice: SortingModelChoice = SortingModelChoice.Lfm3_1_7B,
        budgets: (SortingModelChoice) -> ChunkBudget = ::sortBudgetFor,
    ) = SortingCategorizer(sortStore, modelFor = { model }, choice = { choice }, budgets = budgets, log = log, clock = { 1000L })

    private val progress = mutableListOf<Pair<Int, Int>>()

    private suspend fun SortingCategorizer.run() = categorize(project) { done, total -> progress += done to total }

    @Test
    fun categorize_appliesValidChunks() = runTest {
        val store = analyzed(*(1L..25L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.Done(sorted = 25, newCategories = 0), outcome)
        (1L..25L).forEach {
            assertEquals(ItemStatus.Sorted, store.status(it))
            assertEquals("Beach", sorts.categoryOf(it))
        }
        // LFM3-1.7B takes 10 items per chunk; the reply cap is 30 per item plus 600.
        assertEquals(3, model.calls)
        assertEquals(listOf(900, 900, 750), model.maxTokens)
        assertEquals((1L..10L).toList(), idsIn(model.prompts[0]))
        assertEquals((21L..25L).toList(), idsIn(model.prompts[2]))
        // The local grammar fixes the chunk's ids and offers the existing names.
        assertTrue(model.formats[0].grammar.contains("\"\\\"Beach\\\"\""))
        assertEquals(listOf("text.load", "text.complete", "apply", "text.complete", "apply", "text.complete", "apply", "text.release"), events.log)
        assertEquals(listOf(0 to 25, 10 to 25, 20 to 25, 25 to 25), progress)
        val run = sorts.runs.single()
        assertEquals("done", run.outcome)
        assertEquals(25, run.items)
        assertEquals(25, run.sorted)
        assertEquals(0, run.newCategories)
        assertEquals(model.modelId, run.modelId)
        assertEquals(SORT_PROMPT_VERSION, run.promptVersion)
        assertEquals(1000L, run.finishedAt)
    }

    @Test
    fun nothingUncategorized_isNothingToDo_withoutLoadingOrARunRow() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Pending)), events)
        val sorts = FakeSortStore(store, events)
        val model = FakeTextModel(events)

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.NothingToDo, outcome)
        assertTrue(events.log.isEmpty())
        assertTrue(sorts.runs.isEmpty())
    }

    @Test
    fun budget_followsTheSortingModel() = runTest {
        assertEquals(SortBudgets.CLAUDE, sortBudgetFor(SortingModelChoice.Claude))
        assertEquals(SortBudgets.LFM3_1_7B, sortBudgetFor(SortingModelChoice.Lfm3_1_7B))

        val store = analyzed(*(1L..15L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val claude = FakeTextModel(events, modelId = "claude-sonnet-5-5")

        assertEquals(CategorizeOutcome.Done(15, 0), categorizer(sorts, claude, SortingModelChoice.Claude).run())
        assertEquals(1, claude.calls)
        assertEquals(30 * 15 + 600, claude.maxTokens.single())
        // Claude reads the JSON schema.
        assertTrue(claude.formats.single().jsonSchema.containsKey("properties"))
    }

    @Test
    fun missingId_retriedOnceWithProblems_thenOnlyThatItemIsLeftOut() = runTest {
        val store = analyzed(*(1L..15L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        // Chunk 1 (items 1-10) leaves out item 10 twice; chunk 2 is answered normally.
        val missingTen = assignAll(itemsPrompt(1L..9L))
        model.replies += missingTen
        model.replies += missingTen

        val outcome = categorizer(sorts, model).run()

        assertEquals(3, model.calls)
        assertFalse(model.prompts[0].contains("rejected"))
        assertTrue(model.prompts[1].contains("Your previous reply was rejected"))
        assertTrue(model.prompts[1].contains("Missing ids (assign every listed item): 10"))
        // I3: the retry's valid part is applied; only item 10 stays uncategorized.
        assertEquals(ItemStatus.Analyzed, store.status(10))
        assertNull(sorts.categoryOf(10))
        ((1L..9L) + (11L..15L)).forEach { assertEquals(ItemStatus.Sorted, store.status(it)) }
        outcome as CategorizeOutcome.Partial
        assertEquals(14, outcome.sorted)
        assertEquals(1, outcome.left)
        val run = sorts.runs.single()
        assertEquals("partial", run.outcome)
        assertEquals(14, run.sorted)
        assertEquals("${outcome.message} · left out: 1", run.message)
        assertEquals(listOf(0 to 15, 10 to 15, 15 to 15), progress)
    }

    @Test
    fun unreadableTwice_skipsTheChunk() = runTest {
        val store = analyzed(*(1L..15L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        model.replies += "no idea"
        model.replies += "still no idea"

        val outcome = categorizer(sorts, model).run()

        assertEquals(3, model.calls)
        (1L..10L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        (11L..15L).forEach { assertEquals(ItemStatus.Sorted, store.status(it)) }
        outcome as CategorizeOutcome.Partial
        assertEquals(10, outcome.left)
        assertTrue(outcome.message, outcome.message.contains("10"))
        assertEquals("${outcome.message} · skipped chunks: 1", sorts.runs.single().message)
    }

    @Test
    fun unreadableReply_isRetried_andThenApplied() = runTest {
        val store = analyzed(1, 2, 3)
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        model.replies += "I would put these at the beach."

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.Done(3, 0), outcome)
        assertEquals(2, model.calls)
        assertTrue(model.prompts[1].contains("Your previous reply was rejected"))
    }

    @Test
    fun retry_appliesTheValidAssignments_andLeavesOutTheRest() = runTest {
        val store = analyzed(1, 2, 3, 4)
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        // Item 4 has no usable category, both times.
        val bad = """{"new_categories":[],"assignments":[{"id":1,"category":"Beach","name":"One"},""" +
            """{"id":2,"category":"Beach","name":"Two"},{"id":3,"category":"Beach","name":"Three"},""" +
            """{"id":4,"category":"","name":"Four"}]}"""
        model.replies += bad
        model.replies += bad

        val outcome = categorizer(sorts, model).run()

        assertEquals(2, model.calls)
        (1L..3L).forEach { assertEquals("Beach", sorts.categoryOf(it)) }
        assertEquals(ItemStatus.Analyzed, store.status(4))
        assertEquals(CategorizeOutcome.Partial(sorted = 3, left = 1, message = (outcome as CategorizeOutcome.Partial).message), outcome)
        assertEquals("partial", sorts.runs.single().outcome)
        assertEquals(3, sorts.runs.single().sorted)
    }

    @Test
    fun proposalPass_offersItsCategories_butWritesOnlyTheUsedOnes() = runTest {
        // No categories yet and more items than one LFM chunk: a proposal pass runs first.
        val store = analyzed(*(1L..12L).toList().toLongArray())
        val sorts = FakeSortStore(store, events)
        val model = FakeTextModel(events)
        model.replies += """{"new_categories":[{"name":"Beach","description":"Sand and waves"},""" +
            """{"name":"Food","description":"Meals"},{"name":"Mountains","description":"Peaks and trails"}]}"""
        model.answer = { user ->
            val a = idsIn(user).joinToString(",") { """{"id":$it,"category":"${if (it % 2 == 0L) "Beach" else "Food"}","name":"Item $it"}""" }
            """{"new_categories":[],"assignments":[$a]}"""
        }

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.Done(sorted = 12, newCategories = 2), outcome)
        assertEquals(3, model.calls)
        assertTrue(model.prompts[0].startsWith("Propose categories"))
        // The proposed categories are listed to every assignment chunk...
        assertTrue(model.prompts[1].contains("Mountains\t0\topen\tPeaks and trails"))
        assertTrue(model.formats[1].grammar.contains("Mountains"))
        // ...but only the ones an assignment used are written.
        assertEquals(listOf("Beach", "Food"), sorts.categoryNames().sorted())
        assertEquals("Sand and waves", sorts.categories().single { it.name == "Beach" }.description)
        assertEquals(2, sorts.runs.single().newCategories)
    }

    @Test
    fun unreadableProposal_isSkipped_andChunksStillRun() = runTest {
        val store = analyzed(*(1L..12L).toList().toLongArray())
        val sorts = FakeSortStore(store, events)
        val model = FakeTextModel(events)
        model.replies += "no idea"

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.Done(sorted = 12, newCategories = 1), outcome)
    }

    @Test
    fun claudeUnavailable_leavesItemsUncategorized_withReason() = runTest {
        val store = analyzed(1, 2)
        val sorts = FakeSortStore(store, events)
        val model = FakeTextModel(events).apply {
            failure = SortingUnavailableException(SortUnavailable.NoApiKey, "No Claude API key saved")
        }

        val outcome = categorizer(sorts, model, SortingModelChoice.Claude).run()

        assertEquals(CategorizeOutcome.Unavailable(SortUnavailable.NoApiKey), outcome)
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals(ItemStatus.Analyzed, store.status(2))
        assertEquals("text.release", events.log.last())
        val run = sorts.runs.single()
        assertEquals("unavailable", run.outcome)
        assertEquals(0, run.sorted)
        // The reason and message are logged, never a stack trace that could carry response text.
        assertTrue(warnings.any { it.contains("NoApiKey") && it.contains("No Claude API key saved") })
        assertTrue(loggedErrors.all { it == null })
    }

    /** M5: the run row keeps what happened before the failure. */
    @Test
    fun unexpectedModelError_rowMessageKeepsTheDetails() = runTest {
        val store = analyzed(*(1L..25L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        // Chunk 1 is unreadable twice and is skipped; chunk 2 then fails outright.
        model.replies += "no idea"
        model.replies += "no idea"
        model.answer = { throw IllegalStateException("Generation failed: QNN error 1002") }

        val outcome = categorizer(sorts, model).run()

        outcome as CategorizeOutcome.Partial
        assertEquals(25, outcome.left)
        val run = sorts.runs.single()
        assertEquals("failed", run.outcome)
        assertEquals("${outcome.message} · skipped chunks: 1", run.message)
    }

    @Test
    fun modelNotDownloaded_isUnavailable_andStillReleases() = runTest {
        val sorts = FakeSortStore(analyzed(1), events)
        val model = FakeTextModel(events).apply {
            loadFailure = SortingUnavailableException(SortUnavailable.ModelNotDownloaded, "LFM3-1.7B is not downloaded")
        }

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.Unavailable(SortUnavailable.ModelNotDownloaded), outcome)
        assertEquals(listOf("text.load", "text.release"), events.log)
        assertEquals("unavailable", sorts.runs.single().outcome)
    }

    @Test
    fun unexpectedModelError_endsPartial_andNeverThrows() = runTest {
        val store = analyzed(1, 2)
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events).apply { failure = IllegalStateException("Generation failed: QNN error 1002") }

        val outcome = categorizer(sorts, model).run()

        assertTrue(outcome is CategorizeOutcome.Partial)
        assertEquals(2, (outcome as CategorizeOutcome.Partial).left)
        assertEquals("text.release", events.log.last())
        assertEquals("failed", sorts.runs.single().outcome)
        assertTrue(loggedErrors.all { it == null })
    }

    @Test
    fun itemCategorizedMeanwhile_isSkipped_notOverwritten() = runTest {
        val store = analyzed(1, 2, 3)
        val sorts = FakeSortStore(store, events)
        val trips = sorts.seedCategory("Trips", createdBy = "user")
        sorts.seedCategory("Beach")
        val model = FakeTextModel(events)
        // The user files item 2 while the model is thinking.
        sorts.beforeApply = {
            store.items[2] = store.items.getValue(2).copy(categoryId = trips, categorySetByUser = true, status = ItemStatus.Sorted)
        }

        val outcome = categorizer(sorts, model).run()

        assertEquals("Trips", sorts.categoryOf(2))
        assertEquals("Beach", sorts.categoryOf(1))
        assertEquals(CategorizeOutcome.Done(sorted = 2, newCategories = 0), outcome)
        val run = sorts.runs.single()
        assertEquals(2, run.sorted)
        assertTrue(run.message.orEmpty(), run.message.orEmpty().contains("already categorized: 1"))
    }

    /** I2: once categories exist, an LFM chunk may add at most 3; the retry keeps the 3 largest. */
    @Test
    fun lfmChunk_withCategories_addsAtMostThreeNewOnes() = runTest {
        val store = analyzed(*(1L..10L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        // Four new categories: A holds 4 items, B 3, C 2 and D 1, so D's item is the one left out.
        val sizes = listOf("Alpha" to 4, "Bravo" to 3, "Charlie" to 2, "Delta" to 1)
        val names = sizes.flatMap { (n, k) -> List(k) { n } }
        val four = """{"new_categories":[],"assignments":[""" +
            (1..10).joinToString(",") { """{"id":$it,"category":"${names[it - 1]}","name":"Item $it"}""" } + "]}"
        model.replies += four
        model.replies += four

        val outcome = categorizer(sorts, model).run()

        assertTrue(model.prompts[0], model.prompts[0].contains("Add at most 3 new categories in this reply."))
        assertTrue(model.prompts[1], model.prompts[1].contains("Too many new categories (4; at most 3 allowed)"))
        assertEquals(listOf("Beach", "Alpha", "Bravo", "Charlie"), sorts.categoryNames())
        assertEquals(ItemStatus.Analyzed, store.status(10))
        assertEquals(CategorizeOutcome.Partial(9, 1, (outcome as CategorizeOutcome.Partial).message), outcome)
    }

    @Test
    fun newCategoryCap_isTwelve_forClaude_andForAFirstLfmChunk() = runTest {
        val claudeSorts = FakeSortStore(analyzed(1, 2), events).apply { seedCategory("Beach") }
        val claude = FakeTextModel(events)
        categorizer(claudeSorts, claude, SortingModelChoice.Claude).run()
        assertFalse(claude.prompts[0], claude.prompts[0].contains("new categories in this reply"))

        val lfm = FakeTextModel(events)
        categorizer(FakeSortStore(analyzed(1, 2), events), lfm).run()
        assertFalse(lfm.prompts[0], lfm.prompts[0].contains("new categories in this reply"))
    }

    /** I2: chunks are sized with the real category list, so many categories make smaller chunks. */
    @Test
    fun manyCategories_shrinkTheChunks() = runTest {
        val budget = ChunkBudget(4000, 10, 20)
        val few = FakeTextModel(events)
        categorizer(FakeSortStore(analyzed(*(1L..10L).toList().toLongArray()), events).apply { seedCategory("Beach") }, few, budgets = { budget }).run()
        assertEquals((1L..10L).toList(), idsIn(few.prompts[0]))

        val store = analyzed(*(1L..10L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply {
            seedCategory("Beach")
            (1..50).forEach { seedCategory("Topic $it", description = "A topic with a long description that fills the prompt, number $it of many. ".repeat(2)) }
        }
        val many = FakeTextModel(events)
        val outcome = categorizer(sorts, many, budgets = { budget }).run()
        assertTrue(idsIn(many.prompts[0]).size in 1 until 10)
        assertEquals((1L..10L).toList(), many.prompts.flatMap { idsIn(it) })
        assertEquals(CategorizeOutcome.Done(10, 0), outcome)
    }

    /** M2: a reply cut off at its length cap splits the chunk in half instead of retrying the same size. */
    @Test
    fun lengthStop_splitsTheChunkInHalf() = runTest {
        val store = analyzed(1, 2, 3, 4)
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        model.replies += """{"new_categories":[],"assignments":[{"id":1,"category":"Be"""
        model.stops += "length"

        val outcome = categorizer(sorts, model).run()

        assertEquals(CategorizeOutcome.Done(4, 0), outcome)
        assertEquals(listOf(listOf(1L, 2L, 3L, 4L), listOf(1L, 2L), listOf(3L, 4L)), model.prompts.map { idsIn(it) })
        assertTrue(model.prompts.none { it.contains("rejected") })
        assertEquals(listOf(720, 660, 660), model.maxTokens)
        assertEquals(listOf(0 to 4, 4 to 4), progress)
    }

    @Test
    fun maxTokensStop_splitsDownToOneItem_thenRetriesAsUsual() = runTest {
        val store = analyzed(1, 2, 3, 4)
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events)
        repeat(3) {
            model.replies += "{"
            model.stops += "max_tokens"
        }

        val outcome = categorizer(sorts, model, SortingModelChoice.Claude).run()

        assertEquals(CategorizeOutcome.Done(4, 0), outcome)
        assertEquals(
            listOf(listOf(1L, 2L, 3L, 4L), listOf(1L, 2L), listOf(1L), listOf(1L), listOf(2L), listOf(3L, 4L)),
            model.prompts.map { idsIn(it) },
        )
        // A one-item chunk can't be split: its truncated reply is retried with the problem.
        assertTrue(model.prompts[3].contains("Your previous reply was rejected"))
    }

    /** M7: a run row a process death left open is closed as interrupted when the next run starts. */
    @Test
    fun openRunRow_isClosedAsInterrupted_atTheNextRunsStart() = runTest {
        val sorts = FakeSortStore(analyzed(1, 2), events).apply { seedCategory("Beach") }
        sorts.runs += SortRunEntity(
            id = 1, startedAt = 5L, finishedAt = null, modelId = "m", promptVersion = "sort-v2",
            items = 2, sorted = 0, newCategories = 0, outcome = "partial", message = null,
        )

        categorizer(sorts, FakeTextModel(events)).run()

        val (stale, current) = sorts.runs
        assertEquals(1000L, stale.finishedAt)
        assertEquals("partial", stale.outcome)
        assertEquals("Interrupted", stale.message)
        assertEquals(2L, current.id)
        assertEquals("done", current.outcome)
    }

    /** M4: the notice a cold launch shows comes from the newest finished run that wasn't stopped. */
    @Test
    fun storedSortNotice_readsTheLastRunsSentence() {
        fun run(id: Long, outcome: String, message: String?) = SortRunEntity(
            id = id, startedAt = id, finishedAt = id + 1, modelId = "m", promptVersion = "sort-v3",
            items = 10, sorted = 0, newCategories = 0, outcome = outcome, message = message,
        )
        val unavailable = run(3, "unavailable", "No connection; categorizing will try again after the next run")
        val partial = run(2, "partial", "Categorized 3 of 10; 7 will be tried again after the next run · left out: 7")
        assertEquals(null, storedSortNotice(emptyList()))
        assertEquals(unavailable.message, storedSortNotice(listOf(unavailable, partial)))
        // The details after " · " are for the record only.
        assertEquals("Categorized 3 of 10; 7 will be tried again after the next run", storedSortNotice(listOf(partial)))
        // A done run clears it; a Stop or an interrupted run keeps the one before (as the live notice does).
        assertEquals(null, storedSortNotice(listOf(run(4, "done", null), unavailable)))
        assertEquals(unavailable.message, storedSortNotice(listOf(run(5, "partial", "Stopped · left out: 1"), run(4, "partial", "Interrupted"), unavailable)))
        assertEquals(null, storedSortNotice(listOf(run(6, "failed", null))))
    }

    /** N1: splitting is bounded: a chunk that always stops at its cap costs 6 calls, then waits. */
    @Test
    fun alwaysCutOff_200ItemChunk_makesSixCalls_andLeavesEveryItemOut() = runTest {
        val store = analyzed(*(1L..200L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = FakeTextModel(events).apply {
            answer = { "{" }
            stopFor = { "max_tokens" }
        }

        val outcome = categorizer(sorts, model, SortingModelChoice.Claude).run()

        assertEquals(6, model.calls)
        // A length stop splits at once: no call repeats a chunk at the same size.
        assertEquals(listOf(200, 100, 50, 25, 13, 7), model.prompts.map { idsIn(it).size })
        assertEquals(CategorizeOutcome.Partial(0, 200, (outcome as CategorizeOutcome.Partial).message), outcome)
        (1L..200L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        val run = sorts.runs.single()
        assertTrue(run.message.orEmpty(), run.message.orEmpty().contains("left out: 200"))
        assertFalse(run.message.orEmpty(), run.message.orEmpty().contains("skipped chunks"))
    }

    @Test
    fun cutOffOnlyAbove50_sortsEverythingWithinTheCap() = runTest {
        val store = analyzed(*(1L..100L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = cutOffAbove(50)

        val outcome = categorizer(sorts, model, SortingModelChoice.Claude).run()

        assertEquals(listOf(100, 50, 50), model.prompts.map { idsIn(it).size })
        assertEquals(CategorizeOutcome.Done(100, 0), outcome)
    }

    /** 200 items cut off above 50 need 7 calls (200, 100, 50, 50, 100, 50, 50): the last 50 wait. */
    @Test
    fun cutOffAbove50_on200Items_stopsAtTheCap_andLeavesTheRestOut() = runTest {
        val store = analyzed(*(1L..200L).toList().toLongArray())
        val sorts = FakeSortStore(store, events).apply { seedCategory("Beach") }
        val model = cutOffAbove(50)

        val outcome = categorizer(sorts, model, SortingModelChoice.Claude).run()

        assertEquals(listOf(200, 100, 50, 50, 100, 50), model.prompts.map { idsIn(it).size })
        assertEquals(CategorizeOutcome.Partial(150, 50, (outcome as CategorizeOutcome.Partial).message), outcome)
        (151L..200L).forEach { assertEquals(ItemStatus.Analyzed, store.status(it)) }
        assertTrue(sorts.runs.single().message.orEmpty().contains("left out: 50"))
    }

    /** Replies that are cut off (stop `max_tokens`) for prompts of more than [limit] items, else valid. */
    private fun cutOffAbove(limit: Int) = FakeTextModel(events).apply {
        answer = { user -> if (idsIn(user).size > limit) "{" else assignAll(user) }
        stopFor = { user -> if (idsIn(user).size > limit) "max_tokens" else "end_turn" }
    }

    /** The assignment prompt the categorizer sends for [ids], enough for [idsIn] and the replies above. */
    private fun itemsPrompt(ids: LongRange) = "Items to sort (id...):\n" + ids.joinToString("\n") { "$it\tphoto\tName $it" } + "\n"
}
