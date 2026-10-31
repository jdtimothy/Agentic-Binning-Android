package com.agentic.binning.project

import java.io.File
import java.io.IOException

/** The project's folder no longer exists on disk. */
class ProjectMissingException(val path: String) : IOException("Project folder is missing: $path")

/**
 * Throws [ProjectMissingException] unless [ref]'s folder exists. Never touches the disk beyond the
 * check: opening a project database in a missing folder would recreate the folder.
 */
fun requireProjectFolder(ref: ProjectRef) {
    if (!File(ref.path).isDirectory) throw ProjectMissingException(ref.path)
}
