package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.script.AdbScript
import com.adbhelper.app.core.script.ScriptEngine
import com.adbhelper.app.core.script.ScriptTemplates
import com.adbhelper.app.data.repositories.ScriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ScriptsUiState(
    val isLoading: Boolean = true,
    val scriptsByCategory: Map<String, List<AdbScript>> = emptyMap(),
    val categories: List<String> = listOf("all", "general", "optimize", "info", "backup", "custom"),
    val error: String? = null
) {
    companion object {
        val categoryDisplayNames = mapOf(
            "all" to "全部",
            "general" to "通用",
            "optimize" to "优化",
            "info" to "信息",
            "backup" to "备份",
            "custom" to "自定义"
        )
    }
}

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    private val scriptRepository: ScriptRepository,
    private val scriptEngine: ScriptEngine
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScriptsUiState())
    val uiState: StateFlow<ScriptsUiState> = _uiState.asStateFlow()

    private var collectJob: Job? = null

    init {
        collectJob = viewModelScope.launch {
            scriptRepository.getAllScripts()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { scripts ->
                    val byCategory = scripts.groupBy { it.category }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        scriptsByCategory = byCategory,
                        error = null
                    )
                }
        }
    }

    fun createScript(name: String, description: String, category: String) {
        viewModelScope.launch {
            val script = AdbScript(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                category = category
            )
            scriptRepository.saveScript(script)
        }
    }

    fun deleteScript(id: String) {
        viewModelScope.launch {
            scriptRepository.deleteScript(id)
        }
    }

    /** 设置待执行脚本，由 UI 导航到终端页执行 */
    fun prepareExecuteScript(script: AdbScript) {
        scriptEngine.pendingScript = script
    }

    fun loadPredefinedScripts() {
        viewModelScope.launch {
            ScriptTemplates.PREDEFINED_SCRIPTS.forEach { script ->
                scriptRepository.saveScript(script)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
