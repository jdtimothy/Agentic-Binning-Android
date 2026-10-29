package com.agentic.binning.pipeline

import com.agentic.binning.project.ProjectRef

/**
 * Runs once when the analysis queue empties, after the vision model has been released.
 * Plan 2 sorts the batch here; until then `AppGraph` binds a no-op.
 */
fun interface BatchCompleteHandler {
    suspend fun onBatchComplete(project: ProjectRef)
}
