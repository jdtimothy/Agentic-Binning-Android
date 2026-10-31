package com.agentic.binning.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.agentic.binning.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * In-memory stand-in for the Preferences DataStore. The file-backed DataStore cannot write
 * twice on Windows JVMs (File.renameTo refuses to overwrite), which broke the round-trip
 * test on the Windows dev machine; the store logic under test is identical either way.
 */
private class InMemoryPreferencesDataStore(private val readError: Throwable? = null) : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = if (readError == null) state else flow { throw readError }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class SettingsStoreTest {
    private lateinit var store: SettingsStore

    @Before fun setUp() {
        store = SettingsStore(InMemoryPreferencesDataStore())
    }

    @Test fun defaults() = runTest { assertEquals(AppSettings(), store.settings.first()) }

    @Test fun roundTrip() = runTest {
        store.setThemeMode(ThemeMode.Light); store.setKeepScreenOn(true)
        assertEquals(AppSettings(ThemeMode.Light, true), store.settings.first())
    }

    @Test fun unreadableDataStoreYieldsDefaults() = runTest {
        val broken = SettingsStore(InMemoryPreferencesDataStore(IOException("corrupt")))
        assertEquals(AppSettings(), broken.settings.first())
    }

    @Test fun nonIoErrorsStillPropagate() = runTest {
        val broken = SettingsStore(InMemoryPreferencesDataStore(IllegalStateException("bug")))
        var thrown: Throwable? = null
        try { broken.settings.first() } catch (e: IllegalStateException) { thrown = e }
        assertEquals("bug", thrown?.message)
    }
}
