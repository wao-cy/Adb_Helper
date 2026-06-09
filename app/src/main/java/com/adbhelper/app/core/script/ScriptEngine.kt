package com.adbhelper.app.core.script

import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.ShellResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

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
    val timeout: Long = 30000, // 30 seconds
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

    /** 特殊命令处理器注册表：前缀 → handler（长前缀在前，确保优先匹配） */
    private val specialHandlers: Map<String, SpecialCommandHandler> = listOf(
        DelayCommandHandler(),
        InputCommandHandler(),
        ConfirmCommandHandler(),
        SetPromptCommandHandler(),  // "set /p "
        SetArithCommandHandler(),   // "set /a "
        SetCommandHandler(),        // "set "
        EchoCommandHandler()        // "echo "
    ).associateBy { it.prefix }

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

        var success = true
        var errorMessage: String? = null

        try {
            for ((index, scriptCommand) in script.commands.withIndex()) {
                if (!isActive) {
                    break
                }

                val resolvedCommand = replaceVariables(scriptCommand.command, mergedVariables)

                // 跳过注释和空行
                if (resolvedCommand.isBlank() || resolvedCommand.startsWith("#")) {
                    commandResults.add(CommandResult(command = resolvedCommand, result = null, skipped = true))
                    continue
                }

                // ! 前缀：遇到错误时退出脚本（默认忽略错误继续执行）
                val exitOnError = resolvedCommand.startsWith("! ") || scriptCommand.ignoreError
                val effectiveCommand = if (resolvedCommand.startsWith("! ")) resolvedCommand.removePrefix("! ") else resolvedCommand

                // 特殊命令：通过 handler 注册表查找
                val handler = specialHandlers.entries.find {
                    effectiveCommand.startsWith(it.key, ignoreCase = true)
                }?.value
                if (handler != null) {
                    val ctx = SpecialCommandContext(
                        index = index,
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
                        }
                    )
                    handler.handle(effectiveCommand, scriptCommand, ctx)
                    continue
                }

                // 更新状态
                updateRunning(index, script.commands.size, scriptCommand.description, outputLines)

                // Check condition
                if (scriptCommand.condition != null) {
                    val conditionResult = evaluateCondition(scriptCommand.condition, mergedVariables, serial)
                    if (!conditionResult) {
                        emitLine("[跳过] $effectiveCommand (条件不满足)")
                        commandResults.add(CommandResult(command = effectiveCommand, result = null, skipped = true))
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
                        currentCommand = index + 1,
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

    // Predefined script templates
    companion object {
        val DEVICE_INFO_SCRIPT = AdbScript(
            id = "device_info",
            name = "设备信息采集",
            description = "一键收集设备型号、系统版本、电池、存储等关键信息",
            category = "info",
            commands = listOf(
                ScriptCommand("# 设备基本信息", ""),
                ScriptCommand("shell getprop ro.product.model", "设备型号"),
                ScriptCommand("shell getprop ro.product.brand", "设备品牌"),
                ScriptCommand("shell getprop ro.build.version.release", "Android 版本"),
                ScriptCommand("shell getprop ro.build.display.id", "系统版本号"),
                ScriptCommand("# 硬件信息", ""),
                ScriptCommand("shell cat /proc/cpuinfo | head -5", "CPU 信息"),
                ScriptCommand("shell cat /proc/meminfo | head -3", "内存信息"),
                ScriptCommand("shell wm size", "屏幕分辨率"),
                ScriptCommand("shell wm density", "屏幕密度"),
                ScriptCommand("# 电池与存储", ""),
                ScriptCommand("shell dumpsys battery | grep -E 'level|status|temperature'", "电池状态"),
                ScriptCommand("shell df -h /data", "存储使用情况")
            )
        )

        val APP_CLEANUP_SCRIPT = AdbScript(
            id = "app_cleanup",
            name = "交互式应用清理",
            description = "交互式输入包名，确认后卸载应用（支持用户输入和确认）",
            category = "custom",
            commands = listOf(
                ScriptCommand("input 请输入要清理的应用包名:", "获取用户输入"),
                ScriptCommand("confirm 确认卸载 \$INPUT ？此操作不可撤销。", "二次确认"),
                ScriptCommand("shell pm uninstall \$INPUT", "执行卸载"),
                ScriptCommand("delay 1", "等待 1 秒"),
                ScriptCommand("! shell pm list packages | grep \$INPUT", "验证卸载结果")
            )
        )

        val VARIABLE_DEMO_SCRIPT = AdbScript(
            id = "variable_demo",
            name = "变量操作演示",
            description = "演示 set / set /p / set /a / echo 等变量操作命令",
            category = "custom",
            commands = listOf(
                ScriptCommand("# 基本赋值", ""),
                ScriptCommand("set PKG=com.example.app", "设置包名变量"),
                ScriptCommand("echo 目标包名: \$PKG", "输出变量值"),
                ScriptCommand("# 交互输入", ""),
                ScriptCommand("set /p NAME=请输入应用名称:", "交互输入应用名"),
                ScriptCommand("echo 你输入的应用名是: \$NAME", "回显输入"),
                ScriptCommand("# 算术运算", ""),
                ScriptCommand("set /p COUNT=请输入重试次数:", "输入重试次数"),
                ScriptCommand("set /a REMAIN=\$COUNT-1", "计算剩余次数"),
                ScriptCommand("echo 剩余重试次数: \$REMAIN", "输出计算结果"),
                ScriptCommand("# 条件判断", ""),
                ScriptCommand("shell pm list packages | grep \$PKG", "检查应用是否存在")
            )
        )

        val PREDEFINED_SCRIPTS = listOf(
            DEVICE_INFO_SCRIPT,
            APP_CLEANUP_SCRIPT,
            VARIABLE_DEMO_SCRIPT
        )
    }
}
