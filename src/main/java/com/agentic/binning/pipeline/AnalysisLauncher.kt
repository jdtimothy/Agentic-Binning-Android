package com.agentic.binning.pipeline

import com.agentic.binning.project.ProjectRef

/** What [AnalysisLauncher] reads and writes in one project's database. */
interface ProjectRunFlags {
    /** Pending items, or Analyzing ones a dead process left behind. */
    suspend fun hasWaitingItems(): Boolean
    suspend fun isStoppedByUser(): Boolean
    suspend fun setStoppedByUser(stopped: Boolean)
}

/**
 * Decides when analysis starts. Launch, a project switch and coming back to the app start waiting
 * items on their own, unless the user pressed Stop: that is kept in the project's database, so it
 * holds across a cold launch and switching away and back. Resume and adding media lift it.
 */
class AnalysisLauncher(
    private val state: () -> PipelineState,
    /** Starts the foreground service for the project; false if the system refused (see AppGraph). */
    private val startService: (ProjectRef) -> Boolean,
    private val stopPipeline: () -> Unit,
) {
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

    /** Retries a start the system refused while the app was in the background. */
    suspend fun retryDeferred(project: ProjectRef, flags: ProjectRunFlags): Boolean {
        if (flags.isStoppedByUser()) return false
        return startIfWaiting(project, flags)
    }

    /** The user pressed Resume or added media: lifts a stop and starts anything waiting. */
    suspend fun userStart(project: ProjectRef, flags: ProjectRunFlags): Boolean {
        flags.setStoppedByUser(false)
        return startIfWaiting(project, flags)
    }

    /** The user pressed Stop: stops the run at once, then remembers it for the project. */
    suspend fun userStop(flags: ProjectRunFlags) {
        stopPipeline()
        flags.setStoppedByUser(true)
    }

    private suspend fun startIfWaiting(project: ProjectRef, flags: ProjectRunFlags): Boolean =
        flags.hasWaitingItems() && startService(project)
}
