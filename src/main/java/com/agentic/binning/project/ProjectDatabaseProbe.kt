package com.agentic.binning.project

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

/**
 * A read-only look at a candidate project database for Open project. It never goes through Room and
 * never writes: a missing file is not created, a hot journal is not rolled back (the read fails and
 * the file reads as unreadable) and nothing is migrated. [ProjectOpenRules.decide] turns the facts
 * into a decision.
 */
object ProjectDatabaseProbe {
    fun probe(dbFile: File): DatabaseFacts {
        if (!dbFile.isFile) return DatabaseFacts(exists = false)
        return try {
            BundledSQLiteDriver().open(dbFile.absolutePath, SQLITE_OPEN_READONLY).use { c ->
                val quickCheck = c.text("PRAGMA quick_check") == "ok"
                val version = c.long("PRAGMA user_version")?.toInt()
                val hash = if (c.hasTable("room_master_table")) c.text("SELECT identity_hash FROM room_master_table WHERE id = 42") else null
                val hasMetaTable = c.hasTable("project_meta")
                val metaName = if (hasMetaTable) c.text("SELECT name FROM project_meta WHERE id = 1") else null
                val hasMeta = hasMetaTable && c.long("SELECT COUNT(*) FROM project_meta WHERE id = 1") == 1L
                DatabaseFacts(
                    exists = true,
                    readable = true,
                    userVersion = version,
                    identityHash = hash,
                    metaName = metaName,
                    hasMeta = hasMeta,
                    quickCheckOk = quickCheck,
                )
            }
        } catch (_: Exception) {
            // Not SQLite, a hot journal a read-only connection can't recover, or any other read error.
            DatabaseFacts(exists = true, readable = false)
        }
    }

    private fun SQLiteConnection.hasTable(name: String): Boolean =
        prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use { s ->
            s.bindText(1, name)
            s.step() && s.getLong(0) > 0
        }

    private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { s ->
        if (s.step() && !s.isNull(0)) s.getText(0) else null
    }

    private fun SQLiteConnection.long(sql: String): Long? = prepare(sql).use { s ->
        if (s.step() && !s.isNull(0)) s.getLong(0) else null
    }
}
