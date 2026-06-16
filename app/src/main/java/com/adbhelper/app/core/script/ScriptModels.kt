package com.adbhelper.app.core.script

import com.adbhelper.app.core.shell.ShellResult
import kotlinx.serialization.Serializable

@Serializable
data class AdbScript(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "general",
    val commands: List<ScriptCommand> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ScriptCommand(
    val command: String,
    val description: String = "",
    val ignoreError: Boolean = false,
    val timeout: Long = 30000,
    val condition: String? = null
)

data class ScriptExecutionResult(
    val scriptId: String,
    val scriptName: String,
    val commandResults: List<CommandResult>,
    val startTime: Long,
    val endTime: Long,
    val success: Boolean
)

data class CommandResult(
    val command: String,
    val result: ShellResult?,
    val error: String? = null,
    val skipped: Boolean = false
)

sealed class ScriptExecutionState {
    object Idle : ScriptExecutionState()
    data class Running(
        val currentCommand: Int,
        val totalCommands: Int,
        val commandDescription: String,
        val outputLines: List<String> = emptyList()
    ) : ScriptExecutionState()
    data class Completed(val result: ScriptExecutionResult) : ScriptExecutionState()
    data class Error(val message: String) : ScriptExecutionState()
    data class NeedInput(val prompt: String) : ScriptExecutionState()
    data class NeedConfirm(val prompt: String) : ScriptExecutionState()
}
