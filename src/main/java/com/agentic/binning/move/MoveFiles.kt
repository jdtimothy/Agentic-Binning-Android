package com.agentic.binning.move

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest

/** The storage ran out during a copy (ENOSPC): the half copy must go and the run stop (B6). */
class NoSpaceException(cause: IOException) : IOException(cause.message, cause)

/**
 * The file system as the move engine sees it. Every path is absolute. Nothing here ever replaces an
 * existing file: [renameNoReplace] returns false and [copyHashing] throws instead.
 */
interface MoveFiles {
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun lastModified(path: String): Long
    fun setLastModified(path: String, time: Long)

    /** Whether a rename from [a] to [b] stays on one storage volume (B1); [b] may not exist yet. */
    fun sameVolume(a: String, b: String): Boolean

    /** Moves [from] to [to] (folder and name) in one step; false, touching nothing, if [to] exists. */
    fun renameNoReplace(from: String, to: String): Boolean

    /**
     * Copies [from] to the new file [to] and returns the SHA-256 of the bytes read from [from]. Throws
     * [NoSpaceException] when the storage runs out, and an [IOException] if [to] exists.
     */
    fun copyHashing(from: String, to: String): String

    fun sha256(path: String): String

    /** Deletes a file (never a folder); true if it is gone. */
    fun delete(path: String): Boolean

    fun mkdirs(dir: String): Boolean

    /** Free bytes on the volume holding [dir] (or its nearest existing parent). */
    fun usableSpace(dir: String): Long

    /** Every file under [dir], recursively; empty if it doesn't exist. */
    fun listFiles(dir: String): List<String>

    /** Removes [dir] only if it is a folder with nothing at all inside (B12); true if it did. */
    fun removeEmptyDir(dir: String): Boolean
}

/**
 * [MoveFiles] on the real file system. [volumeOf] names a file's volume: `Os.stat(path).st_dev` on
 * the phone, `Files.getFileStore` in JVM tests.
 *
 * A rename that never replaces would need `renameat2(RENAME_NOREPLACE)` or a hard link, neither of
 * which Android's FUSE storage offers. `Files.move` without `REPLACE_EXISTING` checks first; only this
 * app writes into category folders, and the engine checks the result (handoff ledger ruling).
 */
class RealMoveFiles(private val volumeOf: (File) -> Any?) : MoveFiles {
    override fun exists(path: String) = File(path).exists()

    override fun size(path: String) = File(path).length()

    override fun lastModified(path: String) = File(path).lastModified()

    override fun setLastModified(path: String, time: Long) {
        File(path).setLastModified(time)
    }

    override fun sameVolume(a: String, b: String): Boolean {
        val va = volumeOf(existing(File(a))) ?: return false
        val vb = volumeOf(existing(File(b))) ?: return false
        return va == vb
    }

    override fun renameNoReplace(from: String, to: String): Boolean {
        val target = File(to)
        if (target.exists()) return false
        return try {
            Files.move(File(from).toPath(), target.toPath())
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }
    }

    override fun copyHashing(from: String, to: String): String {
        val target = File(to)
        if (target.exists()) throw IOException("Target exists: $to")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(from).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    output.flush()
                    // On storage before the check reads it back, so the check sees what a reboot would.
                    output.fd.sync()
                }
            }
        } catch (e: IOException) {
            if (isNoSpace(e)) throw NoSpaceException(e)
            throw e
        }
        return digest.digest().toHex()
    }

    override fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    override fun delete(path: String): Boolean {
        val f = File(path)
        if (f.isDirectory) return false
        return f.delete() || !f.exists()
    }

    override fun mkdirs(dir: String): Boolean {
        val d = File(dir)
        return d.isDirectory || d.mkdirs()
    }

    override fun usableSpace(dir: String): Long = existing(File(dir)).usableSpace

    override fun listFiles(dir: String): List<String> {
        val d = File(dir)
        if (!d.isDirectory) return emptyList()
        return d.walkTopDown().filter { it.isFile }.map { it.path }.toList()
    }

    override fun removeEmptyDir(dir: String): Boolean {
        val d = File(dir)
        if (!d.isDirectory || d.list()?.isNotEmpty() != false) return false
        return d.delete()
    }

    private fun existing(f: File): File {
        var cur: File? = f.absoluteFile
        while (cur != null && !cur.exists()) cur = cur.parentFile
        return cur ?: File("/")
    }

    private fun isNoSpace(e: IOException): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            if ("ENOSPC" in m || "No space left" in m) return true
            t = t.cause
        }
        return false
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER = 1 shl 20
    }
}
