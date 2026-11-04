package com.agentic.binning.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Runs [block] in a coroutine that inherits nothing from its caller. Use it for queries on another
 * project's database that may run inside this one's transaction.
 *
 * Room 2.7 keeps a transaction's connection in the coroutine context under one key shared by every
 * database, and doesn't check which database it belongs to. So a DAO call on database B inside a
 * transaction on database A runs on A's connection and reads A's rows, with no error. `withContext`
 * can't help, since it keeps that element; only a separate coroutine leaves it behind.
 */
suspend fun <T> outsideThisTransaction(block: suspend () -> T): T {
    val lookup = CoroutineScope(Dispatchers.IO).async { block() }
    try {
        return lookup.await()
    } catch (e: CancellationException) {
        lookup.cancel(e)
        throw e
    }
}
