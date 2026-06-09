package com.adbhelper.app.data.repositories

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_LOCAL_SAVE_PATH = stringPreferencesKey("local_save_path")
        val KEY_CONNECT_HISTORY = stringPreferencesKey("connect_history")

        fun formatCacheSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            }
        }
    }

    val darkModeFlow = MutableStateFlow(false)
    val keepScreenOnFlow = MutableStateFlow(true)
    val localSavePathFlow = MutableStateFlow(defaultSavePath())
    val connectHistoryFlow = MutableStateFlow<List<String>>(emptyList())

    suspend fun loadSettings() {
        val prefs = context.settingsDataStore.data.first()
        val darkMode = prefs[KEY_DARK_MODE] ?: false
        val keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: true
        val localSavePath = prefs[KEY_LOCAL_SAVE_PATH] ?: defaultSavePath()
        val historyStr = prefs[KEY_CONNECT_HISTORY] ?: ""
        val history = if (historyStr.isBlank()) emptyList() else historyStr.split(",").filter { it.isNotBlank() }

        darkModeFlow.value = darkMode
        keepScreenOnFlow.value = keepScreenOn
        localSavePathFlow.value = localSavePath
        connectHistoryFlow.value = history

        File(localSavePath).mkdirs()
    }

    private fun defaultSavePath(): String {
        return context.getExternalFilesDir(null)?.absolutePath
            ?: "${context.filesDir.absolutePath}/downloads"
    }

    suspend fun getLanguage(): String {
        return context.settingsDataStore.data.first()[KEY_LANGUAGE] ?: "zh"
    }

    suspend fun updateDarkMode(enabled: Boolean) {
        darkModeFlow.value = enabled
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = enabled
        }
    }

    suspend fun updateKeepScreenOn(enabled: Boolean) {
        keepScreenOnFlow.value = enabled
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_KEEP_SCREEN_ON] = enabled
        }
    }

    suspend fun updateLocalSavePath(path: String) {
        localSavePathFlow.value = path
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LOCAL_SAVE_PATH] = path
        }
    }

    suspend fun addConnectHistory(address: String) {
        val current = connectHistoryFlow.value.toMutableList()
        current.remove(address)
        current.add(0, address)
        val newList = current.take(10)
        connectHistoryFlow.value = newList
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CONNECT_HISTORY] = newList.joinToString(",")
        }
    }

    suspend fun removeConnectHistory(address: String) {
        val newList = connectHistoryFlow.value.filter { it != address }
        connectHistoryFlow.value = newList
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CONNECT_HISTORY] = newList.joinToString(",")
        }
    }
}
