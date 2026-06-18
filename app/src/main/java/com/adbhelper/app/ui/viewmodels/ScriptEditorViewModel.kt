package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.script.AdbScript
import com.adbhelper.app.core.script.ScriptCommand
import com.adbhelper.app.core.script.ScriptEngine
import com.adbhelper.app.data.repositories.ScriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ScriptEditorUiState(
    val isLoading: Boolean = false,
    val scriptId: String = "",
    val name: String = "",
    val description: String = "",
    val category: String = "general",
    val commands: List<ScriptCommand> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val isSaving: Boolean = false,
    val error: String? = null,
    val isTextEditMode: Boolean = false,
    val scriptText: String = ""
)

@HiltViewModel
class ScriptEditorViewModel @Inject constructor(
    private val scriptRepository: ScriptRepository,
    private val scriptEngine: ScriptEngine
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScriptEditorUiState())
    val uiState: StateFlow<ScriptEditorUiState> = _uiState.asStateFlow()

    fun loadScript(scriptId: String) {
        if (scriptId == "new") {
            _uiState.value = ScriptEditorUiState(
                scriptId = UUID.randomUUID().toString()
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val script = scriptRepository.getScriptById(scriptId)
                if (script != null) {
                    _uiState.value = ScriptEditorUiState(
                        isLoading = false,
                        scriptId = script.id,
                        name = script.name,
                        description = script.description,
                        category = script.category,
                        commands = script.commands,
                        variables = script.variables
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Script not found"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(description = description)
    }

    fun updateCategory(category: String) {
        _uiState.value = _uiState.value.copy(category = category)
    }

    fun toggleEditMode() {
        val state = _uiState.value
        if (state.isTextEditMode) {
            // 文本→表单：解析文本为命令列表
            val commands = parseTextToCommands(state.scriptText)
            _uiState.value = state.copy(isTextEditMode = false, commands = commands)
        } else {
            // 表单→文本：将命令列表转为文本
            val text = commandsToText(state.commands)
            _uiState.value = state.copy(isTextEditMode = true, scriptText = text)
        }
    }

    fun updateScriptText(text: String) {
        _uiState.value = _uiState.value.copy(scriptText = text)
    }

    private fun commandsToText(commands: List<ScriptCommand>): String {
        return commands.joinToString("\n") { cmd ->
            if (cmd.command.startsWith("#")) {
                cmd.command
            } else {
                buildString {
                    if (cmd.ignoreError) append("! ")
                    append(cmd.command)
                }
            }
        }
    }

    fun parseTextToCommands(text: String): List<ScriptCommand> {
        val commands = mutableListOf<ScriptCommand>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("#")) {
                commands.add(
                    ScriptCommand(command = "# ${trimmed.removePrefix("#").trim()}")
                )
                continue
            }

            val ignoreError = trimmed.startsWith("! ")
            val commandStr = if (ignoreError) trimmed.removePrefix("! ").trim() else trimmed

            commands.add(
                ScriptCommand(command = commandStr, ignoreError = ignoreError)
            )
        }
        return commands
    }

    fun addCommand(command: ScriptCommand) {
        _uiState.value = _uiState.value.copy(
            commands = _uiState.value.commands + command
        )
    }

    fun updateCommand(index: Int, command: ScriptCommand) {
        val commands = _uiState.value.commands.toMutableList()
        if (index in commands.indices) {
            commands[index] = command
            _uiState.value = _uiState.value.copy(commands = commands)
        }
    }

    fun removeCommand(index: Int) {
        val commands = _uiState.value.commands.toMutableList()
        if (index in commands.indices) {
            commands.removeAt(index)
            _uiState.value = _uiState.value.copy(commands = commands)
        }
    }

    fun moveCommand(fromIndex: Int, toIndex: Int) {
        val commands = _uiState.value.commands.toMutableList()
        if (fromIndex in commands.indices && toIndex in commands.indices) {
            val command = commands.removeAt(fromIndex)
            commands.add(toIndex, command)
            _uiState.value = _uiState.value.copy(commands = commands)
        }
    }

    fun addVariable() {
        val variables = _uiState.value.variables.toMutableMap()
        variables["var_${variables.size + 1}"] = ""
        _uiState.value = _uiState.value.copy(variables = variables)
    }

    fun updateVariableKey(oldKey: String, newKey: String) {
        val variables = _uiState.value.variables.toMutableMap()
        val value = variables.remove(oldKey) ?: ""
        variables[newKey] = value
        _uiState.value = _uiState.value.copy(variables = variables)
    }

    fun updateVariableValue(key: String, value: String) {
        val variables = _uiState.value.variables.toMutableMap()
        variables[key] = value
        _uiState.value = _uiState.value.copy(variables = variables)
    }

    fun removeVariable(key: String) {
        val variables = _uiState.value.variables.toMutableMap()
        variables.remove(key)
        _uiState.value = _uiState.value.copy(variables = variables)
    }

    fun saveScript() {
        syncTextMode()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val script = AdbScript(
                    id = _uiState.value.scriptId,
                    name = _uiState.value.name,
                    description = _uiState.value.description,
                    category = _uiState.value.category,
                    commands = _uiState.value.commands,
                    variables = _uiState.value.variables
                )
                scriptRepository.saveScript(script)
                _uiState.value = _uiState.value.copy(isSaving = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = e.message
                )
            }
        }
    }

    /** 准备脚本并设置为待执行，返回脚本名称供 UI 导航 */
    fun prepareExecuteScript(): String {
        syncTextMode()
        val script = AdbScript(
            id = _uiState.value.scriptId,
            name = _uiState.value.name,
            description = _uiState.value.description,
            category = _uiState.value.category,
            commands = _uiState.value.commands,
            variables = _uiState.value.variables
        )
        scriptEngine.pendingScript = script
        return script.name
    }

    private fun syncTextMode() {
        val state = _uiState.value
        if (state.isTextEditMode) {
            _uiState.value = state.copy(commands = parseTextToCommands(state.scriptText))
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
