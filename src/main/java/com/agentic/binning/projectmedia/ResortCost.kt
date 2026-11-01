package com.agentic.binning.projectmedia

import java.util.Locale
import kotlin.math.ceil

/** Dollars per million tokens. */
data class ModelPrice(val inputPerMillion: Double, val outputPerMillion: Double)

data class TokenEstimate(val input: Int, val output: Int)

/**
 * The cost note on the Re-sort sheet when Claude is picked. A local estimate from the text that
 * would be sent (no extra API call), shown as "about $X" with the per-million prices next to it,
 * so the figure can be checked.
 */
object ResortCost {
    /** Claude Sonnet 5.5 (`claude-sonnet-5-5`), first-party API prices. */
    val SONNET_5_5 = ModelPrice(inputPerMillion = 2.0, outputPerMillion = 10.0)

    /** System prompt, rules and the JSON schema. */
    const val PROMPT_TOKENS = 1_500
    /** Item id, kind and field labels around each item's text. */
    const val ITEM_OVERHEAD_TOKENS = 20
    /** Per existing category in the list sent along: name, description, count. */
    const val CATEGORY_IN_TOKENS = 30
    /** Per item in the reply: its id, category and friendly name. */
    const val ITEM_OUT_TOKENS = 30
    /** Per category in the reply: name and one-line description. */
    const val CATEGORY_OUT_TOKENS = 40

    /**
     * [itemTextChars] is the length of each item's text as it would be sent (description, actions,
     * tags, suggested name). About 4 characters per token for English text.
     */
    fun estimate(itemTextChars: List<Int>, existingCategories: Int, expectedCategories: Int): TokenEstimate {
        val itemsIn = itemTextChars.sumOf { ceil(it / 4.0).toInt() + ITEM_OVERHEAD_TOKENS }
        val input = PROMPT_TOKENS + itemsIn + existingCategories * CATEGORY_IN_TOKENS
        val output = itemTextChars.size * ITEM_OUT_TOKENS + expectedCategories * CATEGORY_OUT_TOKENS
        return TokenEstimate(input, output)
    }

    fun dollars(tokens: TokenEstimate, price: ModelPrice): Double =
        tokens.input * price.inputPerMillion / 1e6 + tokens.output * price.outputPerMillion / 1e6

    /** "less than $0.01", "about $0.05", "about $1.20". */
    fun label(dollars: Double): String =
        if (dollars < 0.005) "less than \$0.01" else "about \$" + String.format(Locale.US, "%.2f", dollars)

    /** Token counts as the sheet shows them: "850", "12K", "1.2M". */
    fun tokensLabel(n: Int): String = when {
        n < 1_000 -> "$n"
        n < 1_000_000 -> "${Math.round(n / 1_000.0)}K"
        else -> String.format(Locale.US, "%.1fM", n / 1e6)
    }
}
