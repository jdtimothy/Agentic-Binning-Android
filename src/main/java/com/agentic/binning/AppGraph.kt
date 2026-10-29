package com.agentic.binning

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.agentic.binning.settings.SettingsStore

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Hand-rolled dependency container; later tasks add properties. */
class AppGraph(val context: Context) {
    val settings: SettingsStore by lazy { SettingsStore(context.settingsDataStore) }
}
