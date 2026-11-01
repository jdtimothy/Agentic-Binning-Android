package com.agentic.binning.sorting

import com.agentic.binning.db.CategoryInfo

/**
 * What one sorting backend can take per call: its context size in tokens, the most items in one
 * assignment chunk, the most items sampled for the proposal pass, and the most new categories one
 * chunk may add once categories exist (proposed or written); a chunk with none may add
 * [SortSchema.MAX_NEW_CATEGORIES].
 */
data class ChunkBudget(
    val contextTokens: Int,
    val maxChunk: Int,
    val proposeSample: Int,
    val maxNewWithCategories: Int = SortSchema.MAX_NEW_CATEGORIES,
)

/** Budgets measured on the phone in the Plan 2a spike (docs/superpowers/notes/2026-10-30-plan-2a-spike.md). */
object SortBudgets {
    val CLAUDE = ChunkBudget(200_000, 200, 2000)
    /** LFM3-1.7B makes a long tail of one-item categories, so after the first ones it adds few. */
    val LFM3_1_7B = ChunkBudget(16384, 10, 20, maxNewWithCategories = 3)
}

/**
 * [proposal] is the sample for a proposal pass, or null when none is needed. [chunks] cover every
 * item exactly once, in order, each sized for the category list of the given token size; the
 * categorizer instead takes one [ChunkPlanner.nextChunk] at a time, as the list grows.
 */
data class ChunkPlan(val proposal: List<SortItem>?, val chunks: List<List<SortItem>>)

object ChunkPlanner {
    /** A call's prompt and reply must fit this share of the context. */
    const val FIT_FRACTION = 0.85
    const val REPLY_TOKENS_PER_ITEM = 30
    const val REPLY_TOKENS_BASE = 200
    const val PROPOSE_REPLY_TOKENS = 400

    /** The user message's own words around the item and category lines. */
    private const val FRAME_TOKENS = 100

    /** `chars / 3.5`, rounded up. The measured ratio on real item lines is about 4.1, so this runs high. */
    fun estimateTokens(text: String): Int = (2 * text.length + 6) / 7

    private val systemTokens: Int by lazy { estimateTokens(SortPrompt.system()) }

    /** The prompt tokens of [categories] as the assignment prompt lists them, one line each. */
    fun categoryTokens(categories: List<CategoryInfo>): Int = categories.sumOf { estimateTokens(SortPrompt.categoryLine(it)) + 1 }

    /** Prompt (system, categories, items) plus the reply estimate for one assignment chunk. */
    fun chunkTokens(chunk: List<SortItem>, categoryTokens: Int = 0): Int =
        fixedAssignTokens(categoryTokens) + chunk.sumOf { itemTokens(it) }

    /** Prompt and reply estimate for a proposal pass over [sample]. */
    fun proposeTokens(sample: List<SortItem>): Int =
        fixedProposeTokens() + sample.sumOf { estimateTokens(SortPrompt.proposeLine(it)) + 1 }

    private fun fixedAssignTokens(categoryReserve: Int) = systemTokens + FRAME_TOKENS + categoryReserve + REPLY_TOKENS_BASE

    private fun fixedProposeTokens() = systemTokens + FRAME_TOKENS + PROPOSE_REPLY_TOKENS

    private fun itemTokens(item: SortItem) = estimateTokens(SortPrompt.itemLine(item)) + 1 + REPLY_TOKENS_PER_ITEM

    fun plan(items: List<SortItem>, hasCategories: Boolean, budget: ChunkBudget, categoryTokens: Int = 0): ChunkPlan {
        val limit = limitOf(budget)
        val proposal = if (!hasCategories && items.size > budget.maxChunk) proposalSample(items, budget, limit) else null
        val chunks = ArrayList<List<SortItem>>()
        var start = 0
        while (start < items.size) {
            val chunk = nextChunk(items.subList(start, items.size), budget, categoryTokens)
            chunks += chunk
            start += chunk.size
        }
        return ChunkPlan(proposal, chunks)
    }

    /**
     * The longest prefix of [items] (at most `maxChunk`) whose assignment prompt, with a category list
     * of [categoryTokens], fits the budget. Never empty for non-empty [items]: a single oversized item
     * still gets a chunk of its own rather than being dropped.
     */
    fun nextChunk(items: List<SortItem>, budget: ChunkBudget, categoryTokens: Int): List<SortItem> {
        val limit = limitOf(budget)
        var used = fixedAssignTokens(categoryTokens)
        var n = 0
        for (item in items) {
            val cost = itemTokens(item)
            if (n > 0 && (n >= budget.maxChunk || used + cost > limit)) break
            used += cost
            n++
        }
        return items.subList(0, n).toList()
    }

    private fun limitOf(budget: ChunkBudget) = (FIT_FRACTION * budget.contextTokens).toInt()

    /** Up to `proposeSample` items taken at even steps through [items], cut back until the prompt fits. */
    private fun proposalSample(items: List<SortItem>, budget: ChunkBudget, limit: Int): List<SortItem> {
        var n = minOf(budget.proposeSample, items.size)
        val lineTokens = items.map { estimateTokens(SortPrompt.proposeLine(it)) + 1 }
        while (n > 1) {
            val sample = evenIndices(items.size, n)
            if (fixedProposeTokens() + sample.sumOf { lineTokens[it] } <= limit) break
            n--
        }
        return evenIndices(items.size, maxOf(n, 1)).map { items[it] }
    }

    private fun evenIndices(size: Int, n: Int): List<Int> = (0 until n).map { (it.toLong() * size / n).toInt() }
}
