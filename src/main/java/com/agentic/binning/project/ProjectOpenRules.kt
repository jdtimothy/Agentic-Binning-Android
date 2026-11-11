package com.agentic.binning.project

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/** What a read-only look at a candidate `.binning/project.db` found (see `ProjectDatabaseProbe`). */
data class DatabaseFacts(
    val exists: Boolean,
    /** Opened read-only and answered; false for a non-SQLite file, a hot journal, or any read error. */
    val readable: Boolean = false,
    /** `PRAGMA user_version`: Room's schema version. */
    val userVersion: Int? = null,
    /** Room's identity hash from `room_master_table`, or null when that table is missing. */
    val identityHash: String? = null,
    /** `project_meta.name`, or null when there is no meta row. */
    val metaName: String? = null,
    val hasMeta: Boolean = false,
    /** `PRAGMA quick_check` said `ok`. */
    val quickCheckOk: Boolean = false,
)

/** Why Open project refused a folder. Every case leaves the disk untouched. */
enum class OpenRefusal(val message: String) {
    NoProject("No project in this folder"),
    CantOpen("This project can't be opened"),
    Newer("Made with a newer app version. Update to open it."),
    NotOnDevice("Pick a folder on this phone or its SD card"),
    ListUnreadable("Couldn't update the project list"),
}

/** Where a picked folder stands relative to the listed projects. */
sealed interface Placement {
    /** The folder is already listed: open that entry, add nothing. */
    data class Listed(val ref: ProjectRef) : Placement

    /** The folder lies inside a listed project. */
    data class Inside(val ref: ProjectRef) : Placement

    /** The folder contains a listed project. */
    data class Contains(val ref: ProjectRef) : Placement

    data object Unlisted : Placement
}

sealed interface DatabaseDecision {
    /** Open it. [backupFromVersion] is set when the schema is older and must be copied before Room upgrades it. */
    data class Open(val backupFromVersion: Int?) : DatabaseDecision

    data class Refused(val reason: OpenRefusal) : DatabaseDecision
}

/** The decisions behind Open project and Current projects' remove, free of Android so they run on the JVM. */
object ProjectOpenRules {
    /** Someone who picks the hidden `.binning` folder itself means its project. */
    fun projectDir(picked: File): File =
        if (picked.name == ProjectFolders.BINNING_DIR) picked.absoluteFile.parentFile ?: picked else picked

    fun placement(dir: String, listed: List<ProjectRef>): Placement {
        val target = ProjectRegistry.canonical(dir)
        listed.firstOrNull { ProjectRegistry.canonical(it.path) == target }?.let { return Placement.Listed(it) }
        listed.firstOrNull { isUnder(target, ProjectRegistry.canonical(it.path)) }?.let { return Placement.Inside(it) }
        listed.firstOrNull { isUnder(ProjectRegistry.canonical(it.path), target) }?.let { return Placement.Contains(it) }
        return Placement.Unlisted
    }

    /** The one-line refusal for a folder inside or containing a listed project. */
    fun placementMessage(placement: Placement): String? = when (placement) {
        is Placement.Inside -> "This folder is inside ${placement.ref.name}"
        is Placement.Contains -> "This folder contains ${placement.ref.name}"
        else -> null
    }

    /**
     * Whether a project database may be opened, from what the read-only probe found. Only a current
     * or an older schema with Room's own identity hash for that version opens; an older one is backed
     * up first. Anything newer, foreign or damaged is refused, untouched.
     */
    fun decide(facts: DatabaseFacts, currentVersion: Int, knownHashes: Map<Int, String>): DatabaseDecision {
        if (!facts.exists) return DatabaseDecision.Refused(OpenRefusal.NoProject)
        if (!facts.readable || !facts.quickCheckOk) return DatabaseDecision.Refused(OpenRefusal.CantOpen)
        val version = facts.userVersion ?: return DatabaseDecision.Refused(OpenRefusal.CantOpen)
        if (version > currentVersion) return DatabaseDecision.Refused(OpenRefusal.Newer)
        if (facts.identityHash == null || facts.identityHash != knownHashes[version]) return DatabaseDecision.Refused(OpenRefusal.CantOpen)
        if (!facts.hasMeta) return DatabaseDecision.Refused(OpenRefusal.CantOpen)
        return DatabaseDecision.Open(backupFromVersion = version.takeIf { it < currentVersion })
    }

    /** The name a reopened project is listed under: its own `project_meta.name`, else its folder's. */
    fun listedName(facts: DatabaseFacts, dir: File): String = facts.metaName?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name

    /**
     * Where an older database is copied before it is upgraded: `project.db.v<N>.bak` beside it, or
     * `project.db.v<N>.<k>.bak` if that name is taken. Never an existing file.
     */
    fun backupFile(dbFile: File, version: Int, exists: (File) -> Boolean = File::exists): File {
        val dir = dbFile.absoluteFile.parentFile
        val first = File(dir, "${dbFile.name}.v$version.bak")
        if (!exists(first)) return first
        return generateSequence(1) { it + 1 }.map { File(dir, "${dbFile.name}.v$version.$it.bak") }.first { !exists(it) }
    }

    /**
     * Copies [dbFile] aside if its schema is older than [currentVersion], before Room upgrades it: an
     * upgrade is one-way, since an older build can't open the result. Returns the copy, or null when
     * none was needed. An open that runs no query never upgrades the file, so the next open finds it old
     * again: a backup with the same contents is enough, or copies would pile up without limit. Throws if
     * the copy fails, so nothing is upgraded without one.
     */
    fun backupIfOlder(dbFile: File, currentVersion: Int): File? {
        val version = headerVersion(dbFile) ?: return null
        if (version !in 1 until currentVersion) return null
        if (hasBackupOf(dbFile, version)) return null
        val backup = backupFile(dbFile, version)
        Files.copy(dbFile.toPath(), backup.toPath()) // fails rather than overwrite
        return backup
    }

    private fun hasBackupOf(dbFile: File, version: Int): Boolean {
        val prefix = "${dbFile.name}.v$version."
        val earlier = dbFile.absoluteFile.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".bak") }.orEmpty()
        val length = dbFile.length()
        val same = earlier.filter { it.length() == length }
        if (same.isEmpty()) return false
        val bytes = dbFile.readBytes()
        return same.any { it.readBytes().contentEquals(bytes) }
    }

    /** `user_version` from a SQLite file's header, without opening it; null if it is missing or not SQLite. */
    private fun headerVersion(dbFile: File): Int? {
        if (!dbFile.isFile) return null
        val header = ByteArray(SQLITE_HEADER_BYTES)
        val read = dbFile.inputStream().use { it.readNBytes(header, 0, header.size) }
        if (read < header.size) return null
        if (!SQLITE_MAGIC.indices.all { header[it] == SQLITE_MAGIC[it] }) return null
        return ByteBuffer.wrap(header, SQLITE_USER_VERSION_OFFSET, Int.SIZE_BYTES).int
    }

    /**
     * The project to open after the open one was removed: the first of [remaining], in list order,
     * that [openable] accepts. Null means none, and the sheet shows Current projects with no open project.
     */
    fun nextAfterRemoval(remaining: List<ProjectRef>, openable: (ProjectRef) -> Boolean): ProjectRef? =
        remaining.firstOrNull(openable)

    private fun isUnder(path: String, ancestor: String): Boolean =
        path.startsWith(ancestor.trimEnd(File.separatorChar) + File.separator)

    private const val SQLITE_HEADER_BYTES = 100
    private const val SQLITE_USER_VERSION_OFFSET = 60
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
