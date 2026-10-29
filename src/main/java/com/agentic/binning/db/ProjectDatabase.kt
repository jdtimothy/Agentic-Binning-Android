package com.agentic.binning.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

@Database(
    entities = [
        ItemEntity::class,
        AnalysisEntity::class,
        TagEntity::class,
        ItemTagEntity::class,
        CategoryEntity::class,
        MoveRunEntity::class,
        MoveStepEntity::class,
        ProjectMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun analyses(): AnalysisDao
}

/**
 * Opens (creating if needed) the per-project database at [dbFile]. The folder lives on FUSE-backed
 * shared storage, so the journal mode is TRUNCATE rather than WAL. Room's annotations only cover
 * FTS3/4, so the FTS5 table is created by raw SQL in onCreate; its rowid is the item id.
 */
fun openProjectDatabase(context: Context, dbFile: File): ProjectDatabase =
    Room.databaseBuilder<ProjectDatabase>(context.applicationContext, dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE VIRTUAL TABLE item_fts USING fts5(" +
                        "description, actions, tags, friendly_name, category, " +
                        "tokenize='porter unicode61')",
                )
            }
        })
        .build()
