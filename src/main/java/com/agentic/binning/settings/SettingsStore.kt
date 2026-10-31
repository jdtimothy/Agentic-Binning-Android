package com.agentic.binning.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agentic.binning.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val keepScreenOn: Boolean = false,
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
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        dataStore.edit { it[KEEP_SCREEN_ON] = on }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
