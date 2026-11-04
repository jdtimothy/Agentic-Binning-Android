package com.agentic.binning.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agentic.binning.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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

    @Test fun defaults_areLfmAnd16() = runTest {
        val s = store.settings.first()
        assertEquals(SortingModelChoice.Lfm3_1_7B, s.sortingModel)
        assertEquals(16, s.maxFramesPerClip)
        assertEquals(false, s.sortingModelChosenByUser)
    }

    @Test fun frameCap_snapsToOptions() = runTest {
        fun snapped(n: Int): Int = runBlocking { store.setMaxFramesPerClip(n); store.settings.first().maxFramesPerClip }
        assertEquals(16, snapped(18))
        assertEquals(48, snapped(44))
        assertEquals(64, snapped(100))
        assertEquals(16, snapped(8))
        assertEquals(16, snapped(20)) // tie snaps down
        assertEquals(32, snapped(40)) // tie snaps down
        assertEquals(24, snapped(24))
    }

    @Test fun sortingModel_roundTripsAndMarksUserChoice() = runTest {
        store.setSortingModel(SortingModelChoice.Claude)
        val s = store.settings.first()
        assertEquals(SortingModelChoice.Claude, s.sortingModel)
        assertEquals(true, s.sortingModelChosenByUser)
    }

    @Test fun keySaved_switchesToClaude_whenNotChosen() = runTest {
        store.onApiKeySaved()
        val s = store.settings.first()
        assertEquals(SortingModelChoice.Claude, s.sortingModel)
        assertEquals(false, s.sortingModelChosenByUser)
    }

    @Test fun keySaved_keepsUserChoice() = runTest {
        store.setSortingModel(SortingModelChoice.Lfm3_1_7B)
        store.onApiKeySaved()
        assertEquals(SortingModelChoice.Lfm3_1_7B, store.settings.first().sortingModel)
    }

    @Test fun unknownStoredSortingModelFallsBackToDefault() = runTest {
        val ds = InMemoryPreferencesDataStore()
        ds.edit { it[stringPreferencesKey("sorting_model")] = "Removed_8B" }
        assertEquals(SortingModelChoice.Lfm3_1_7B, SettingsStore(ds).settings.first().sortingModel)
    }

    @Test fun removedQwenChoice_fallsBackToDefault() = runTest {
        // Qwen3-4B was offered briefly in Plan 2a and dropped (its qairt build does not load).
        val ds = InMemoryPreferencesDataStore()
        ds.edit { it[stringPreferencesKey("sorting_model")] = "Qwen3_4B" }
        assertEquals(SortingModelChoice.Lfm3_1_7B, SettingsStore(ds).settings.first().sortingModel)
    }

    @Test fun mediaPicker_defaultsToAndroidPhotoPicker() = runTest {
        assertEquals(MediaPickerChoice.AndroidPhotoPicker, store.settings.first().mediaPicker)
    }

    @Test fun mediaPicker_roundTrips() = runTest {
        store.setMediaPicker(MediaPickerChoice.SamsungGallery)
        assertEquals(MediaPickerChoice.SamsungGallery, store.settings.first().mediaPicker)
    }

    @Test fun unknownStoredMediaPickerFallsBackToDefault() = runTest {
        val ds = InMemoryPreferencesDataStore()
        ds.edit { it[stringPreferencesKey("media_picker")] = "Removed" }
        assertEquals(MediaPickerChoice.AndroidPhotoPicker, SettingsStore(ds).settings.first().mediaPicker)
    }

    @Test fun sortingChoices_areClaudeAndLfmOnly() =
        assertEquals(listOf("Claude", "Lfm3_1_7B"), SortingModelChoice.entries.map { it.name })
}
