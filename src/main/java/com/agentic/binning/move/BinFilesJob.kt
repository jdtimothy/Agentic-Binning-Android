package com.agentic.binning.move

import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.MoveRunEntity

/** What [BinFilesJob] reads and writes in the project database, beyond the engine's [MoveStore]. */
interface BinStore : MoveStore {
    suspend fun binItems(): List<BinItem>

    /** Category id → name. */
    suspend fun categoryNames(): Map<Long, String>

    /** Files the folder check skips (Q2's removed files and B5's left ones). */
    suspend fun removedPaths(): List<String>
    suspend fun dropRemoved(paths: List<String>)

    /** Which of [paths] another listed project also holds (P8): copied, never moved. */
    suspend fun sharedPaths(paths: List<String>): Set<String>

    /** The run and its steps in one transaction, with in-place items marked binned and gone ones Missing. */
    suspend fun writeRun(kind: MoveKind, startedAt: Long, steps: List<PlannedStep>, bytes: Map<Long, Long>, inPlace: List<Long>, missing: List<Long>): Long
    suspend fun markItems(inPlace: List<Long>, missing: List<Long>)

    /** A run the app didn't finish (Running or Paused), to resume (B3). */
    suspend fun openRun(): MoveRunEntity?
    suspend fun run(id: Long): MoveRunEntity?
    suspend fun finishRun(runId: Long, state: RunState, finishedAt: Long, message: String?, removedFolders: List<String>)

    /** Folders a finished step put a file in: the only ones (with `Copied from` folders) ever removed (B12). */
    suspend fun binnedFolders(): List<String>
}

/**
 * Bin Files from start to end: the folder check (B5), the plan (written in one transaction before
 * anything moves), the [MoveEngine], removing empty folders the app made (B12), the check again, and
 * the run's end. [resume] finishes a run the app didn't (B3) without planning again.
 *
 * Cancellation propagates out of [binFiles] and [resume] with the run left open in the database, so
 * it resumes the next time the project opens.
 */
class BinFilesJob(
    private val store: BinStore,
    private val files: MoveFiles,
    notifier: MoveNotifier,
    projectDir: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = projectDir.trimEnd('/')
    private val engine = MoveEngine(store, files, notifier, root)

    /** What a report needs that the database doesn't keep; lost on restart (B8 is open). */
    private data class Note(val inPlace: Int = 0, val checkOk: Boolean? = null)

    private val notes = HashMap<Long, Note>()

    suspend fun binFiles(control: MoveControl, onProgress: (MoveProgress) -> Unit): MoveOutcome {
        store.openRun()?.let { return resume(it.id, control, onProgress) }

        val categories = store.categoryNames()
        var removed = store.removedPaths()
        val gone = removed.filterNot(files::exists)
        if (gone.isNotEmpty()) {
            store.dropRemoved(gone)
            removed = removed - gone.toSet()
        }

        var items = store.binItems()
        val before = check(categories, items, removed)
        val missingBinned = binnedAt(items, before.missing)
        if (missingBinned.isNotEmpty()) {
            store.markItems(emptyList(), missingBinned)
            items = store.binItems()
        }
        if (FolderCheck.blocks(before)) return MoveOutcome.Blocked(before)

        val onDisk = FolderCheck.categoryFiles(root, categories.values, files::listFiles)
        val movable = items.filter { it.status == ItemStatus.Sorted || it.status == ItemStatus.Binned }
        val shared = if (movable.isEmpty()) emptySet() else store.sharedPaths(movable.map { it.currentPath })
        val plan = BinPlanner.plan(root, items, categories, onDisk.toSet(), files::exists, shared)
        val bytes = plan.steps.associate { it.itemId to files.size(it.source) }
        val runId = store.writeRun(MoveKind.Bin, clock(), plan.steps, bytes, plan.inPlace, plan.becameMissing)
        notes[runId] = Note(inPlace = plan.inPlace.size)
        if (plan.steps.isEmpty()) {
            notes[runId] = Note(inPlace = plan.inPlace.size, checkOk = true)
            store.finishRun(runId, RunState.Done, clock(), null, emptyList())
            return MoveOutcome.NothingToMove(plan.inPlace.size)
        }
        return execute(runId, MoveKind.Bin, control, onProgress)
    }

    /** Finishes the open run [runId] (B3); a paused one waits for [control] to resume. */
    suspend fun resume(runId: Long, control: MoveControl, onProgress: (MoveProgress) -> Unit): MoveOutcome {
        val run = store.run(runId) ?: return MoveOutcome.Failed("The run to resume is gone")
        return execute(runId, MoveKind.of(run.kind), control, onProgress)
    }

    /** The report for [runId]: the run and its steps from the database, the left-out items as they are now. */
    suspend fun report(runId: Long): MoveReport? {
        val run = store.run(runId) ?: return null
        val leftOut = store.binItems().mapNotNull {
            when (it.status) {
                ItemStatus.Failed -> LeftOut(it.id, LeftOutReason.Failed)
                ItemStatus.Missing -> LeftOut(it.id, LeftOutReason.Missing)
                else -> null
            }
        }
        val note = notes[runId] ?: Note()
        return MoveReport.of(run, store.steps(runId), note.inPlace, leftOut, note.checkOk)
    }

    private suspend fun execute(runId: Long, kind: MoveKind, control: MoveControl, onProgress: (MoveProgress) -> Unit): MoveOutcome {
        val outcome = engine.run(runId, kind, control, onProgress)
        val removedFolders = removeEmptyFolders(runId)
        var checkOk: Boolean? = null
        if (outcome == MoveOutcome.Done) {
            val categories = store.categoryNames()
            val items = store.binItems()
            val after = check(categories, items, store.removedPaths())
            val missing = binnedAt(items, after.missing)
            if (missing.isNotEmpty()) store.markItems(emptyList(), missing)
            checkOk = after.ok
        }
        notes[runId] = (notes[runId] ?: Note()).copy(checkOk = checkOk)
        val state = when (outcome) {
            MoveOutcome.Done -> RunState.Done
            is MoveOutcome.Failed -> RunState.Failed
            else -> RunState.Stopped
        }
        store.finishRun(runId, state, clock(), messageOf(outcome), removedFolders)
        return outcome
    }

    private fun check(categories: Map<Long, String>, items: List<BinItem>, removed: List<String>): LayoutProblems =
        FolderCheck.check(
            projectDir = root,
            categoryNames = categories.values,
            items = items.map { CheckItem(it.currentPath, it.status == ItemStatus.Binned) },
            removed = removed,
            filesOnDisk = FolderCheck.categoryFiles(root, categories.values, files::listFiles),
        )

    private fun binnedAt(items: List<BinItem>, missingPaths: List<String>): List<Long> {
        val keys = missingPaths.mapTo(HashSet()) { it.lowercase() }
        return items.filter { it.status == ItemStatus.Binned && it.currentPath.lowercase() in keys }.map { it.id }
    }

    /**
     * B12: a folder directly inside the project that this run emptied is removed only if the app made
     * it (a finished step once put a file there, or it is a `Copied from` folder) and nothing at all is
     * left inside. Returns the folders removed.
     */
    private suspend fun removeEmptyFolders(runId: Long): List<String> {
        val emptied = store.steps(runId)
            .filter { StepState.of(it.state) == StepState.Done && !it.keepSource }
            .map { it.source.substringBeforeLast('/') }
            .filter { it.substringBeforeLast('/') == root && !it.endsWith("/.binning") }
            .distinct()
        if (emptied.isEmpty()) return emptyList()
        val made = store.binnedFolders().mapTo(HashSet()) { it.lowercase() }
        return emptied
            .filter { it.lowercase() in made || it.substringAfterLast('/').startsWith(COPIED_FROM) }
            .sorted()
            .filter { files.removeEmptyDir(it) }
    }

    private fun messageOf(outcome: MoveOutcome): String? = when (outcome) {
        is MoveOutcome.StorageFull -> "Storage is full. Free up ${formatBytes(outcome.neededBytes)} and press Bin Files again."
        is MoveOutcome.Failed -> outcome.message
        else -> null
    }

    companion object {
        /** The start of a folder P8's copies go into: `Copied from <other project>`. */
        const val COPIED_FROM = "Copied from "
    }
}
