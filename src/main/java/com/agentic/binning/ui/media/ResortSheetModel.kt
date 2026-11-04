package com.agentic.binning.ui.media

import com.agentic.binning.projectmedia.RESORT_BUSY
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.ui.media.ProjectMediaViewModel.Companion.countOf
import com.agentic.binning.ui.settings.SortingOptions
import com.agentic.binning.ui.settings.sortingRowStatus
import com.agentic.binning.ui.settings.specOf

/** One model in the Re-sort sheet's picker: what Settings lists (Q14). */
data class ResortModelRow(
    val choice: SortingModelChoice,
    val title: String,
    /** What it sends or where it runs; the reason when it can't run. */
    val subtitle: String,
    /** The model Settings uses: the sheet's default, marked "Settings choice". */
    val settingsChoice: Boolean,
    val selected: Boolean,
    val unavailable: Boolean,
)

/** The Re-sort sheet (mockup state 7). There is no cost note (Q13). */
data class ResortUi(
    val intro: String,
    val rows: List<ResortModelRow>,
    val modelNote: String,
    val keepEdits: Boolean,
    val keepText: String,
    val changes: List<String>,
    val unchanged: List<String>,
    val buttonLabel: String,
    val enabled: Boolean,
    /** Why the button is off; null when it is on. */
    val reason: String?,
)

/**
 * What the sheet counts: the items a run re-sorts with Keep my edits ticked ([withEditsKept], which
 * leaves out items whose category the user set) and unticked, failed ones it leaves out, and what
 * the user set.
 */
data class ResortCounts(val withEditsKept: Int, val withoutEdits: Int, val failed: Int, val myCategories: Int, val myItems: Int)

const val NOTHING_TO_RESORT = "Nothing is analyzed yet"

/** Keep my edits leaves nothing to re-sort: every analyzed item is in a category the user set. */
const val ALL_KEPT = "Every analyzed item is in a category you set"

private const val ON_DEVICE_SUBTITLE = "Runs on this phone"

/** "LFM3-1.7B", or "Claude" for the cloud model. */
fun modelName(choice: SortingModelChoice): String = specOf(choice)?.displayName ?: "Claude"

/** "the 3 categories and 5 items", leaving out a zero; null when both are zero. */
private fun mine(c: ResortCounts): String? =
    listOfNotNull(
        c.myCategories.takeIf { it > 0 }?.let { if (it == 1) "1 category" else "$it categories" },
        c.myItems.takeIf { it > 0 }?.let { countOf(it, "item") },
    ).takeIf { it.isNotEmpty() }?.joinToString(" and ")

/**
 * The sheet for [counts] with [chosen] picked. [unavailable] holds why a model can't run (no key, not
 * downloaded); [busy] is a run in progress (Q12), which comes first among the reasons the button is off.
 */
fun resortUi(
    counts: ResortCounts,
    settingsChoice: SortingModelChoice,
    chosen: SortingModelChoice,
    keepEdits: Boolean,
    unavailable: Map<SortingModelChoice, String>,
    busy: Boolean,
): ResortUi {
    val n = if (keepEdits) counts.withEditsKept else counts.withoutEdits
    val failed = when (counts.failed) {
        0 -> ""
        1 -> " 1 failed item is left out."
        else -> " ${counts.failed} failed items are left out."
    }
    val mine = mine(counts)
    val reason = when {
        busy -> RESORT_BUSY
        chosen in unavailable -> unavailable.getValue(chosen)
        n == 0 -> if (keepEdits && counts.withoutEdits > 0) ALL_KEPT else NOTHING_TO_RESORT
        else -> null
    }
    return ResortUi(
        intro = "Sorts ${countOf(n, "analyzed item")} again from scratch. The categories may change completely: " +
            "new names, merges and splits.$failed",
        rows = SortingOptions.map { o ->
            ResortModelRow(
                choice = o.choice,
                title = o.title,
                subtitle = unavailable[o.choice] ?: if (o.spec == null) sortingRowStatus(o.choice, null).text else ON_DEVICE_SUBTITLE,
                settingsChoice = o.choice == settingsChoice,
                selected = o.choice == chosen,
                unavailable = o.choice in unavailable,
            )
        },
        modelNote = if (specOf(chosen) == null) {
            "Sends the text of ${countOf(n, "item")} (descriptions, actions, tags, suggested names) and the category list " +
                "to Claude. Never photos."
        } else {
            "Runs on this phone's NPU. Analysis waits until it finishes."
        },
        keepEdits = keepEdits,
        keepText = when {
            mine == null -> "Nothing is set by you yet."
            keepEdits -> "Keeps the $mine you set. New items can still join your categories."
            else -> "Your $mine may be replaced. They stop being locked."
        },
        changes = listOf("Category names and descriptions", "Which items each category holds", "Friendly names that sorting chose"),
        unchanged = listOf("Any file on the phone. Only the database changes.", "Files move only when you press Bin Files."),
        buttonLabel = "Re-sort ${countOf(n, "item")}",
        enabled = reason == null,
        reason = reason,
    )
}
