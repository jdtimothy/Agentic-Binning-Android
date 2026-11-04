package com.agentic.binning.media

import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.ItemStore
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class Candidate(val file: File, val kind: ItemKind)

sealed interface RegistrationOutcome {
    data class Added(val path: String) : RegistrationOutcome
    data class DuplicateInProject(val path: String) : RegistrationOutcome
    /** [listed] is false for a project found by its folder's database, off Current projects (P8). */
    data class HeldByOtherProject(val path: String, val projectName: String, val listed: Boolean = true) : RegistrationOutcome
    data class Unreadable(val path: String, val reason: String) : RegistrationOutcome
}

data class RegistrationProgress(val done: Int, val total: Int, val last: RegistrationOutcome)

/** Answers whether another project already holds a file, by content hash or by path. */
interface ProjectItemLookup {
    val projectName: String
    suspend fun containsHashOrPath(hash: String, path: String): Boolean
}

/** A [ProjectItemLookup] backed by another project's item table. */
class ItemStoreLookup(override val projectName: String, private val items: ItemStore) : ProjectItemLookup {
    override suspend fun containsHashOrPath(hash: String, path: String): Boolean =
        items.findByHashes(listOf(hash)).isNotEmpty() || items.findByPaths(listOf(path)).isNotEmpty()
}

/**
 * Registers media files as [ItemStatus.Pending] items of [thisProject]. Files are only read, never
 * moved, renamed or deleted. The flow does blocking I/O, so collect it with `flowOn(Dispatchers.IO)`.
 */
class Registrar(
    private val thisProject: ProjectRef,
    private val itemDao: ItemStore,
    private val otherProjects: List<ProjectItemLookup>,
    private val registry: ProjectRegistry,
    /** The project a file sits inside, found on disk whether or not it is listed (P8). */
    private val findProject: (File) -> ProjectFolder? = { null },
    private val hash: (File) -> String = ContentHasher::sha256,
    private val metadata: (Candidate) -> MediaMetadata,
    private val clock: () -> Long,
) {
    /** Emits one [RegistrationProgress] per candidate, in order. */
    fun register(candidates: List<Candidate>): Flow<RegistrationProgress> = flow {
        val thisFolder = File(thisProject.path).canonicalPath
        candidates.forEachIndexed { index, candidate ->
            val outcome = try {
                registerOne(candidate, thisFolder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RegistrationOutcome.Unreadable(
                    candidate.file.absolutePath,
                    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                )
            }
            emit(RegistrationProgress(index + 1, candidates.size, outcome))
        }
    }

    private suspend fun registerOne(candidate: Candidate, thisFolder: String): RegistrationOutcome {
        val path = candidate.file.absolutePath

        // 1. Inside another project's folder.
        val owner = registry.projectContaining(path)
        if (owner != null && File(owner.path).canonicalPath != thisFolder) {
            return RegistrationOutcome.HeldByOtherProject(path, owner.name)
        }
        findProject(candidate.file)?.takeIf { it.dir.canonicalPath != thisFolder }?.let {
            return RegistrationOutcome.HeldByOtherProject(path, it.name, listed = false)
        }

        // 2. Already in this project; the path is checked first so a known file is never re-hashed.
        if (itemDao.findByPaths(listOf(path)).isNotEmpty()) return RegistrationOutcome.DuplicateInProject(path)
        val contentHash = hash(candidate.file)
        if (itemDao.findByHashes(listOf(contentHash)).isNotEmpty()) return RegistrationOutcome.DuplicateInProject(path)

        // 3. Held by another project.
        otherProjects.firstOrNull { it.containsHashOrPath(contentHash, path) }?.let {
            return RegistrationOutcome.HeldByOtherProject(path, it.projectName)
        }

        // 4. New.
        insert(candidate, contentHash)
        return RegistrationOutcome.Added(path)
    }

    /**
     * Adds a checked copy of a file another project holds (P8): an ordinary item of this project, though
     * the other project has the same hash. False if this project already holds it.
     */
    suspend fun registerCopy(candidate: Candidate): Boolean {
        val path = candidate.file.absolutePath
        if (itemDao.findByPaths(listOf(path)).isNotEmpty()) return false
        val contentHash = hash(candidate.file)
        if (itemDao.findByHashes(listOf(contentHash)).isNotEmpty()) return false
        insert(candidate, contentHash)
        return true
    }

    private suspend fun insert(candidate: Candidate, contentHash: String) {
        val path = candidate.file.absolutePath
        val meta = metadata(candidate)
        itemDao.insert(
            ItemEntity(
                contentHash = contentHash,
                originalPath = path,
                originalName = candidate.file.name,
                currentPath = path,
                kind = candidate.kind,
                takenAt = meta.takenAt,
                durationMs = meta.durationMs,
                width = meta.width,
                height = meta.height,
                status = ItemStatus.Pending,
                addedAt = clock(),
            ),
        )
    }
}
