package com.agentic.binning.sorting

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private fun items(n: Int) = (1..n).map {
        item(
            it.toLong(),
            kind = if (it % 3 == 0) ItemKind.Video else ItemKind.Photo,
            name = "Sea lions basking on the rocks $it",
            tags = listOf("sea lions", "rocks", "sunny", "wildlife", "coast", "animals"),
            description = "Several sea lions rest on a rocky shoreline while waves roll in behind them, with a few birds nearby. ".repeat(3),
            actions = if (it % 3 == 0) listOf("0:00 Pan right across the rocks", "0:04 One slides into the water") else emptyList(),
        )
    }

    @Test fun budgets_areTheSpikeValues() {
        assertEquals(ChunkBudget(200_000, 200, 2000), SortBudgets.CLAUDE)
        assertEquals(ChunkBudget(16384, 10, 20, maxNewWithCategories = 3), SortBudgets.LFM3_1_7B)
        assertEquals(12, SortBudgets.CLAUDE.maxNewWithCategories)
    }

    @Test fun chunkPlan_fitsContextFor1600Items() {
        val all = items(1600)
        val budget = SortBudgets.LFM3_1_7B
        val plan = ChunkPlanner.plan(all, hasCategories = true, budget = budget)
        for (c in plan.chunks) {
            assertTrue(c.size <= budget.maxChunk)
            assertTrue(ChunkPlanner.chunkTokens(c) <= 0.85 * budget.contextTokens)
        }
        assertEquals(all.map { it.id }, plan.chunks.flatten().map { it.id })
        assertEquals(1600, plan.chunks.flatten().map { it.id }.toSet().size)
    }

    @Test fun chunkPlan_claudeBudgetUsesChunksOf200() {
        val plan = ChunkPlanner.plan(items(1600), hasCategories = true, budget = SortBudgets.CLAUDE)
        assertTrue(plan.chunks.all { it.size <= 200 })
        assertEquals(8, plan.chunks.size)
        assertTrue(plan.chunks.all { ChunkPlanner.chunkTokens(it) <= 0.85 * 200_000 })
    }

    @Test fun chunkPlan_smallContextShrinksChunksBelowMaxChunk() {
        val budget = ChunkBudget(4096, 100, 50)
        val all = items(200)
        val plan = ChunkPlanner.plan(all, hasCategories = true, budget = budget)
        assertTrue(plan.chunks.all { it.size < 100 })
        assertTrue(plan.chunks.all { ChunkPlanner.chunkTokens(it) <= 0.85 * 4096 })
        assertEquals(all.map { it.id }, plan.chunks.flatten().map { it.id })
    }

    @Test fun chunkPlan_oneHugeItemStillGetsItsOwnChunk() {
        val huge = item(1, name = "x ".repeat(100_000))
        val plan = ChunkPlanner.plan(listOf(huge, item(2)), hasCategories = true, budget = ChunkBudget(4096, 10, 10))
        assertEquals(listOf(listOf(1L), listOf(2L)), plan.chunks.map { c -> c.map { it.id } })
    }

    @Test fun chunkPlan_noItemsNoChunks() {
        val plan = ChunkPlanner.plan(emptyList(), hasCategories = false, budget = SortBudgets.LFM3_1_7B)
        assertNull(plan.proposal)
        assertTrue(plan.chunks.isEmpty())
    }

    @Test fun chunkPlan_proposalOnlyOnFirstBatchOverChunk() {
        val b = SortBudgets.LFM3_1_7B
        assertNull("has categories", ChunkPlanner.plan(items(500), hasCategories = true, budget = b).proposal)
        assertNull("fits in one chunk", ChunkPlanner.plan(items(10), hasCategories = false, budget = b).proposal)
        val p = ChunkPlanner.plan(items(500), hasCategories = false, budget = b).proposal
        assertNotNull(p)
        assertEquals(b.proposeSample, p!!.size)
    }

    @Test fun chunkPlan_proposalSampleIsEvenAndOrdered() {
        val all = items(100)
        val p = ChunkPlanner.plan(all, hasCategories = false, budget = ChunkBudget(200_000, 10, 10)).proposal!!
        assertEquals(10, p.size)
        assertEquals(1L, p.first().id)
        assertTrue(p.last().id >= 90)
        assertEquals(p.map { it.id }, p.map { it.id }.sorted())
        assertEquals(10, p.map { it.id }.toSet().size)
    }

    @Test fun chunkPlan_proposalTakesEverythingWhenSampleExceedsItems() {
        val p = ChunkPlanner.plan(items(30), hasCategories = false, budget = ChunkBudget(200_000, 10, 2000)).proposal!!
        assertEquals(30, p.size)
    }

    @Test fun chunkPlan_proposalStaysInsideBudget() {
        val budget = ChunkBudget(4096, 10, 2000)
        val p = ChunkPlanner.plan(items(800), hasCategories = false, budget = budget).proposal!!
        assertTrue(p.size in 1 until 800)
        assertTrue(ChunkPlanner.proposeTokens(p) <= 0.85 * 4096)
    }

    /** I2: the category list is sized from the categories themselves, not a fixed reserve. */
    @Test fun categoryTokens_countEveryCategoryLine() {
        val categories = (1..3).map { category("Topic $it", items = it, description = "About topic $it") }
        val expected = categories.sumOf { ChunkPlanner.estimateTokens(SortPrompt.categoryLine(it)) + 1 }
        assertEquals(expected, ChunkPlanner.categoryTokens(categories))
        assertEquals(0, ChunkPlanner.categoryTokens(emptyList()))
    }

    @Test fun nextChunk_shrinksAsTheCategoryListGrows() {
        val all = items(50)
        val budget = SortBudgets.LFM3_1_7B
        val limit = 0.85 * budget.contextTokens
        assertEquals(10, ChunkPlanner.nextChunk(all, budget, categoryTokens = 0).size)
        // Grow the list until five items no longer fit beside it.
        val many = ArrayList<com.agentic.binning.db.CategoryInfo>()
        while (ChunkPlanner.chunkTokens(all.take(5), ChunkPlanner.categoryTokens(many)) <= limit) {
            many += category("Topic number ${many.size}", items = 3, description = "A long description of topic ${many.size} ".repeat(4))
        }
        val tokens = ChunkPlanner.categoryTokens(many)
        val chunk = ChunkPlanner.nextChunk(all, budget, tokens)
        assertTrue("${chunk.size}", chunk.size in 1 until 10)
        assertTrue(ChunkPlanner.chunkTokens(chunk, tokens) <= limit)
        assertTrue(ChunkPlanner.chunkTokens(all.take(chunk.size + 1), tokens) > limit)
        assertEquals(all.take(chunk.size).map { it.id }, chunk.map { it.id })
        // The one-item rule stands: a list too big for any item still yields a chunk of one.
        assertEquals(1, ChunkPlanner.nextChunk(all, budget, categoryTokens = budget.contextTokens).size)
    }

    @Test fun estimateTokens_isCharsOver3Point5RoundedUp() {
        assertEquals(0, ChunkPlanner.estimateTokens(""))
        assertEquals(1, ChunkPlanner.estimateTokens("a"))
        assertEquals(2, ChunkPlanner.estimateTokens("a".repeat(4)))
        assertEquals(2, ChunkPlanner.estimateTokens("a".repeat(7)))
        assertEquals(3, ChunkPlanner.estimateTokens("a".repeat(8)))
    }
}
