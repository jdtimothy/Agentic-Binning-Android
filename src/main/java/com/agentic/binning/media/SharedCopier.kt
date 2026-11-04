package com.agentic.binning.media

import com.agentic.binning.move.BinFilesJob
import com.agentic.binning.move.MoveEngine
import com.agentic.binning.move.MoveFiles
import com.agentic.binning.move.MovePlanner
import com.agentic.binning.move.NoSpaceException
import java.io.IOException

/** What copying files another project holds came to (P8). */
data class SharedCopyResult(
    val copied: Int = 0,
    /** Copies that never matched their original, or couldn't be read or added: left out, nothing lost. */
    val failed: Int = 0,
    /** Files this project already holds, not copied again. */
    val alreadyHere: Int = 0,
    /** Files not tried because storage ran out; [neededBytes] says how much to free. */
    val notTried: Int = 0,
    val neededBytes: Long = 0,
    /** The `Copied from …` folders files went into, in first-used order. */
    val folders: List<String> = emptyList(),
)

/**
 * Copies files another project holds into `<project>/Copied from <other>/` (P8). It reads the originals
 * and never moves, renames or deletes them. Each copy is written to a temp file, read back and compared
 * by size and SHA-256 like a Bin Files copy, and only then put in place: nothing is overwritten.
 */
class SharedCopier(private val files: MoveFiles, projectDir: String) {
    private val root = projectDir.trimEnd('/')

    suspend fun copy(
        held: List<HeldFile>,
        isKnown: suspend (hash: String) -> Boolean,
        register: suspend (path: String, hash: String) -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): SharedCopyResult {
        // Unchecked temp files from a copy the app died in; never trusted. The engine's own are left alone.
        files.listFiles("$root/.binning/moving").filter { it.substringAfterLast('/').startsWith(TEMP_PREFIX) }.forEach { files.delete(it) }
        var result = SharedCopyResult()
        for ((index, file) in held.withIndex()) {
            val outcome = try {
                copyOne(file, index, isKnown, register)
            } catch (e: IOException) {
                Outcome.Failed
            }
            result = when (outcome) {
                is Outcome.Copied -> result.copy(
                    copied = result.copied + 1,
                    folders = if (outcome.folder in result.folders) result.folders else result.folders + outcome.folder,
                )
                Outcome.Failed -> result.copy(failed = result.failed + 1)
                Outcome.AlreadyHere -> result.copy(alreadyHere = result.alreadyHere + 1)
                is Outcome.NoSpace -> return result.copy(notTried = held.size - index, neededBytes = outcome.needed)
            }
            onProgress(index + 1, held.size)
        }
        return result
    }

    private sealed interface Outcome {
        data class Copied(val folder: String) : Outcome
        data object Failed : Outcome
        data object AlreadyHere : Outcome
        data class NoSpace(val needed: Long) : Outcome
    }

    private suspend fun copyOne(file: HeldFile, index: Int, isKnown: suspend (String) -> Boolean, register: suspend (String, String) -> Boolean): Outcome {
        if (!files.exists(file.path)) return Outcome.Failed

        val size = files.size(file.path)
        val free = files.usableSpace(root)
        val wanted = size + MoveEngine.COPY_MARGIN_BYTES
        if (free < wanted) return Outcome.NoSpace(wanted - free)

        val temp = "$root/.binning/moving/$TEMP_PREFIX$index.tmp"
        files.mkdirs(temp.substringBeforeLast('/'))
        // A file is read to copy it, hashing as it goes, and the copy read once to check it: large videos
        // come through here. So a file this project already holds is found after copying, not before.
        var checkedHash: String? = null
        for (attempt in 1..MoveEngine.COPY_TRIES) {
            val hash = try {
                files.copyHashing(file.path, temp)
            } catch (e: NoSpaceException) {
                files.delete(temp)
                return Outcome.NoSpace(size)
            } catch (e: IOException) {
                files.delete(temp)
                return Outcome.Failed
            }
            if (isKnown(hash)) {
                files.delete(temp)
                return Outcome.AlreadyHere
            }
            if (files.size(temp) == size && files.sha256(temp) == hash) {
                checkedHash = hash
                break
            }
            files.delete(temp)
        }
        val hash = checkedHash ?: return Outcome.Failed
        files.setLastModified(temp, files.lastModified(file.path))

        val folder = BinFilesJob.COPIED_FROM + MovePlanner.folderFor(file.project)
        files.mkdirs("$root/$folder")
        val name = file.path.substringAfterLast('/')
        var dest = MovePlanner.nextFree("$root/$folder/$name", files::exists)
        var tries = 0
        while (!files.renameNoReplace(temp, dest)) {
            if (++tries >= RENAME_TRIES) {
                files.delete(temp)
                return Outcome.Failed
            }
            dest = MovePlanner.nextFree(dest, files::exists)
        }
        if (!register(dest, hash)) {
            // Only this app's own checked copy: an unregistered file would trip nothing but clutter the folder.
            files.delete(dest)
            return Outcome.Failed
        }
        return Outcome.Copied(folder)
    }

    private companion object {
        const val RENAME_TRIES = 5
        const val TEMP_PREFIX = "copy-"
    }
}
