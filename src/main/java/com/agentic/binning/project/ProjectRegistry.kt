package com.agentic.binning.project

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProjectRef(val name: String, val path: String)

class ProjectExistsException(val path: String) : IOException("Project folder already exists: $path")

@Serializable
private data class RegistryData(
    val projects: List<ProjectRef> = emptyList(),
    val current: String? = null,
)

/**
 * The list of known projects plus the current one, persisted as JSON at [registryFile]
 * (`{"projects":[...],"current":"<path>"}`). New projects are created under [projectsRoot].
 * The file is re-read on every call so separate instances stay consistent.
 */
class ProjectRegistry(private val registryFile: File, private val projectsRoot: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<ProjectRef> = read().projects

    @Synchronized
    fun current(): ProjectRef? {
        val data = read()
        return data.projects.firstOrNull { it.path == data.current }
    }

    @Synchronized
    fun setCurrent(ref: ProjectRef) {
        val data = read()
        val projects = if (data.projects.any { it.path == ref.path }) data.projects else data.projects + ref
        write(RegistryData(projects, ref.path))
    }

    /** Forgets the current project (e.g. its folder disappeared) without removing it from [list]. */
    @Synchronized
    fun clearCurrent() {
        val data = read()
        if (data.current != null) write(data.copy(current = null))
    }

    @Synchronized
    fun create(name: String): Result<ProjectRef> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Project name must not be blank" }
        val folderName = ProjectFolders.folderNameFor(trimmed)
        require(folderName.isNotEmpty()) { "Project name has no usable characters" }
        val dir = File(projectsRoot, folderName)
        if (dir.exists()) throw ProjectExistsException(dir.path)
        val thumbs = ProjectFolders.thumbsDir(dir)
        if (!thumbs.mkdirs()) throw IOException("Could not create ${thumbs.path}")
        File(ProjectFolders.binningDir(dir), ".nomedia").createNewFile()
        val ref = ProjectRef(trimmed, dir.path)
        val data = read()
        write(data.copy(projects = data.projects + ref))
        ref
    }

    /** The project whose folder is an ancestor of [path], matching whole path segments only. */
    @Synchronized
    fun projectContaining(path: String): ProjectRef? {
        val target = File(path).canonicalPath
        return read().projects.firstOrNull {
            target.startsWith(File(it.path).canonicalPath.trimEnd(File.separatorChar) + File.separator)
        }
    }

    private fun read(): RegistryData {
        if (!registryFile.isFile) return RegistryData()
        return try {
            json.decodeFromString(RegistryData.serializer(), registryFile.readText())
        } catch (_: Exception) {
            RegistryData()
        }
    }

    /** Writes to a temp file then replaces; File.renameTo fails over an existing file on Windows. */
    private fun write(data: RegistryData) {
        registryFile.absoluteFile.parentFile?.mkdirs()
        val tmp = File(registryFile.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(RegistryData.serializer(), data))
        Files.move(tmp.toPath(), registryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
