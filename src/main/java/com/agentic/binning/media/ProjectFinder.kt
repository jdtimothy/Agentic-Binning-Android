package com.agentic.binning.media

import com.agentic.binning.project.ProjectDatabaseProbe
import com.agentic.binning.project.ProjectFolders
import java.io.File

/** A folder holding `.binning/project.db`, and the name that project goes by. */
data class ProjectFolder(val dir: File, val name: String)

/**
 * Finds the project a file sits inside by looking for its database on disk (P8), so a project taken off
 * Current projects still owns its folder. It only reads: [nameOf] goes through the read-only probe, so
 * another project's database is never opened by Room or migrated. One finder serves one scan and asks
 * each folder once.
 */
class ProjectFinder(
    /** The open project's folder: found like any other, but its database isn't read for a name nobody needs. */
    ownDir: File? = null,
    private val nameOf: (File) -> String? = { ProjectDatabaseProbe.probe(it).metaName },
) {
    private val known = HashMap<String, ProjectFolder?>()
    private val own = ownDir?.canonicalPath

    fun projectContaining(file: File): ProjectFolder? {
        val start = file.absoluteFile.parentFile ?: return null
        val walked = ArrayList<String>()
        var dir: File? = start
        var found: ProjectFolder? = null
        while (dir != null) {
            if (dir.path in known) {
                found = known[dir.path]
                break
            }
            walked += dir.path
            val db = ProjectFolders.databaseFile(dir)
            if (db.isFile) {
                val name = if (dir.canonicalPath == own) null else nameOf(db)?.trim()?.takeIf { it.isNotEmpty() }
                found = ProjectFolder(dir, name ?: dir.name)
                break
            }
            dir = dir.parentFile
        }
        walked.forEach { known[it] = found }
        return found
    }
}
