package com.agentic.binning.move

import com.agentic.binning.db.ItemStatus

/** What the planner needs of one item. */
data class BinItem(
    val id: Long,
    val status: ItemStatus,
    val categoryId: Long?,
    val friendlyName: String?,
    val currentPath: String,
    /** The file's name when it was added; its extension is kept as it is. */
    val originalName: String,
)

enum class LeftOutReason { Failed, Missing, NoCategory }

/** An item Bin Files leaves where it is; the report lists it (B8). */
data class LeftOut(val itemId: Long, val reason: LeftOutReason)

/** One file to move, as written to `move_step`. */
data class PlannedStep(
    val itemId: Long,
    val source: String,
    val dest: String,
    /** Another project also holds the source: copy it and leave the original (P8). */
    val keepSource: Boolean,
    /** The destination got a " 2"-style number because its plain name was taken. */
    val numbered: Boolean,
)

data class BinPlan(
    val steps: List<PlannedStep>,
    /** Items found at their target but not yet marked binned: marked, never moved (B9). */
    val inPlace: List<Long>,
    /** Items whose file is gone: marked Missing, left out, never blocking (B5). */
    val becameMissing: List<Long>,
    val leftOut: List<LeftOut>,
)

/**
 * Step 1 of Bin Files and Save: decides, by path rather than status (plan-2-notes), which items move,
 * which are already in place, and which stay where they are. Destinations come from [MovePlanner], so
 * nothing is ever overwritten.
 */
object BinPlanner {
    /**
     * @param categoryNames category id → its current name; the folder is the name made safe.
     * @param existingPaths every file in the target folders now, so a taken name gets a number.
     * @param exists whether a source file is still there.
     * @param sharedPaths paths another listed project also holds (P8): copied, never moved.
     * @param only limits the plan to these items (a Save); null plans every item.
     */
    fun plan(
        projectDir: String,
        items: List<BinItem>,
        categoryNames: Map<Long, String>,
        existingPaths: Set<String>,
        exists: (String) -> Boolean,
        sharedPaths: Set<String> = emptySet(),
        only: Set<Long>? = null,
    ): BinPlan {
        val inPlace = ArrayList<Long>()
        val missing = ArrayList<Long>()
        val leftOut = ArrayList<LeftOut>()
        val requests = ArrayList<MoveRequest>()
        for (item in items.sortedBy { it.id }) {
            if (only != null && item.id !in only) continue
            val category = item.categoryId?.let(categoryNames::get)
            when {
                item.status == ItemStatus.Failed -> leftOut += LeftOut(item.id, LeftOutReason.Failed)
                item.status == ItemStatus.Missing -> leftOut += LeftOut(item.id, LeftOutReason.Missing)
                (item.status != ItemStatus.Sorted && item.status != ItemStatus.Binned) || category == null ->
                    leftOut += LeftOut(item.id, LeftOutReason.NoCategory)
                !exists(item.currentPath) -> missing += item.id
                else -> {
                    val request = MoveRequest(item.id, item.currentPath, category, item.friendlyName.orEmpty(), extensionOf(item))
                    if (MovePlanner.isInPlace(projectDir, request)) {
                        if (item.status != ItemStatus.Binned) inPlace += item.id
                    } else {
                        requests += request
                    }
                }
            }
        }
        val byId = requests.associateBy { it.itemId }
        val sharedKeys = sharedPaths.mapTo(HashSet()) { it.lowercase() }
        val steps = MovePlanner.plan(projectDir, requests, existingPaths).map { move ->
            val request = byId.getValue(move.itemId)
            PlannedStep(
                itemId = move.itemId,
                source = move.source,
                dest = move.dest,
                keepSource = move.source.lowercase() in sharedKeys,
                numbered = !move.dest.equals(MovePlanner.plainTarget(projectDir, request), ignoreCase = true),
            )
        }
        return BinPlan(steps, inPlace, missing, leftOut)
    }

    private fun extensionOf(item: BinItem): String {
        val fromOriginal = item.originalName.substringAfterLast('.', "")
        val ext = fromOriginal.ifEmpty { item.currentPath.substringAfterLast('/').substringAfterLast('.', "") }
        return if (ext.isEmpty()) "" else ".$ext"
    }
}
