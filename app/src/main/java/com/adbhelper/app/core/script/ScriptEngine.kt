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

    // Predefined script templates
    companion object {
        val COMPREHENSIVE_DEMO_SCRIPT = AdbScript(
            id = "comprehensive_demo",
            name = "综合命令演示",
            description = "全面展示脚本引擎所有命令：echo / set / set /p / set /a / capture / if / goto / delay / input / confirm，以及控制流、输出捕获等高级功能",
            category = "custom",
            commands = listOf(
                ScriptCommand("# ===== 综合命令演示 — 覆盖所有自定义命令 =====\n# echo、delay、input、confirm、set、set /p、set /a\n# capture、if、goto、:label、!、\$变量引用", ""),

                // ---------- 基本命令 ----------
                ScriptCommand("echo ========== 1. 基本命令 ==========", ""),
                ScriptCommand("shell getprop ro.product.model", "设备型号"),
                ScriptCommand("shell getprop ro.build.version.release", "系统版本"),

                // ---------- capture 输出捕获 ----------
                ScriptCommand("capture BRAND=shell getprop ro.product.brand", "捕获输出到变量"),
                ScriptCommand("echo 设备品牌: \$BRAND", "引用捕获的变量"),

                // ---------- if + goto 控制流 ----------
                ScriptCommand("if \$BRAND==\"samsung\" goto samsung_device", "三星设备分支"),
                ScriptCommand("echo 非三星设备，跳过三星专用设置", ""),
                ScriptCommand("goto ask_pkg", "跳转到输入环节"),

                ScriptCommand(":samsung_device", "标签：三星设备"),
                ScriptCommand("echo 检测到三星设备，启用多窗口模式", ""),
                ScriptCommand("shell settings put global multi_window_enabled 1", "启用多窗口"),
                ScriptCommand("delay 1", ""),

                // ---------- set 变量赋值 ----------
                ScriptCommand(":ask_pkg", "标签：询问包名"),
                ScriptCommand("echo ========== 2. 变量赋值与交互 ==========", ""),
                ScriptCommand("set DEF_PKG=com.android.chrome", "设置默认包名"),
                ScriptCommand("set DEF_ACTION=install", "设置默认操作"),
                ScriptCommand("echo 默认包名: \$DEF_PKG，默认操作: \$DEF_ACTION", ""),

                // ---------- set /p 交互输入 ----------
                ScriptCommand("set /p PKG=请输入包名(留空用默认):", "交互输入包名"),
                ScriptCommand("set /p ACTION=请输入操作(install/uninstall):", "交互输入操作"),

                // ---------- if 变量比较 ----------
                ScriptCommand("if \$PKG!=\"\" goto use_custom_pkg", "自定义包名"),
                ScriptCommand("set PKG=\$DEF_PKG", "使用默认包名"),
                ScriptCommand("goto check_action", ""),

                ScriptCommand(":use_custom_pkg", "标签：自定义包名"),
                ScriptCommand("echo 使用自定义包名: \$PKG", ""),

                // ---------- if + goto 根据操作跳转 ----------
                ScriptCommand(":check_action", "标签：判断操作类型"),
                ScriptCommand("if \$ACTION==\"uninstall\" goto do_uninstall", ""),
                ScriptCommand("if \$ACTION==\"install\" goto do_install", ""),
                ScriptCommand("echo 未知操作: \$ACTION，使用默认操作", ""),
                ScriptCommand("set ACTION=\$DEF_ACTION", ""),
                ScriptCommand("if \$ACTION==\"uninstall\" goto do_uninstall", ""),
                ScriptCommand("echo 执行默认操作: \$ACTION", ""),
                ScriptCommand("goto end", ""),

                // ---------- input + confirm ----------
                ScriptCommand(":do_uninstall", "标签：卸载流程"),
                ScriptCommand("echo ========== 3. 交互确认 ==========", ""),
                ScriptCommand("input 请输入卸载原因(可选，直接回车跳过):", "输入反馈"),
                ScriptCommand("if \$INPUT!=\"\" echo 卸载原因已记录: \$INPUT", ""),
                ScriptCommand("confirm 确认卸载 \$PKG ？此操作不可撤销。", "确认卸载"),
                ScriptCommand("! shell pm uninstall \$PKG", "执行卸载(失败则退出)"),
                ScriptCommand("delay 2", "等待 2 秒"),
                ScriptCommand("echo 卸载完成，验证结果...", ""),

                // ---------- condition 条件执行 ----------
                ScriptCommand("shell pm list packages | grep \$PKG", "验证卸载(应失败)"),

                // ---------- set /a 算术运算 ----------
                ScriptCommand("echo ========== 4. 算术运算 ==========", ""),
                ScriptCommand("set /a SCORE=85+15", "算术: 加法"),
                ScriptCommand("echo 评分: \$SCORE", ""),
                ScriptCommand("set COUNT=3", ""),
                ScriptCommand("set /a REMAIN=\$COUNT-1", "算术: 减法"),
                ScriptCommand("set /a DOUBLE=\$COUNT*2", "算术: 乘法"),
                ScriptCommand("echo 剩余: \$REMAIN，双倍: \$DOUBLE", ""),
                ScriptCommand("goto end", ""),

                // ---------- 安装流程 ----------
                ScriptCommand(":do_install", "标签：安装流程"),
                ScriptCommand("echo ========== 5. 安装流程 ==========", ""),

                // ---------- condition 文件存在检查 ----------
                ScriptCommand("input 请输入APK路径(默认 /sdcard/app.apk):", ""),
                ScriptCommand("if \$INPUT!=\"\" goto set_apk_path", ""),
                ScriptCommand("set APK_PATH=/sdcard/app.apk", ""),
                ScriptCommand("goto do_install_exec", ""),
                ScriptCommand(":set_apk_path", ""),
                ScriptCommand("set APK_PATH=\$INPUT", ""),

                ScriptCommand(":do_install_exec", ""),
                ScriptCommand("echo 安装路径: \$APK_PATH", ""),
                ScriptCommand("shell pm install \$APK_PATH", "安装APK"),
                ScriptCommand("delay 1", ""),

                // ---------- 结束 ----------
                ScriptCommand(":end", "标签：结束"),
                ScriptCommand("echo ========== 演示结束 ==========", ""),
                ScriptCommand("echo 本脚本演示了所有命令的用法。", ""),

                // 宏块条件示例（通过编辑命令卡片的 condition 字段使用）
                ScriptCommand("# 提示: 以下命令支持在「条件」字段设置条件\n# exists:路径 — 文件存在才执行\n# not_exists:路径 — 文件不存在才执行\n# package:包名 — 应用存在才执行", "")
            )
        )

        val PREDEFINED_SCRIPTS = listOf(
            COMPREHENSIVE_DEMO_SCRIPT
        )
    }
}
