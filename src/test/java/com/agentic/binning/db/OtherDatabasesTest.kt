package com.agentic.binning.db

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherDatabasesTest {
    /** Stands in for Room's connection element, which the caller's transaction carries in its context. */
    private class Marker : AbstractCoroutineContextElement(Marker) {
        companion object Key : CoroutineContext.Key<Marker>
    }

    /** The finding: a query on another project's database ran on this project's connection, and read its rows. */
    @Test fun theCallersTransactionDoesNotComeAlong() = runTest {
        val seen = withContext(Marker()) { outsideThisTransaction { currentCoroutineContext()[Marker] } }
        assertNull(seen)
    }

    @Test fun returnsWhatTheBlockFound() = runTest {
        assertEquals(setOf("/a.jpg"), outsideThisTransaction { setOf("/a.jpg") })
    }

    /** A failed lookup rolls the Save back: it must reach the transaction, not vanish with the coroutine. */
    @Test fun aFailureReachesTheCaller() = runTest {
        val thrown = runCatching { outsideThisTransaction<Unit> { error("can't read it") } }.exceptionOrNull()
        assertEquals("can't read it", thrown?.message)
    }

    @Test fun cancellingTheCallerCancelsTheLookup() = runTest {
        val started = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Throwable?>()
        val caller = async {
            outsideThisTransaction {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } catch (e: CancellationException) {
                    ended.complete(e)
                    throw e
                }
            }
        }
        started.await()
        caller.cancel()
        assertTrue(ended.await() is CancellationException)
    }
}
