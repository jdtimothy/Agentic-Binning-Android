package com.agentic.binning.move

import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.MoveStepEntity
import java.io.IOException
import java.security.MessageDigest

/**
 * An in-memory file system for the engine's tests. Paths under `/sd/` are on another volume. Hooks
 * let a test corrupt copies, run out of space, or crash at a chosen moment.
 */
class FakeMoveFiles : MoveFiles {
    class F(var content: String, var mtime: Long = 1_000L)

    val files = LinkedHashMap<String, F>()
    val dirs = HashSet<String>()
    var free: Long = 10_000_000_000L

    /** The next N copies write the wrong bytes. */
    var corruptCopies = 0

    /** The next copy fails with ENOSPC after writing half the file. */
    var noSpaceDuringCopy = false

    /** A rename that "succeeds" but leaves the file at the source (to test the check after a rename). */
    var renameLies = false

    /** A rename that fails with an I/O error, touching nothing. */
    var renameThrows = false

    /** Called before each operation by name ("rename", "copy", "delete"); throw from it to simulate a crash. */
    var before: (op: String, path: String) -> Unit = { _, _ -> }

    val log = ArrayList<String>()

    fun put(path: String, content: String, mtime: Long = 1_000L) {
        files[path] = F(content, mtime)
        var d = path.substringBeforeLast('/')
        while (d.isNotEmpty()) { dirs += d; d = d.substringBeforeLast('/', "") }
    }

    fun content(path: String) = files[path]?.content

    private fun key(path: String) = files.keys.firstOrNull { it.equals(path, ignoreCase = true) }

    override fun exists(path: String) = key(path) != null || dirs.any { it.equals(path, ignoreCase = true) }
    override fun size(path: String) = files.getValue(key(path)!!).content.length.toLong()
    override fun lastModified(path: String) = files.getValue(key(path)!!).mtime
    override fun setLastModified(path: String, time: Long) { files.getValue(key(path)!!).mtime = time }
    override fun sameVolume(a: String, b: String) = a.startsWith("/sd/") == b.startsWith("/sd/")

    override fun renameNoReplace(from: String, to: String): Boolean {
        before("rename", from)
        if (renameThrows) throw IOException("EACCES")
        if (key(to) != null) return false
        log += "rename $from -> $to"
        if (renameLies) return true
        val f = files.remove(key(from)!!)!!
        files[to] = f
        return true
    }

    override fun copyHashing(from: String, to: String): String {
        before("copy", from)
        if (key(to) != null) throw IOException("exists")
        val src = files.getValue(key(from)!!)
        log += "copy $from -> $to"
        if (noSpaceDuringCopy) {
            noSpaceDuringCopy = false
            files[to] = F(src.content.take(src.content.length / 2))
            throw NoSpaceException(IOException("ENOSPC"))
        }
        files[to] = F(if (corruptCopies > 0) { corruptCopies--; src.content + "!" } else src.content)
        return hash(src.content)
    }

    /** Full reads of a file for a hash, beyond the one a copy makes as it goes. */
    var sha256Calls = 0

    override fun sha256(path: String): String {
        sha256Calls++
        return hash(files.getValue(key(path)!!).content)
    }

    override fun delete(path: String): Boolean {
        before("delete", path)
        log += "delete $path"
        files.remove(key(path) ?: return true)
        return true
    }

    override fun mkdirs(dir: String): Boolean { var d = dir; while (d.isNotEmpty()) { dirs += d; d = d.substringBeforeLast('/', "") }; return true }
    override fun usableSpace(dir: String) = free
    override fun listFiles(dir: String) = files.keys.filter { it.startsWith("$dir/", ignoreCase = true) }

    override fun removeEmptyDir(dir: String): Boolean {
        if (dir !in dirs || files.keys.any { it.startsWith("$dir/") } || dirs.any { it.startsWith("$dir/") }) return false
        log += "rmdir $dir"
        dirs -= dir
        return true
    }

    companion object {
        fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** The engine's database, in memory: steps of one run and the items they move. */
class FakeMoveStore : MoveStore {
    data class Item(var path: String, var status: ItemStatus, var hash: String)

    val steps = ArrayList<MoveStepEntity>()
    val items = HashMap<Long, Item>()
    var runState = "Running"
    var runMessage: String? = null
    val runStates = ArrayList<String>()

    fun addStep(itemId: Long, source: String, dest: String, bytes: Long, state: StepState = StepState.Planned, keepSource: Boolean = false, attempts: Int = 0): Long {
        val id = steps.size + 1L
        steps += MoveStepEntity(id = id, runId = 1, itemId = itemId, source = source, dest = dest, state = state.name, bytes = bytes, keepSource = keepSource, attempts = attempts)
        items.getOrPut(itemId) { Item(source, ItemStatus.Sorted, "h$itemId") }
        return id
    }

    fun step(id: Long) = steps.first { it.id == id }
    private fun update(id: Long, change: (MoveStepEntity) -> MoveStepEntity) {
        val i = steps.indexOfFirst { it.id == id }
        steps[i] = change(steps[i])
    }

    override suspend fun steps(runId: Long) = steps.toList()
    override suspend fun startStep(stepId: Long): Int { update(stepId) { it.copy(attempts = it.attempts + 1) }; return step(stepId).attempts }
    override suspend fun setStepState(stepId: Long, state: String, error: String?) = update(stepId) { it.copy(state = state, error = error) }
    override suspend fun setStepDest(stepId: Long, dest: String) = update(stepId) { it.copy(dest = dest) }

    override suspend fun recordMoved(stepId: Long, itemId: Long, dest: String, hash: String?, state: String) {
        items.getValue(itemId).apply { path = dest; status = ItemStatus.Binned; if (hash != null) this.hash = hash }
        setStepState(stepId, state, null)
    }

    override suspend fun recordMissing(stepId: Long, itemId: Long) {
        items.getValue(itemId).status = ItemStatus.Missing
        setStepState(stepId, StepState.Missing.name, null)
    }

    override suspend fun recordReverted(stepId: Long, itemId: Long, source: String, error: String) {
        items.getValue(itemId).apply { path = source; status = ItemStatus.Sorted }
        setStepState(stepId, StepState.Failed.name, error)
    }

    override suspend fun setRunState(runId: Long, state: String, message: String?) {
        runState = state
        runMessage = message
        runStates += state
    }
}
