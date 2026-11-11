package com.agentic.binning.db

import android.content.Context
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Finding 3's real path: a Save asks other projects' databases from inside its own transaction. */
class OtherDatabasesDeviceTest {
    private lateinit var thisFile: File
    private lateinit var otherFile: File
    private lateinit var thisDb: ProjectDatabase
    private lateinit var otherDb: ProjectDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        thisFile = File(context.cacheDir, "this-${System.nanoTime()}.db")
        otherFile = File(context.cacheDir, "other-${System.nanoTime()}.db")
        thisDb = openProjectDatabase(context, thisFile)
        otherDb = openProjectDatabase(context, otherFile)
    }

    @After fun tearDown() {
        thisDb.close()
        otherDb.close()
        listOf(thisFile, otherFile).forEach { f -> f.parentFile?.listFiles { c -> c.name.startsWith(f.name) }?.forEach { it.delete() } }
    }

    private fun item(path: String) = ItemEntity(
        contentHash = path, originalPath = path, originalName = path.substringAfterLast('/'), currentPath = path,
        kind = ItemKind.Photo, takenAt = null, durationMs = null, width = null, height = null,
        status = ItemStatus.Binned, addedAt = 1L,
    )

    /** A file only this project holds isn't shared; one the other project holds too is. */
    @Test fun insideATransaction_theOtherDatabaseAnswersForItself() = runTest {
        thisDb.items().insert(item("/p/only-here.jpg"))
        thisDb.items().insert(item("/p/both.jpg"))
        otherDb.items().insert(item("/p/both.jpg"))

        val found = thisDb.useWriterConnection { connection ->
            connection.immediateTransaction {
                outsideThisTransaction {
                    otherDb.items().findByPaths(listOf("/p/only-here.jpg", "/p/both.jpg")).map { it.currentPath }
                }
            }
        }

        assertEquals(listOf("/p/both.jpg"), found)
    }
}
