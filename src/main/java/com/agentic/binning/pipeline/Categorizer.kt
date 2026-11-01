package com.agentic.binning.pipeline

import com.agentic.binning.db.Assignment
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.CategoryInfo
import com.agentic.binning.db.SortRunEntity
import com.agentic.binning.db.UncategorizedItem
import com.agentic.binning.model.ReplyFormat
import com.agentic.binning.model.SortUnavailable
import com.agentic.binning.model.SortingUnavailableException
import com.agentic.binning.model.TextModel
import com.agentic.binning.model.describe
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.sorting.ChunkBudget
import com.agentic.binning.sorting.ChunkPlanner
import com.agentic.binning.sorting.SORT_PROMPT_VERSION
import com.agentic.binning.sorting.SortBudgets
import com.agentic.binning.sorting.SortItem
import com.agentic.binning.sorting.SortParser
import com.agentic.binning.sorting.SortPrompt
import com.agentic.binning.sorting.SortSchema
import com.agentic.binning.sorting.SortValidator
import com.agentic.binning.sorting.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Sorts the open project's analyzed, uncategorized items into categories. */
interface Categorizer {
    /** [onProgress] gets items handled so far of the items to sort, starting with 0 before the model loads. */
    suspend fun categorize(project: ProjectRef, onProgress: (done: Int, total: Int) -> Unit): CategorizeOutcome
}

sealed interface CategorizeOutcome {
    /** No analyzed item was waiting for a category. */
    data object NothingToDo : CategorizeOutcome

    data class Done(val sorted: Int, val newCategories: Int) : CategorizeOutcome

    /** [left] items are still uncategorized; a later run picks them up. [message] is shown to the user. */
    data class Partial(val sorted: Int, val left: Int, val message: String) : CategorizeOutcome

    /**
     * The sorting model can't run now (no key, no network, not downloaded...). Chunks applied before
     * the backend became unavailable stay applied; the rest stay uncategorized for a later run.
     */
    data class Unavailable(val reason: SortUnavailable) : CategorizeOutcome
}

/** What the categorizer reads and writes in the open project's database (`SortDao`). */
interface SortStore {
    suspend fun categories(): List<CategoryInfo>

    /** Analyzed items with no category, oldest first. */
    suspend fun uncategorized(): List<UncategorizedItem>

    /** Every item of the project, for the catch-all limit. */
    suspend fun itemCount(): Int

    /**
     * Writes one chunk all-or-nothing: each assignment's category comes from the existing ones or from
     * [newCategories] (written only once an assignment uses it). An item that has a category by now,
     * whose category the user set, or that no longer exists, is skipped. Returns how many items were
     * assigned. @throws IllegalArgumentException for an unknown category; nothing is written then.
     */
    suspend fun applyChunk(newCategories: List<CategoryEntity>, assignments: List<Assignment>): Int

    suspend fun startRun(run: SortRunEntity): Long

    suspend fun finishRun(id: Long, finishedAt: Long, sorted: Int, newCategories: Int, outcome: String, message: String?)

    /** Closes run rows left open by a process death as `partial` with [message]; returns how many. */
    suspend fun closeOpenRuns(finishedAt: Long, message: String): Int
}

/**
 * The sort notice to show when no live one exists (after a cold launch or a project switch), from
 * the project's finished [runs], newest first: the newest run that wasn't stopped or interrupted
 * decides, as with the live notice (a Stop keeps the one before). Its sentence, without the details
 * kept for the record, unless it finished `done`.
 */
fun storedSortNotice(runs: List<SortRunEntity>): String? {
    val last = runs.firstOrNull { run ->
        val message = run.message.orEmpty()
        listOf(SortingCategorizer.STOPPED, SortingCategorizer.INTERRUPTED).none { message == it || message.startsWith(it + SortingCategorizer.DETAILS_SEPARATOR) }
    } ?: return null
    if (last.outcome == "done") return null
    return last.message?.substringBefore(SortingCategorizer.DETAILS_SEPARATOR)?.takeIf { it.isNotBlank() }
}

/** Chunk sizes and context for each sorting model. */
fun sortBudgetFor(choice: SortingModelChoice): ChunkBudget = when (choice) {
    SortingModelChoice.Claude -> SortBudgets.CLAUDE
    SortingModelChoice.Lfm3_1_7B -> SortBudgets.LFM3_1_7B
}

/**
 * Reads the uncategorized items, loads the chosen sorting model, runs the proposal pass if the
 * [ChunkPlanner] asks for one, then sorts chunk by chunk: complete, parse, validate, and on a rejected
 * or unreadable reply one retry with the problems as feedback. After the retry the valid assignments
 * of a chunk are applied and only the items left out stay uncategorized; a chunk whose retry is still
 * unreadable is skipped (its items stay Analyzed). A reply cut off at its length cap is not retried
 * at the same size: the chunk is split in half and each half sorted on its own, down to one item.
 * A chunk costs at most [SortingCategorizer.MAX_CALLS_PER_CHUNK] model calls across its splits and
 * retries; the items not reached by then are left out for the next run.
 * Each chunk is sized for the category list it is shown and committed on its own, so a Stop keeps
 * the chunks already written. The model is always released, and every call is recorded as a `sort_run` row.
 *
 * Never throws except for cancellation: an unusable backend is [CategorizeOutcome.Unavailable], any
 * other failure [CategorizeOutcome.Partial]. Only the reason and message are logged, never a stack
 * trace, which could carry text from the model's service.
 */
class SortingCategorizer(
    private val store: SortStore,
    private val modelFor: (SortingModelChoice) -> TextModel,
    private val choice: suspend () -> SortingModelChoice,
    private val budgets: (SortingModelChoice) -> ChunkBudget = ::sortBudgetFor,
    private val log: PipelineLog = PipelineLog.None,
    private val clock: () -> Long = System::currentTimeMillis,
) : Categorizer {

    /** Counts for one call. */
    private class Tally(val items: Int) {
        var sorted = 0
        var alreadyCategorized = 0
        var leftOut = 0
        var skippedChunks = 0

        val left: Int get() = items - sorted - alreadyCategorized
    }

    override suspend fun categorize(project: ProjectRef, onProgress: (done: Int, total: Int) -> Unit): CategorizeOutcome {
        val items = store.uncategorized()
        if (items.isEmpty()) return CategorizeOutcome.NothingToDo
        val total = items.size
        onProgress(0, total)

        val chosen = choice()
        val model = modelFor(chosen)
        val before = store.categories()
        closeOpenRuns()
        val runId = store.startRun(
            SortRunEntity(
                startedAt = clock(),
                finishedAt = null,
                modelId = model.modelId,
                promptVersion = SORT_PROMPT_VERSION,
                items = total,
                sorted = 0,
                newCategories = 0,
                outcome = "partial",
                message = null,
            ),
        )
        val tally = Tally(total)
        try {
            model.load()
            val budget = budgets(chosen)
            val sortItems = items.map(SortItem::from)
            val plan = ChunkPlanner.plan(sortItems, hasCategories = before.isNotEmpty(), budget = budget)
            val proposals = plan.proposal?.let { propose(model, it, before) }.orEmpty()
            val projectItems = store.itemCount()
            var done = 0
            while (done < total) {
                currentCoroutineContext().ensureActive()
                // Each chunk is sized for the category list it will be shown, which grows as chunks add to it.
                val (listed, _) = offered(proposals)
                val chunk = ChunkPlanner.nextChunk(sortItems.subList(done, total), budget, ChunkPlanner.categoryTokens(listed))
                sortChunk(model, chunk, proposals, projectItems, budget, tally, CallBudget(MAX_CALLS_PER_CHUNK))
                done += chunk.size
                onProgress(done, total)
            }
            val newCategories = newCategoryCount(before)
            return if (tally.left == 0) {
                finishRun(runId, tally, newCategories, "done", details(tally))
                CategorizeOutcome.Done(tally.sorted, newCategories)
            } else {
                val message = "Categorized ${tally.sorted} of $total; ${tally.left} will be tried again after the next run"
                finishRun(runId, tally, newCategories, if (tally.sorted == 0) "failed" else "partial", joined(message, details(tally)))
                CategorizeOutcome.Partial(tally.sorted, tally.left, message)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finishRun(runId, tally, newCategoryCount(before), "partial", joined(STOPPED, details(tally))) }
            throw e
        } catch (e: SortingUnavailableException) {
            log.warn("Categorizing unavailable (${e.reason}): ${e.message}", null)
            finishRun(runId, tally, newCategoryCount(before), "unavailable", describe(e.reason))
            return CategorizeOutcome.Unavailable(e.reason)
        } catch (e: Exception) {
            log.warn("Categorizing failed (${e.javaClass.simpleName}): ${e.message}", null)
            val message = "Categorizing ran into a problem; ${tally.left} will be tried again after the next run"
            finishRun(runId, tally, newCategoryCount(before), if (tally.sorted == 0) "failed" else "partial", joined(message, details(tally)))
            return CategorizeOutcome.Partial(tally.sorted, tally.left, message)
        } finally {
            withContext(NonCancellable) {
                try {
                    model.release()
                } catch (e: Exception) {
                    log.warn("Could not release the sorting model (${e.javaClass.simpleName}): ${e.message}", null)
                }
            }
        }
    }

    /** The proposal pass: up to 12 broad categories from a sample. An unusable reply just means none. */
    private suspend fun propose(model: TextModel, sample: List<SortItem>, existing: List<CategoryInfo>): List<CategoryEntity> {
        val reply = model.complete(
            SortPrompt.system(),
            SortPrompt.proposeUser(sample),
            ReplyFormat(SortSchema.proposeJsonSchema(), SortSchema.proposeGrammar()),
            PROPOSE_MAX_TOKENS,
        )
        currentCoroutineContext().ensureActive()
        val proposed = SortParser.parse(reply.text)?.newCategories.orEmpty()
        if (proposed.isEmpty()) {
            log.warn("The proposal pass gave no usable categories (stop ${reply.stopReason}); sorting without it", null)
            return emptyList()
        }
        return SortValidator.mergeProposals(proposed, existing)
    }

    /**
     * The categories a chunk is offered: the written ones, then the proposed ones not written yet
     * (offered to every chunk but written only once one is used), with those unwritten proposals.
     */
    private suspend fun offered(proposals: List<CategoryEntity>): Pair<List<CategoryInfo>, List<CategoryEntity>> {
        val written = store.categories()
        val unwritten = proposals.filter { p -> written.none { it.name == p.name } }
        return written + unwritten.map { CategoryInfo(0, it.name, it.description, "model", 0) } to unwritten
    }

    /** Model calls one planned chunk may still make, shared by its splits and retries. */
    private class CallBudget(var left: Int)

    /**
     * Sorts [chunk] with one retry. An unusable reply that stopped at its length cap splits the chunk
     * in half at once instead (each half reads the categories again, so the second sees what the first
     * added). Every call is taken from [calls]; once it is used up, the items still waiting are left
     * out and stay uncategorized for the next run.
     */
    private suspend fun sortChunk(
        model: TextModel,
        chunk: List<SortItem>,
        proposals: List<CategoryEntity>,
        projectItems: Int,
        budget: ChunkBudget,
        tally: Tally,
        calls: CallBudget,
    ) {
        val (listed, unwritten) = offered(proposals)
        // Once categories exist (written or proposed), a chunk may add only the backend's few.
        val maxNew = if (listed.isEmpty()) SortSchema.MAX_NEW_CATEGORIES else budget.maxNewWithCategories
        val format = ReplyFormat(
            SortSchema.assignJsonSchema(),
            SortSchema.assignGrammar(chunk.map { it.id }, listed.map { it.name }),
        )
        val maxTokens = REPLY_TOKENS_PER_ITEM * chunk.size + REPLY_TOKENS_EXTRA
        var feedback = emptyList<String>()
        for (attempt in 1..2) {
            if (calls.left <= 0) {
                tally.leftOut += chunk.size
                log.warn("Reached $MAX_CALLS_PER_CHUNK calls for this chunk; ${chunk.size} items stay uncategorized for the next run", null)
                return
            }
            calls.left--
            val isRetry = attempt == 2
            val reply = model.complete(SortPrompt.system(), SortPrompt.assignUser(chunk, listed, feedback, maxNew), format, maxTokens)
            currentCoroutineContext().ensureActive()
            val parsed = SortParser.parse(reply.text)
            val validation = if (parsed == null) {
                Validation.Rejected(listOf(UNREADABLE))
            } else {
                SortValidator.validate(parsed, chunk, listed, projectItems, isRetry, maxNew)
            }
            when (validation) {
                is Validation.Ok -> {
                    apply(validation, unwritten, chunk, tally)
                    return
                }
                is Validation.Rejected if reply.stopReason in LENGTH_STOPS && chunk.size > 1 -> {
                    val half = (chunk.size + 1) / 2
                    log.warn("Sorting reply for ${chunk.size} items stopped at its length cap; splitting into ${half} and ${chunk.size - half}", null)
                    sortChunk(model, chunk.subList(0, half), proposals, projectItems, budget, tally, calls)
                    sortChunk(model, chunk.subList(half, chunk.size), proposals, projectItems, budget, tally, calls)
                    return
                }
                is Validation.Rejected -> {
                    feedback = validation.problems
                    log.warn(
                        "Sorting reply for ${chunk.size} items rejected (attempt $attempt, stop ${reply.stopReason}): " +
                            validation.problems.joinToString("; "),
                        null,
                    )
                }
            }
        }
        tally.skippedChunks++
        log.warn("Skipped a chunk of ${chunk.size} items after the retry; they stay uncategorized", null)
    }

    private suspend fun apply(ok: Validation.Ok, unwritten: List<CategoryEntity>, chunk: List<SortItem>, tally: Tally) {
        ok.warnings.forEach { log.warn("Sorting: $it", null) }
        ok.leftOut.values.forEach { log.warn("Sorting left an item out: $it", null) }
        val applied = try {
            store.applyChunk(ok.newCategories + unwritten, ok.assignments)
        } catch (e: IllegalArgumentException) {
            tally.skippedChunks++
            log.warn("Could not write a chunk of ${chunk.size} items: ${e.message}", null)
            return
        }
        tally.sorted += applied
        tally.alreadyCategorized += ok.assignments.size - applied
        tally.leftOut += ok.leftOut.size
        if (applied < ok.assignments.size) {
            log.warn("${ok.assignments.size - applied} items were categorized meanwhile; left as they are", null)
        }
    }

    private suspend fun newCategoryCount(before: List<CategoryInfo>): Int = try {
        (store.categories().size - before.size).coerceAtLeast(0)
    } catch (e: Exception) {
        0
    }

    /** What the run row's message adds beyond the outcome: skipped chunks, left-out and already-categorized items. */
    private fun details(t: Tally): String? = listOfNotNull(
        t.skippedChunks.takeIf { it > 0 }?.let { "skipped chunks: $it" },
        t.leftOut.takeIf { it > 0 }?.let { "left out: $it" },
        t.alreadyCategorized.takeIf { it > 0 }?.let { "already categorized: $it" },
    ).joinToString(" · ").ifEmpty { null }

    private fun joined(first: String, second: String?) = if (second == null) first else "$first$DETAILS_SEPARATOR$second"

    /**
     * A run row still open now was left by a process death (runs never overlap), so it can't be told
     * from a running one later: close it. Never throws.
     */
    private suspend fun closeOpenRuns() {
        try {
            val closed = store.closeOpenRuns(clock(), INTERRUPTED)
            if (closed > 0) log.warn("Closed $closed sort run record(s) left open by an earlier run", null)
        } catch (e: Exception) {
            log.warn("Could not close open sort run records (${e.javaClass.simpleName}): ${e.message}", null)
        }
    }

    /** Never throws: the outcome matters more than its record. */
    private suspend fun finishRun(id: Long, t: Tally, newCategories: Int, outcome: String, message: String?) {
        try {
            store.finishRun(id, clock(), t.sorted, newCategories, outcome, message)
        } catch (e: Exception) {
            log.warn("Could not record the sort run (${e.javaClass.simpleName}): ${e.message}", null)
        }
    }

    companion object {
        /** The reply cap per chunk is `30 * chunk.size + 600` tokens. */
        const val REPLY_TOKENS_PER_ITEM = 30
        const val REPLY_TOKENS_EXTRA = 600

        /** Twelve categories of a 40-char name and a 160-char description, with room to spare. */
        const val PROPOSE_MAX_TOKENS = 1000

        /** The message of a run row a process death left open, set when the next run starts. */
        const val INTERRUPTED = "Interrupted"

        /** The message of a run the user (or a project switch) stopped, before any details. */
        const val STOPPED = "Stopped"

        /** Between a run row's sentence and the details that are for the record only. */
        const val DETAILS_SEPARATOR = " · "

        /**
         * The most model calls one planned chunk may make across its splits and retries, so a reply
         * that keeps being cut off (Claude's thinking shares its fixed cap) can't multiply paid calls.
         */
        const val MAX_CALLS_PER_CHUNK = 6

        /** Stop reasons for a reply cut off at its cap: GenieX's `length`, Claude's `max_tokens`. */
        val LENGTH_STOPS = setOf("length", "max_tokens")

        const val UNREADABLE = "The reply was not a JSON object with new_categories and assignments. Reply with the JSON object only."
    }
}
