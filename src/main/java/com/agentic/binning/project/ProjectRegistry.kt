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

/**
 * `projects.json` exists but can't be read. Every write refuses then, so an unreadable list is never
 * replaced by an empty one; the file is left exactly as it is.
 */
class ProjectListUnreadableException(cause: Throwable? = null) : IOException("The project list could not be read", cause)

/** A project taken off the list by [ProjectRegistry.remove]: enough to put it back with [ProjectRegistry.restore]. */
data class RemovedProject(val ref: ProjectRef, val index: Int, val wasCurrent: Boolean)

@Serializable
private data class RegistryData(
    val projects: List<ProjectRef> = emptyList(),
    val current: String? = null,
)

/**
 * The projects loaded into the app ("Current projects") plus the open one, persisted as JSON at
 * [registryFile] (`{"projects":[...],"current":"<path>"}`). New projects are created under
 * [projectsRoot]. The file is re-read on every call so separate instances stay consistent.
 *
 * Paths are compared by their canonical form ([samePath]), so one folder reached through another
 * spelling (`/sdcard/...`, `..`, a trailing slash) is never listed twice.
 *
 * Reads are lenient: an unreadable file lists no projects. Writes are strict: they throw
 * [ProjectListUnreadableException] instead of overwriting a file that exists but can't be parsed.
 * Nothing here ever touches a project's folder except [create], which makes a new one.
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

    /** The listed project at [path] (any spelling of it), or null. */
    @Synchronized
    fun find(path: String): ProjectRef? = read().projects.firstOrNull { samePath(it.path, path) }

    /**
     * Makes [ref] the open project, adding it to the list if no entry has the same folder. An existing
     * entry keeps its own name and path. Returns the entry as stored.
     * @throws ProjectListUnreadableException if the list file exists but can't be read.
     */
    @Synchronized
    fun setCurrent(ref: ProjectRef): ProjectRef {
        val data = readForWrite()
        val stored = data.projects.firstOrNull { samePath(it.path, ref.path) }
        val projects = if (stored != null) data.projects else data.projects + ref
        write(RegistryData(projects, (stored ?: ref).path))
        return stored ?: ref
    }

    /**
     * Forgets the current project (e.g. its folder disappeared) without removing it from [list].
     * @throws ProjectListUnreadableException if the list file exists but can't be read.
     */
    @Synchronized
    fun clearCurrent() {
        val data = readForWrite()
        if (data.current != null) write(data.copy(current = null))
    }

    /**
     * Takes the project at [path] off the list (and clears it as current if it was). Only
     * `projects.json` changes; the project's folder, database and media are never touched.
     * Returns what was removed, or null if no entry has that folder.
     * @throws ProjectListUnreadableException if the list file exists but can't be read.
     */
    @Synchronized
    fun remove(path: String): RemovedProject? {
        val data = readForWrite()
        val index = data.projects.indexOfFirst { samePath(it.path, path) }
        if (index < 0) return null
        val ref = data.projects[index]
        val wasCurrent = data.current != null && samePath(data.current, ref.path)
        write(RegistryData(data.projects.filterIndexed { i, _ -> i != index }, if (wasCurrent) null else data.current))
        return RemovedProject(ref, index, wasCurrent)
    }

    /**
     * Undo for [remove]: puts the entry back at its old position (or last, if the list got shorter).
     * Does nothing if the folder is listed again meanwhile. It does not make it current; reopening it
     * does that. @throws ProjectListUnreadableException if the list file exists but can't be read.
     */
    @Synchronized
    fun restore(removed: RemovedProject) {
        val data = readForWrite()
        if (data.projects.any { samePath(it.path, removed.ref.path) }) return
        val projects = data.projects.toMutableList()
        projects.add(removed.index.coerceIn(0, projects.size), removed.ref)
        write(data.copy(projects = projects))
    }

    /**
     * Creates a new project folder under [projectsRoot] and lists it.
     * Fails with [ProjectListUnreadableException] (before creating anything) if the list can't be read.
     */
    @Synchronized
    fun create(name: String): Result<ProjectRef> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Project name must not be blank" }
        val folderName = ProjectFolders.folderNameFor(trimmed)
        require(folderName.isNotEmpty()) { "Project name has no usable characters" }
        val data = readForWrite()
        val dir = File(projectsRoot, folderName)
        if (dir.exists()) throw ProjectExistsException(dir.path)
        val thumbs = ProjectFolders.thumbsDir(dir)
        if (!thumbs.mkdirs()) throw IOException("Could not create ${thumbs.path}")
        File(ProjectFolders.binningDir(dir), ".nomedia").createNewFile()
        val ref = ProjectRef(trimmed, dir.path)
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

    private fun read(): RegistryData = try {
        readForWrite()
    } catch (_: ProjectListUnreadableException) {
        RegistryData()
    }

    /** A missing file is an empty list; a file that exists but can't be parsed throws. */
    private fun readForWrite(): RegistryData {
        if (!registryFile.exists()) return RegistryData()
        return try {
            json.decodeFromString(RegistryData.serializer(), registryFile.readText())
        } catch (e: Exception) {
            throw ProjectListUnreadableException(e)
        }
    }

    /** Writes to a temp file then replaces; File.renameTo fails over an existing file on Windows. */
    private fun write(data: RegistryData) {
        registryFile.absoluteFile.parentFile?.mkdirs()
        val tmp = File(registryFile.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(RegistryData.serializer(), data))
        Files.move(tmp.toPath(), registryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** The canonical form of [path] (symlinks and `..` resolved, no trailing separator). */
        fun canonical(path: String): String {
            val file = File(path)
            val resolved = try {
                file.canonicalPath
            } catch (_: IOException) {
                file.absolutePath
            }
            return resolved.trimEnd(File.separatorChar).ifEmpty { File.separator }
        }

        fun samePath(a: String, b: String): Boolean = a == b || canonical(a) == canonical(b)
    }
}
