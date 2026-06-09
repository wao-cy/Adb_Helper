package com.adbhelper.app.ui.viewmodels

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.script.AdbScript
import com.adbhelper.app.core.script.ScriptEngine
import com.adbhelper.app.core.script.ScriptExecutionState
import com.adbhelper.app.core.terminal.AnsiParser
import com.adbhelper.app.core.terminal.AnsiSpan
import com.adbhelper.app.core.terminal.OutputLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.IOException
import javax.inject.Inject

data class ShellUiState(
    val outputLines: List<OutputLine> = listOf(
        OutputLine(listOf(AnsiSpan("Welcome to ADB Shell", Color.White))),
        OutputLine(listOf(AnsiSpan("Type 'help' for available commands", Color.White))),
        OutputLine(emptyList())
    ),
    val currentCommand: String = "",
    val isExecuting: Boolean = false,
    val isInteractiveMode: Boolean = false,
    val isScriptMode: Boolean = false,
    val scriptExecutionState: ScriptExecutionState = ScriptExecutionState.Idle,
    val error: String? = null
)

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val adbManager: AdbManager,
    private val scriptEngine: ScriptEngine,
    private val deviceSession: DeviceSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(ShellUiState())
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    // Interactive shell state
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var shellReaderThread: Thread? = null

    // 当前正在执行的普通命令进程
    private var currentProcess: Process? = null

    // Script execution coroutine tracking
    private var outputCollectorJob: Job? = null
    private var stateObserverJob: Job? = null
    private var scriptExecutionJob: Job? = null

    fun updateCommand(command: String) {
        _uiState.value = _uiState.value.copy(currentCommand = command)
    }

    fun executeCommand() {
        val command = _uiState.value.currentCommand.trim()
        if (command.isBlank() || _uiState.value.isExecuting) return

        commandHistory.add(0, command)
        historyIndex = -1

        if (_uiState.value.isInteractiveMode) {
            executeInteractiveCommand(command)
        } else {
            executeNormalCommand(command)
        }
    }

    private fun executeNormalCommand(command: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isExecuting = true,
                currentCommand = ""
            )

            addColoredLine("$ $command", Color.Cyan)

            when {
                command.equals("clear", ignoreCase = true) -> {
                    clearOutput()
                    _uiState.value = _uiState.value.copy(isExecuting = false)
                    return@launch
                }
                command.equals("help", ignoreCase = true) -> {
                    showHelp()
                    _uiState.value = _uiState.value.copy(isExecuting = false)
                    return@launch
                }
                command.equals("history", ignoreCase = true) -> {
                    showHistory()
                    _uiState.value = _uiState.value.copy(isExecuting = false)
                    return@launch
                }
                command.equals("shell", ignoreCase = true) -> {
                    startInteractiveShell()
                    return@launch
                }
            }

            try {
                val serial = deviceSession.selectedSerial.value
                val args = command.split("\\s+".toRegex()).toTypedArray()
                val finalArgs = if (serial != null) arrayOf("-s", serial) + args else args
                val process = adbManager.executeCommand(*finalArgs)
                currentProcess = process
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                currentProcess = null
                if (output.isNotBlank()) {
                    output.lines().forEach { line ->
                        addRawLine(line)
                    }
                }
            } catch (e: Exception) {
                currentProcess = null
                addColoredLine("ERROR: ${e.message}", Color.Red)
            }

            addRawLine("")
            _uiState.value = _uiState.value.copy(isExecuting = false)
        }
    }

    private fun startInteractiveShell() {
        viewModelScope.launch {
            try {
                addColoredLine("Starting interactive shell...", Color.Yellow)
                val serial = deviceSession.selectedSerial.value
                val process = withContext(Dispatchers.IO) {
                    if (serial != null) {
                        adbManager.executeCommand("-s", serial, "shell")
                    } else {
                        adbManager.executeCommand("shell")
                    }
                }
                shellProcess = process
                shellWriter = process.outputStream.bufferedWriter()

                _uiState.value = _uiState.value.copy(
                    isInteractiveMode = true,
                    isExecuting = false
                )

                // Read output in background thread
                shellReaderThread = Thread {
                    try {
                        val reader = process.inputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val raw = line!!
                            viewModelScope.launch(Dispatchers.Main) {
                                addRawLine(raw)
                            }
                        }
                    } catch (_: IOException) {
                        // Process closed
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        exitInteractiveShell()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                addColoredLine("交互式终端已启动，输入 'exit' 退出。", Color.Green)
                addRawLine("")
            } catch (e: Exception) {
                addColoredLine("ERROR: ${e.message}", Color.Red)
                _uiState.value = _uiState.value.copy(isExecuting = false)
            }
        }
    }

    private fun executeInteractiveCommand(command: String) {
        _uiState.value = _uiState.value.copy(currentCommand = "")

        if (command.equals("exit", ignoreCase = true)) {
            exitInteractiveShell()
            return
        }

        addColoredLine("shell$ $command", Color.Cyan)

        try {
            shellWriter?.apply {
                write(command)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            addColoredLine("ERROR: ${e.message}", Color.Red)
            exitInteractiveShell()
        }
    }

    private fun exitInteractiveShell() {
        try {
            shellWriter?.close()
            shellProcess?.destroy()
        } catch (_: Exception) {}
        shellWriter = null
        shellProcess = null
        shellReaderThread = null
        _uiState.value = _uiState.value.copy(isInteractiveMode = false)
        addRawLine("")
        addColoredLine("Shell exited.", Color.Yellow)
        addRawLine("")
    }

    @Suppress("SpellCheckingInspection")
    fun executeQuickCommand(type: String) {
        if (type == "shell") {
            if (_uiState.value.isInteractiveMode) {
                addColoredLine("已在交互式终端中，输入 'exit' 退出。", Color.Yellow)
            } else {
                updateCommand("shell")
                executeCommand()
            }
            return
        }
        val command = when (type) {
            "devices" -> "devices -l"
            "getprop" -> "shell getprop"
            "packages" -> "shell pm list packages"
            "battery" -> "shell dumpsys battery"
            "exit" -> "exit"
            else -> return
        }
        updateCommand(command)
        executeCommand()
    }

    private fun showHelp() {
        val helpText = """
            |Available commands:
            |  devices [-l]      - List connected devices
            |  connect <ip>      - Connect to device via TCP/IP
            |  disconnect        - Disconnect from device
            |  shell <command>   - Run shell command on device
            |  push <local> <remote> - Push file to device
            |  pull <remote> <local> - Pull file from device
            |  install <apk>     - Install APK
            |  uninstall <pkg>   - Uninstall package
            |  clear             - Clear output
            |  help              - Show this help
            |  history           - Show command history
        """.trimMargin()
        helpText.lines().forEach { addRawLine(it) }
    }

    private fun showHistory() {
        addColoredLine("Command history:", Color.Yellow)
        commandHistory.take(20).forEachIndexed { index, cmd ->
            addColoredLine("  ${index + 1}. $cmd", Color.White)
        }
    }

    // Add a line with a single solid color (no ANSI parsing)
    private fun addColoredLine(text: String, color: Color) {
        _uiState.value = _uiState.value.copy(
            outputLines = _uiState.value.outputLines + OutputLine(listOf(AnsiSpan(text, color)))
        )
    }

    // Add a raw line that may contain ANSI escape sequences (parse and render with colors)
    private fun addRawLine(raw: String) {
        val spans = AnsiParser.parse(raw)
        _uiState.value = _uiState.value.copy(
            outputLines = _uiState.value.outputLines + OutputLine(spans)
        )
    }

    fun clearOutput() {
        _uiState.value = _uiState.value.copy(
            outputLines = listOf(
                OutputLine(listOf(AnsiSpan("Welcome to ADB Shell", Color.White))),
                OutputLine(emptyList())
            )
        )
    }

    fun previousCommand() {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size - 1)
        _uiState.value = _uiState.value.copy(
            currentCommand = commandHistory[historyIndex]
        )
    }

    fun nextCommand() {
        if (historyIndex <= 0) {
            historyIndex = -1
            _uiState.value = _uiState.value.copy(currentCommand = "")
        } else {
            historyIndex--
            _uiState.value = _uiState.value.copy(
                currentCommand = commandHistory[historyIndex]
            )
        }
    }

    // ========== 脚本执行 ==========

    /** 检查并启动待执行的脚本 */
    fun checkPendingScript() {
        val script = scriptEngine.pendingScript ?: return
        scriptEngine.pendingScript = null
        startScriptExecution(script)
    }

    private fun startScriptExecution(script: AdbScript) {
        // 1. 取消上一次执行的协程，防止重复收集器
        outputCollectorJob?.cancel()
        stateObserverJob?.cancel()
        scriptExecutionJob?.cancel()

        // 2. 重置脚本引擎状态，清除残留的 Error/Completed
        scriptEngine.dismissExecution()

        // 3. 清空 UI 输出，设置脚本模式
        _uiState.value = _uiState.value.copy(isScriptMode = true)
        clearOutput()
        addColoredLine("═══ 脚本: ${script.name} ═══", Color.Cyan)
        addRawLine("")

        // 4. 准备 ack 通道：递增版本号，创建新 channel
        val ackVersion = scriptEngine.prepareForExecution()

        // 协程 A：收集输出行，处理后发送 ack
        outputCollectorJob = viewModelScope.launch {
            scriptEngine.outputFlow.collect { line ->
                addRawLine(line)
                scriptEngine.sendOutputAck(ackVersion)
            }
        }

        // 协程 B：观察执行状态（交互提示 + 完成/错误）
        stateObserverJob = viewModelScope.launch {
            scriptEngine.executionState.collect { state ->
                _uiState.value = _uiState.value.copy(scriptExecutionState = state)

                when (state) {
                    is ScriptExecutionState.Completed -> {
                        addRawLine("")
                        if (state.result.success) {
                            addColoredLine("═══ 脚本执行完成 ═══", Color.Green)
                        } else {
                            addColoredLine("═══ 脚本执行失败 ═══", Color.Red)
                        }
                    }
                    is ScriptExecutionState.Error -> {
                        addRawLine("")
                        addColoredLine("[错误] ${state.message}", Color.Red)
                    }
                    else -> {}
                }
            }
        }

        // 协程 C：执行脚本
        scriptExecutionJob = viewModelScope.launch {
            scriptEngine.executeScript(script, serial = deviceSession.selectedSerial.value)
        }
    }

    fun submitScriptInput(value: String) {
        scriptEngine.submitInput(value)
    }

    fun confirmScriptContinue() {
        scriptEngine.confirmContinue()
    }

    fun exitScriptMode() {
        scriptExecutionJob?.cancel()
        scriptEngine.dismissExecution()
        outputCollectorJob?.cancel()
        stateObserverJob?.cancel()
        clearOutput()
        _uiState.value = _uiState.value.copy(
            isScriptMode = false,
            scriptExecutionState = ScriptExecutionState.Idle
        )
    }

    fun copyAllOutput(): String {
        return _uiState.value.outputLines.joinToString("\n") { line ->
            line.spans.joinToString("") { it.text }
        }
    }
}
