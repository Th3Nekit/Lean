package com.th3web.lean.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class SettingsSnapshotStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun load(): Settings = runCatching {
        preferences.getString(VALUE_KEY, null)
            ?.let { Serialization.json.decodeFromString<Settings>(it) }
            ?: Settings()
    }.getOrDefault(Settings())

    fun save(settings: Settings) {
        runCatching {
            preferences.edit()
                .putString(VALUE_KEY, Serialization.json.encodeToString(settings))
                .commit()
        }
    }

    companion object {
        internal const val VALUE_KEY = "settings"
        private const val PREFERENCES_NAME = "lean_settings_startup"
    }
}
