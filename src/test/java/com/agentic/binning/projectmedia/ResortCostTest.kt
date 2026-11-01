package com.agentic.binning.projectmedia

import com.agentic.binning.model.ClaudeTextModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ResortCostTest {
    /**
     * [ResortCost.SONNET_5_5] is the price of the model 2a's Claude backend calls. If that model
     * changes, this fails so the price is reviewed with it (Plan 2b Task 6).
     */
    @Test fun price_isForTheModelClaudeSorts() {
        assertEquals("claude-sonnet-5-5", ClaudeTextModel.MODEL)
    }

    @Test fun estimate_countsPromptItemsAndCategories() {
        // 2 items of 400 characters: 100 tokens each plus 20 overhead.
        val t = ResortCost.estimate(listOf(400, 400), existingCategories = 3, expectedCategories = 2)
        assertEquals(ResortCost.PROMPT_TOKENS + 2 * 120 + 3 * 30, t.input)
        assertEquals(2 * 30 + 2 * 40, t.output)
    }

    @Test fun hawaiiProject_isAFewCents() {
        // 43 items of about 900 characters with 12 categories. (The mockup's "about $0.05" was illustrative.)
        val t = ResortCost.estimate(List(43) { 900 }, existingCategories = 12, expectedCategories = 12)
        assertEquals("12K", ResortCost.tokensLabel(t.input))
        assertEquals("about \$0.04", ResortCost.label(ResortCost.dollars(t, ResortCost.SONNET_5_5)))
    }

    @Test fun dollars_usesPerMillionPrices() {
        val d = ResortCost.dollars(TokenEstimate(1_000_000, 100_000), ResortCost.SONNET_5_5)
        assertEquals(3.0, d, 1e-9)
    }

    @Test fun label_roundsAndFloors() {
        assertEquals("less than \$0.01", ResortCost.label(0.004))
        assertEquals("about \$0.01", ResortCost.label(0.006))
        assertEquals("about \$1.20", ResortCost.label(1.2))
    }

    @Test fun tokensLabel() {
        assertEquals("850", ResortCost.tokensLabel(850))
        assertEquals("12K", ResortCost.tokensLabel(12_345))
        assertEquals("1.2M", ResortCost.tokensLabel(1_234_567))
    }
}
