package com.adbhelper.app.core.script

import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.ShellResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScriptEngine @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val adbManager: AdbManager
) {
    private val _executionState = MutableStateFlow<ScriptExecutionState>(ScriptExecutionState.Idle)
    val executionState: StateFlow<ScriptExecutionState> = _executionState.asStateFlow()

    /** 实时输出流，每行立即发射，不依赖 StateFlow 状态变更 */
    private val _outputFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<String> = _outputFlow.asSharedFlow()

    /** Ack 通道：收集器处理完每条输出后发送确认 */
    @Volatile
    private var outputAckChannel = Channel<Unit>(Channel.UNLIMITED)

    /** 版本号：防止前一次执行的收集器发送的 ack 被新执行消费 */
    @Volatile
    private var outputAckVersion = 0

    private var inputDeferred: CompletableDeferred<String>? = null
    private var confirmDeferred: CompletableDeferred<Unit>? = null

    /** 特殊命令处理器注册表，按前缀长度降序（长前缀优先匹配） */
    private val specialHandlers: List<SpecialCommandHandler> = listOf(
        SetPromptCommandHandler(),  // "set /p "
        SetArithCommandHandler(),   // "set /a "
        SetCommandHandler(),        // "set "
        CaptureCommandHandler(),    // "capture "
        DelayCommandHandler(),      // "delay "
        InputCommandHandler(),      // "input "
        ConfirmCommandHandler(),    // "confirm "
        EchoCommandHandler(),       // "echo "
        GotoCommandHandler(),       // "goto "
        IfCommandHandler(),         // "if "
        LabelCommandHandler()       // ":"
    ).sortedByDescending { it.prefix.length }

    /** 待执行的脚本，由编辑页设置，ShellViewModel 读取后清空 */
    var pendingScript: AdbScript? = null

    suspend fun executeScript(
        script: AdbScript,
        variables: Map<String, String> = emptyMap(),
        serial: String? = null
    ): ScriptExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val commandResults = mutableListOf<CommandResult>()
        val mergedVariables = (script.variables + variables).toMutableMap()
        val outputLines = mutableListOf<String>()

        suspend fun emitLine(line: String) {
            outputLines.add(line)
            if (_outputFlow.tryEmit(line)) {
                withTimeoutOrNull(100L) { outputAckChannel.receive() }
            }
        }

        _executionState.value = ScriptExecutionState.Running(
            currentCommand = 0,
            totalCommands = script.commands.size,
            commandDescription = ""
        )

        // 预处理标签索引
        val labelIndexMap = buildLabelIndexMap(script.commands)

        var success = true
        var errorMessage: String? = null

        try {
            var commandIndex = 0
            var jumpTargetIndex: Int? = null

            while (commandIndex < script.commands.size) {
                val scriptCommand = script.commands[commandIndex]

                if (!isActive) {
                    break
                }

                val resolvedCommand = replaceVariables(scriptCommand.command, mergedVariables)

                // 跳过注释和空行
                if (resolvedCommand.isBlank() || resolvedCommand.startsWith("#")) {
                    commandResults.add(CommandResult(command = resolvedCommand, result = null, skipped = true))
                    commandIndex++
                    continue
                }

                // ! 前缀：遇到错误时退出脚本（默认忽略错误继续执行）
                val exitOnError = resolvedCommand.startsWith("! ") || scriptCommand.ignoreError
                var effectiveCommand = if (resolvedCommand.startsWith("! ")) resolvedCommand.removePrefix("! ") else resolvedCommand
                // 去掉可选的 "adb " 前缀
                if (effectiveCommand.startsWith("adb ", ignoreCase = true)) {
                    effectiveCommand = effectiveCommand.removePrefix("adb ")
                }

                // 特殊命令：通过 handler 列表查找（长前缀优先匹配）
                val handler = specialHandlers.firstOrNull {
                    effectiveCommand.startsWith(it.prefix, ignoreCase = true)
                }
                if (handler != null) {
                    val ctx = SpecialCommandContext(
                        index = commandIndex,
                        totalCommands = script.commands.size,
                        outputLines = outputLines,
                        mergedVariables = mergedVariables,
                        updateRunning = ::updateRunning,
                        emitLine = ::emitLine,
                        setState = { _executionState.value = it },
                        addResult = { commandResults.add(it) },
                        awaitInput = { deferred ->
                            inputDeferred = deferred
                            try { deferred.await() } finally { inputDeferred = null }
                        },
                        awaitConfirm = { deferred ->
                            confirmDeferred = deferred
                            try { deferred.await() } finally { confirmDeferred = null }
                        },
                        jumpTo = { label ->
                            labelIndexMap[label]?.let {
                                jumpTargetIndex = it
                                true
                            } ?: false
                        },
                        executeShell = { cmd, serialOverride ->
                            val effSerial = serialOverride ?: serial
                            if (cmd.startsWith("shell ", ignoreCase = true)) {
                                shellExecutor.execute(cmd, effSerial)
                            } else {
                                val args = cmd.split("\\s+".toRegex()).toTypedArray()
                                val output = adbManager.executeAdbCommand(*args, serial = effSerial)
                                ShellResult(command = cmd, output = output, exitCode = 0)
                            }
                        }
                    )
                    handler.handle(effectiveCommand, scriptCommand, ctx)
                } else {
                    // 更新状态
                    updateRunning(commandIndex, script.commands.size, scriptCommand.description, outputLines)

                    // Check condition
                    if (scriptCommand.condition != null) {
                        val conditionResult = evaluateCondition(scriptCommand.condition, mergedVariables, serial)
                        if (!conditionResult) {
                            emitLine("[跳过] $effectiveCommand (条件不满足)")
                            commandResults.add(CommandResult(command = effectiveCommand, result = null, skipped = true))
                            commandIndex++
                            continue
                        }
                    }

                    // 执行普通命令（带流式输出）
                    try {
                        val lines = mutableListOf<String>()
                        emitLine("> $resolvedCommand")

                        val result = withTimeout(scriptCommand.timeout) {
                            if (effectiveCommand.startsWith("shell ", ignoreCase = true)) {
                                shellExecutor.execute(effectiveCommand, serial)
                            } else {
                                val args = effectiveCommand.split("\\s+".toRegex()).toTypedArray()
                                val output = adbManager.executeAdbCommand(*args, serial = serial)
                                ShellResult(command = effectiveCommand, output = output, exitCode = 0)
                            }
                        }

                        result.output.lines().forEach { line ->
                            if (line.isNotBlank()) {
                                lines.add(line)
                                emitLine(line)
                            }
                        }
                        // 更新 UI 输出
                        _executionState.value = ScriptExecutionState.Running(
                            currentCommand = commandIndex + 1,
                            totalCommands = script.commands.size,
                            commandDescription = scriptCommand.description,
                            outputLines = outputLines.toList()
                        )

                        commandResults.add(CommandResult(command = effectiveCommand, result = result))

                        if (result.exitCode != 0 && exitOnError) {
                            success = false
                            errorMessage = "Command failed: $effectiveCommand\n${result.output}"
                            emitLine("[错误] 命令失败，退出码: ${result.exitCode}")
                            break
                        }
                    } catch (_: TimeoutCancellationException) {
                        emitLine("[超时] $effectiveCommand")
                        commandResults.add(CommandResult(command = effectiveCommand, result = null, error = "Command timed out after ${scriptCommand.timeout}ms"))
                        if (exitOnError) {
                            success = false
                            errorMessage = "Command timed out: $effectiveCommand"
                            break
                        }
                    } catch (e: Exception) {
                        emitLine("[异常] ${e.message}")
                        commandResults.add(CommandResult(command = effectiveCommand, result = null, error = e.message))
                        if (exitOnError) {
                            success = false
                            errorMessage = "Error: ${e.message}"
                            break
                        }
                    }
                }

                // 处理 goto 跳转
                if (jumpTargetIndex != null) {
                    commandIndex = jumpTargetIndex!!
                    jumpTargetIndex = null
                } else {
                    commandIndex++
                }
            }
        } finally {
            // 等待所有输出被收集器处理完毕后，再设置最终状态
            // 确保 observer 只触发一次，且在所有输出之后
            withTimeoutOrNull(500L) {
                while (outputAckChannel.tryReceive().isSuccess) { /* drain stale acks */ }
            }
            val finalError = errorMessage
            _executionState.value = if (!isActive) {
                ScriptExecutionState.Idle
            } else if (finalError != null) {
                ScriptExecutionState.Error(finalError)
            } else {
                ScriptExecutionState.Completed(
                    ScriptExecutionResult(
                        scriptId = script.id, scriptName = script.name,
                        commandResults = commandResults,
                        startTime = startTime, endTime = System.currentTimeMillis(),
                        success = success
                    )
                )
            }
        }

        ScriptExecutionResult(
            scriptId = script.id, scriptName = script.name,
            commandResults = commandResults,
            startTime = startTime, endTime = System.currentTimeMillis(),
            success = success
        )
    }

    private fun updateRunning(index: Int, total: Int, description: String, outputLines: List<String>) {
        _executionState.value = ScriptExecutionState.Running(
            currentCommand = index + 1,
            totalCommands = total,
            commandDescription = description,
            outputLines = outputLines
        )
    }

    fun submitInput(value: String) {
        inputDeferred?.complete(value)
    }

    fun confirmContinue() {
        confirmDeferred?.complete(Unit)
    }

    fun dismissExecution() {
        inputDeferred?.complete("")
        confirmDeferred?.complete(Unit)
        _executionState.value = ScriptExecutionState.Idle
    }

    /**
     * 准备新的脚本执行。必须由 ViewModel 在启动执行协程之前调用。
     * 返回 ack 版本号，收集器需通过 [sendOutputAck] 回传。
     */
    fun prepareForExecution(): Int {
        outputAckVersion++
        outputAckChannel = Channel(Channel.UNLIMITED)
        return outputAckVersion
    }

    /**
     * 由输出收集器在处理每条输出后调用。
     * [version] 必须与当前 [outputAckVersion] 匹配，过期的 ack 会被丢弃。
     */
    fun sendOutputAck(version: Int) {
        if (version == outputAckVersion) {
            outputAckChannel.trySend(Unit)
        }
    }

    private fun replaceVariables(command: String, variables: Map<String, String>): String {
        var result = command
        variables.forEach { (key, value) ->
            result = result.replace("\${$key}", value)
            result = result.replace("$$key", value)
        }
        return result
    }

    private suspend fun evaluateCondition(
        condition: String,
        variables: Map<String, String>,
        serial: String?
    ): Boolean {
        val resolvedCondition = replaceVariables(condition, variables)

        return when {
            resolvedCondition.startsWith("exists:") -> {
                val path = resolvedCondition.removePrefix("exists:")
                val result = shellExecutor.execute("ls $path 2>/dev/null && echo true || echo false", serial)
                result.output.trim() == "true"
            }
            resolvedCondition.startsWith("not_exists:") -> {
                val path = resolvedCondition.removePrefix("not_exists:")
                val result = shellExecutor.execute("ls $path 2>/dev/null && echo false || echo true", serial)
                result.output.trim() == "true"
            }
            resolvedCondition.startsWith("package:") -> {
                val pkg = resolvedCondition.removePrefix("package:")
                val result = shellExecutor.execute("pm list packages | grep -q $pkg && echo true || echo false", serial)
                result.output.trim() == "true"
            }
            else -> true
        }
    }

    /** 构建标签名 → 命令索引的映射（用于 goto） */
    private fun buildLabelIndexMap(commands: List<ScriptCommand>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        commands.forEachIndexed { i, cmd ->
            val trimmed = cmd.command.trim()
            if (trimmed.startsWith(":") && trimmed.length > 1 && !trimmed.substring(1).contains(" ")) {
                map[trimmed.removePrefix(":")] = i
            }
        }
        return map
    }


}
