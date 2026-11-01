package com.agentic.binning.pipeline

import com.agentic.binning.project.ProjectRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a started run does: analyze waiting items (and categorize at the end), or only categorize. */
enum class RunKind { Analyze, CategorizeOnly }

/** What [AnalysisLauncher] reads and writes in one project's database. */
interface ProjectRunFlags {
    /** Pending items, or Analyzing ones a dead process left behind. */
    suspend fun hasWaitingItems(): Boolean

    /** Analyzed items without a category that the model may sort (not ones the user placed). */
    suspend fun hasUncategorizedItems(): Boolean
    suspend fun isStoppedByUser(): Boolean
    suspend fun setStoppedByUser(stopped: Boolean)
}

/**
 * Decides when analysis starts. Launch, a project switch and coming back to the app start waiting
 * items on their own, unless the user pressed Stop: that is kept in the project's database, so it
 * holds across a cold launch and switching away and back. Resume and adding media lift it.
 *
 * Categorizing alone (nothing to analyze, but analyzed items without a category: a project opened
 * fully analyzed, a Stop while categorizing, a notice saying items will be tried again) starts only
 * from the Resume button ([userStart]), never on its own or after an add: a Claude run costs money.
 */
class AnalysisLauncher(
    private val state: () -> PipelineState,
    /** Starts the foreground service for the project; false if the system refused (see AppGraph). */
    private val startService: (ProjectRef, RunKind) -> Boolean,
    private val stopPipeline: () -> Unit,
) {
    /**
     * Held by [userStart], [userAdd] and [userStop] so a quick Stop then Resume write the flag in call order.
     * The mutex queues waiters first come, first served, so callers must reach it in call order too
     * (AppGraph launches them undispatched).
     */
    private val userActions = Mutex()

    /**
     * On launch, after a switch and on resume: starts [project]'s waiting items, leaving alone a run
     * that is going or that stopped in this process, and a project the user stopped.
     */
    suspend fun autoStart(project: ProjectRef, flags: ProjectRunFlags): Boolean {
        val current = state()
        if (current.project == project && (current.phase.isRunning || current.phase == PipelinePhase.Stopped)) return false
        if (flags.isStoppedByUser()) return false
        return startIfWaiting(project, flags)
    }

    /**
     * Retries a start the system refused while the app was in the background; [kind] is what that
     * start was, so only a refused categorize-only start (the user's) categorizes here.
     */
    suspend fun retryDeferred(project: ProjectRef, flags: ProjectRunFlags, kind: RunKind): Boolean {
        if (flags.isStoppedByUser()) return false
        return if (kind == RunKind.CategorizeOnly) startOrCategorize(project, flags) else startIfWaiting(project, flags)
    }

    /**
     * The user pressed Resume: lifts a stop and starts anything waiting, or with nothing to analyze
     * categorizes the analyzed items that have no category yet.
     */
    suspend fun userStart(project: ProjectRef, flags: ProjectRunFlags): Boolean = userActions.withLock {
        flags.setStoppedByUser(false)
        startOrCategorize(project, flags)
    }

    /**
     * The user added media: lifts a stop and analyzes anything waiting (the run categorizes at its
     * end). An add that registered nothing new starts nothing, not even a categorization.
     */
    suspend fun userAdd(project: ProjectRef, flags: ProjectRunFlags): Boolean = userActions.withLock {
        flags.setStoppedByUser(false)
        startIfWaiting(project, flags)
    }

    /** The user pressed Stop: stops the run at once, then remembers it for the project. */
    suspend fun userStop(flags: ProjectRunFlags) = userActions.withLock {
        stopPipeline()
        flags.setStoppedByUser(true)
    }

    private suspend fun startIfWaiting(project: ProjectRef, flags: ProjectRunFlags): Boolean =
        flags.hasWaitingItems() && startService(project, RunKind.Analyze)

    private suspend fun startOrCategorize(project: ProjectRef, flags: ProjectRunFlags): Boolean = when {
        flags.hasWaitingItems() -> startService(project, RunKind.Analyze)
        flags.hasUncategorizedItems() -> startService(project, RunKind.CategorizeOnly)
        else -> false
    }
}
