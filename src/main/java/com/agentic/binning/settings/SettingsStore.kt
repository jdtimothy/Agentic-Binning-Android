package com.agentic.binning.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agentic.binning.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Which model sorts the library into categories. A stored name that is no longer here (Qwen3-4B,
 * dropped in Plan 2a because its `qairt` build does not load) reads back as the default.
 */
enum class SortingModelChoice { Claude, Lfm3_1_7B }

/** Which picker "Pick photos and videos" opens. */
enum class MediaPickerChoice { AndroidPhotoPicker, SamsungGallery }

/** The frame counts a clip may be capped at; [SettingsStore.setMaxFramesPerClip] snaps to these. */
val FRAME_CAP_OPTIONS: List<Int> = listOf(16, 24, 32, 48, 64)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val keepScreenOn: Boolean = false,
    /** Offline-capable default; switches to Claude when a key is first saved (see [SettingsStore.onApiKeySaved]). */
    val sortingModel: SortingModelChoice = SortingModelChoice.Lfm3_1_7B,
    val maxFramesPerClip: Int = FRAME_CAP_OPTIONS.first(),
    /** True once the user picked a sorting model themselves; saving a key then no longer changes it. */
    val sortingModelChosenByUser: Boolean = false,
    val mediaPicker: MediaPickerChoice = MediaPickerChoice.AndroidPhotoPicker,
)

/** Persists [AppSettings] in a Preferences DataStore. */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.catch { e ->
        // An unreadable store must not crash the app: fall back to defaults.
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { prefs ->
        AppSettings(
            themeMode = prefs[THEME_MODE]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.System,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: false,
            sortingModel = prefs[SORTING_MODEL]
                ?.let { name -> SortingModelChoice.entries.firstOrNull { it.name == name } }
                ?: SortingModelChoice.Lfm3_1_7B,
            maxFramesPerClip = prefs[MAX_FRAMES]?.let(::snapFrameCap) ?: FRAME_CAP_OPTIONS.first(),
            sortingModelChosenByUser = prefs[SORTING_MODEL_CHOSEN] ?: false,
            mediaPicker = prefs[MEDIA_PICKER]
                ?.let { name -> MediaPickerChoice.entries.firstOrNull { it.name == name } }
                ?: MediaPickerChoice.AndroidPhotoPicker,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        dataStore.edit { it[KEEP_SCREEN_ON] = on }
    }

    /** The user's pick from Settings: remembered, so a later key save never overrides it. */
    suspend fun setSortingModel(choice: SortingModelChoice) {
        dataStore.edit {
            it[SORTING_MODEL] = choice.name
            it[SORTING_MODEL_CHOSEN] = true
        }
    }

    /** Stores [n] snapped to the nearest of [FRAME_CAP_OPTIONS] (a tie snaps down). */
    suspend fun setMaxFramesPerClip(n: Int) {
        dataStore.edit { it[MAX_FRAMES] = snapFrameCap(n) }
    }

    suspend fun setMediaPicker(choice: MediaPickerChoice) {
        dataStore.edit { it[MEDIA_PICKER] = choice.name }
    }

    /** Call after an API key was saved: switches to Claude unless the user already chose a model. */
    suspend fun onApiKeySaved() {
        dataStore.edit {
            if (it[SORTING_MODEL_CHOSEN] != true) it[SORTING_MODEL] = SortingModelChoice.Claude.name
        }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SORTING_MODEL = stringPreferencesKey("sorting_model")
        val MAX_FRAMES = intPreferencesKey("max_frames_per_clip")
        val SORTING_MODEL_CHOSEN = booleanPreferencesKey("sorting_model_chosen_by_user")
        val MEDIA_PICKER = stringPreferencesKey("media_picker")
    }
}

/** The nearest of [FRAME_CAP_OPTIONS]; the options ascend, so a tie resolves to the lower one. */
internal fun snapFrameCap(n: Int): Int = FRAME_CAP_OPTIONS.minBy { abs(it - n) }
