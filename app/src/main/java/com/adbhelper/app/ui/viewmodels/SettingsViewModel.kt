package com.adbhelper.app.ui.viewmodels

import android.content.Context
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.data.repositories.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val keepScreenOn: Boolean = true,
    val darkMode: Boolean = false,
    val language: String = "zh",
    val localSavePath: String = "",
    val cacheSize: Long = 0L,
    val defaultTab: Int = 0,
    val fetchIcons: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        loadCacheSize()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.loadSettings()
            _uiState.value = _uiState.value.copy(
                language = settingsRepository.getLanguage(),
                darkMode = settingsRepository.darkModeFlow.value,
                keepScreenOn = settingsRepository.keepScreenOnFlow.value,
                localSavePath = settingsRepository.localSavePathFlow.value,
                defaultTab = settingsRepository.defaultTabFlow.value,
                fetchIcons = settingsRepository.fetchIconsFlow.value
            )
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(keepScreenOn = enabled)
        viewModelScope.launch {
            settingsRepository.updateKeepScreenOn(enabled)
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkMode = enabled)
        viewModelScope.launch {
            settingsRepository.updateDarkMode(enabled)
        }
    }

    fun updateLocalSavePath(path: String) {
        _uiState.value = _uiState.value.copy(localSavePath = path)
        viewModelScope.launch {
            settingsRepository.updateLocalSavePath(path)
        }
    }

    fun updateDefaultTab(index: Int) {
        _uiState.value = _uiState.value.copy(defaultTab = index)
        viewModelScope.launch {
            settingsRepository.updateDefaultTab(index)
        }
    }

    fun updateFetchIcons(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(fetchIcons = enabled)
        viewModelScope.launch {
            settingsRepository.updateFetchIcons(enabled)
        }
    }

    fun addConnectHistory(address: String) {
        viewModelScope.launch {
            settingsRepository.addConnectHistory(address)
        }
    }

    fun removeConnectHistory(address: String) {
        viewModelScope.launch {
            settingsRepository.removeConnectHistory(address)
        }
    }

    // ========== 缓存管理 ==========

    private fun loadCacheSize() {
        viewModelScope.launch {
            val size = calculateDirSize(context.cacheDir) +
                    (context.externalCacheDir?.let { calculateDirSize(it) } ?: 0L) +
                    calculateDirSize(context.codeCacheDir)
            _uiState.value = _uiState.value.copy(cacheSize = size)
        }
    }

    fun clearAppCache() {
        viewModelScope.launch {
            deleteDir(context.cacheDir)
            context.externalCacheDir?.let { deleteDir(it) }
            deleteDir(context.codeCacheDir)
            // ADB server 日志目录被删，重建以保证 server 下次能正常启动
            context.cacheDir.mkdirs()
            _uiState.value = _uiState.value.copy(cacheSize = 0L)
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { deleteDir(it) }
        }
        dir.delete()
    }
}
