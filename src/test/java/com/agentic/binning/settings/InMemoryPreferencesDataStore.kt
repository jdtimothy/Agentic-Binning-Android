package com.agentic.binning.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * In-memory stand-in for the Preferences DataStore. The file-backed DataStore cannot write
 * twice on Windows JVMs (File.renameTo refuses to overwrite), which broke the round-trip
 * test on the Windows dev machine; the store logic under test is identical either way.
 */
internal class InMemoryPreferencesDataStore(private val readError: Throwable? = null) : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = if (readError == null) state else flow { throw readError }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
