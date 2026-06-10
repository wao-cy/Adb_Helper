package com.adbhelper.app.core.script

import com.adbhelper.app.core.shell.ShellResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * 脚本特殊命令执行上下文，由 executeScript 在循环中构建。
 */
class SpecialCommandContext(
    val index: Int,
    val totalCommands: Int,
    val outputLines: MutableList<String>,
    val mergedVariables: MutableMap<String, String>,
    val updateRunning: (Int, Int, String, List<String>) -> Unit,
    val emitLine: suspend (String) -> Unit,
    val setState: (ScriptExecutionState) -> Unit,
    val addResult: (CommandResult) -> Unit,
    /** 等待用户输入，返回输入值 */
    val awaitInput: suspend (CompletableDeferred<String>) -> String,
    /** 等待用户确认 */
    val awaitConfirm: suspend (CompletableDeferred<Unit>) -> Unit,
    /** 跳转到指定标签，返回标签是否存在 */
    val jumpTo: (String) -> Boolean,
    /** 执行 shell 命令（用于 capture / if 内部调用） */
    val executeShell: suspend (String, String?) -> ShellResult
)

/**
 * 特殊命令处理器接口。
 * 每个实现负责一种特殊命令（delay / input / confirm）。
 */
interface SpecialCommandHandler {
    /** 命令前缀（如 "delay "），用于匹配 */
    val prefix: String

    /** 处理匹配到的特殊命令 */
    suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext)
}

/** delay N — 延时 N 秒 */
class DelayCommandHandler : SpecialCommandHandler {
    override val prefix = "delay "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val seconds = effectiveCommand.removePrefix(prefix).trim().toLongOrNull() ?: 1
        val desc = scriptCommand.description.ifBlank { "延时 ${seconds}秒" }
        ctx.updateRunning(ctx.index, ctx.totalCommands, desc, ctx.outputLines)
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
        delay(seconds * 1000)
    }
}

/** input <prompt> — 等待用户输入，结果存入 INPUT 变量 */
class InputCommandHandler : SpecialCommandHandler {
    override val prefix = "input "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val prompt = effectiveCommand.removePrefix(prefix).trim()
        ctx.updateRunning(ctx.index, ctx.totalCommands, "等待输入: $prompt", ctx.outputLines)
        val deferred = CompletableDeferred<String>()
        ctx.setState(ScriptExecutionState.NeedInput(prompt))
        val value = ctx.awaitInput(deferred)
        ctx.mergedVariables["INPUT"] = value
        ctx.emitLine("[输入] $prompt → $value")
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** confirm <prompt> — 等待用户确认 */
class ConfirmCommandHandler : SpecialCommandHandler {
    override val prefix = "confirm "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val prompt = effectiveCommand.removePrefix(prefix).trim()
        ctx.updateRunning(ctx.index, ctx.totalCommands, "等待确认: $prompt", ctx.outputLines)
        val deferred = CompletableDeferred<Unit>()
        ctx.setState(ScriptExecutionState.NeedConfirm(prompt))
        ctx.awaitConfirm(deferred)
        ctx.emitLine("[确认] $prompt → 继续")
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** set VAR=value — 变量赋值 */
class SetCommandHandler : SpecialCommandHandler {
    override val prefix = "set "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val body = effectiveCommand.removePrefix(prefix).trim()
        val eqIndex = body.indexOf('=')
        if (eqIndex < 0) {
            ctx.emitLine("[错误] set 语法错误，应为 set VAR=value")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, error = "set 语法错误"))
            return
        }
        val key = body.substring(0, eqIndex).trim()
        val rawValue = body.substring(eqIndex + 1)
        // 替换值中的变量引用（支持间接引用）
        val value = replaceVars(rawValue, ctx.mergedVariables)
        ctx.mergedVariables[key] = value
        ctx.emitLine("[set] $key=$value")
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** set /p VAR=提示文本 — 交互输入到指定变量 */
class SetPromptCommandHandler : SpecialCommandHandler {
    override val prefix = "set /p "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val body = effectiveCommand.removePrefix(prefix).trim()
        val eqIndex = body.indexOf('=')
        if (eqIndex < 0) {
            ctx.emitLine("[错误] set /p 语法错误，应为 set /p VAR=提示文本")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, error = "set /p 语法错误"))
            return
        }
        val varName = body.substring(0, eqIndex).trim()
        val prompt = body.substring(eqIndex + 1).trim()
        ctx.updateRunning(ctx.index, ctx.totalCommands, "等待输入: $prompt → \$$varName", ctx.outputLines)
        val deferred = CompletableDeferred<String>()
        ctx.setState(ScriptExecutionState.NeedInput(prompt))
        val value = ctx.awaitInput(deferred)
        ctx.mergedVariables[varName] = value
        ctx.emitLine("[set /p] \$$varName = $value")
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** set /a VAR=表达式 — 整数算术运算 */
class SetArithCommandHandler : SpecialCommandHandler {
    override val prefix = "set /a "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val body = effectiveCommand.removePrefix(prefix).trim()
        val eqIndex = body.indexOf('=')
        if (eqIndex < 0) {
            ctx.emitLine("[错误] set /a 语法错误，应为 set /a VAR=表达式")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, error = "set /a 语法错误"))
            return
        }
        val varName = body.substring(0, eqIndex).trim()
        val expr = body.substring(eqIndex + 1).trim()
        val resolved = replaceVars(expr, ctx.mergedVariables)
        try {
            val result = ArithEvaluator.eval(resolved)
            ctx.mergedVariables[varName] = result.toString()
            ctx.emitLine("[set /a] $varName = $result")
        } catch (e: Exception) {
            ctx.emitLine("[错误] 算术表达式求值失败: $resolved — ${e.message}")
        }
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** echo 文本 — 输出文本（支持变量替换） */
class EchoCommandHandler : SpecialCommandHandler {
    override val prefix = "echo "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val text = effectiveCommand.removePrefix(prefix)
        val resolved = replaceVars(text, ctx.mergedVariables)
        ctx.emitLine(resolved)
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** :label_name — 标签标记（无操作） */
class LabelCommandHandler : SpecialCommandHandler {
    override val prefix = ":"

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val label = effectiveCommand.removePrefix(":").trim()
        if (label.isNotBlank() && !label.contains(" ")) {
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, skipped = true))
        }
        // 不符合标签格式（含空格或空）的 :xxx 交由常规流程
    }
}

/** goto label_name — 跳转到指定标签 */
class GotoCommandHandler : SpecialCommandHandler {
    override val prefix = "goto "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val label = effectiveCommand.removePrefix(prefix).trim()
        val found = ctx.jumpTo(label)
        ctx.emitLine(if (found) "[goto] → $label" else "[goto] 标签不存在: $label")
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** capture VAR=shell command — 执行 shell 命令，输出存入变量 */
class CaptureCommandHandler : SpecialCommandHandler {
    override val prefix = "capture "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        val body = effectiveCommand.removePrefix(prefix).trim()
        val eqIndex = body.indexOf('=')
        if (eqIndex < 0) {
            ctx.emitLine("[错误] capture 语法错误，应为 capture VAR=shell command")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, error = "capture 语法错误"))
            return
        }
        val varName = body.substring(0, eqIndex).trim()
        if (varName.isEmpty()) {
            ctx.emitLine("[错误] capture 变量名为空")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, error = "capture 变量名为空"))
            return
        }
        val shellCmd = body.substring(eqIndex + 1).trim()
        var effectiveShell = shellCmd
        if (effectiveShell.startsWith("adb ", ignoreCase = true)) {
            effectiveShell = effectiveShell.removePrefix("adb ")
        }
        ctx.emitLine("[capture] $varName = $effectiveShell")
        try {
            val result = ctx.executeShell(effectiveShell, null)
            val output = result.output.trim()
            ctx.mergedVariables[varName] = output
            ctx.emitLine("[capture] $varName = $output")
        } catch (e: Exception) {
            ctx.emitLine("[capture] 执行失败: ${e.message}")
            ctx.mergedVariables[varName] = ""
        }
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }
}

/** if condition action — 条件判断 */
class IfCommandHandler : SpecialCommandHandler {
    override val prefix = "if "

    override suspend fun handle(effectiveCommand: String, scriptCommand: ScriptCommand, ctx: SpecialCommandContext) {
        // 使用原始命令文本以保留 $VAR 语法（engine 的 replaceVariables 已替换掉 $VAR）
        var raw = scriptCommand.command.trim()
        if (raw.startsWith("! ")) raw = raw.removePrefix("! ")
        if (raw.startsWith("adb ", ignoreCase = true)) raw = raw.removePrefix("adb ")
        val body = raw.removePrefix(prefix).trim()

        // 解析条件
        val (condition, action) = parseIfCondition(body)
        if (condition == null || action == null) {
            ctx.emitLine("[错误] if 语法错误，支持的条件: \$VAR==\"value\" / \$VAR!=\"value\" / defined \$VAR / not defined \$VAR / exists:path / not_exists:path")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, error = "if 语法错误"))
            return
        }

        // 评估条件（变量替换后）
        val conditionResult: Boolean = if (condition.startsWith("exists:") || condition.startsWith("not_exists:")) {
            // 路径条件需要执行 shell 检查
            val isExists = condition.startsWith("exists:")
            val path = condition.removePrefix(if (isExists) "exists:" else "not_exists:")
            val resolvedPath = replaceVars(path, ctx.mergedVariables)
            val shellResult = ctx.executeShell("shell ls $resolvedPath 2>/dev/null && echo true || echo false", null)
            val pathExists = shellResult.output.trim() == "true"
            ctx.emitLine("[if] $condition → ${if (pathExists == isExists) "true" else "false"}")
            pathExists == isExists
        } else {
            val result = evaluateIfCondition(condition, ctx.mergedVariables)
            ctx.emitLine("[if] $condition → ${if (result) "true" else "false"}")
            result
        }

        if (conditionResult) {
            // 条件成立：通过 handler 注册表执行 action
            val resolvedAction = replaceVars(action, ctx.mergedVariables)
            val handler = listOf(
                GotoCommandHandler(),
                EchoCommandHandler(),
                SetCommandHandler(),
                SetPromptCommandHandler(),
                SetArithCommandHandler(),
                CaptureCommandHandler()
            ).find { resolvedAction.startsWith(it.prefix, ignoreCase = true) }

            if (handler != null) {
                handler.handle(resolvedAction, scriptCommand.copy(command = resolvedAction), ctx)
            } else {
                ctx.emitLine("[if] 不支持的 action: $action")
            }
        } else {
            ctx.emitLine("[if] 条件不满足，跳过: $action")
            ctx.addResult(CommandResult(command = effectiveCommand, result = null, skipped = true))
        }
    }

    /**
     * 解析 if 条件行，返回 (条件表达式, 动作命令)。
     * 若解析失败返回 (null, null)。
     */
    private data class IfParseResult(val condition: String?, val action: String?)

    private fun parseIfCondition(body: String): IfParseResult {
        // 处理 defined / not defined
        val definedMatch = Regex("""^(not\s+)?defined\s+\$?(\w+)\s+(.+)$""").find(body)
        if (definedMatch != null) {
            val isNot = definedMatch.groupValues[1].isNotBlank()
            val varName = definedMatch.groupValues[2]
            val action = definedMatch.groupValues[3]
            return IfParseResult("defined:\$$varName:${if (isNot) "false" else "true"}", action)
        }

        // 处理 $VAR=="value" / $VAR!="value"
        val eqMatch = Regex("""^\$(\w+)\s*(==|!=)\s*"([^"]*)"\s+(.+)$""").find(body)
        if (eqMatch != null) {
            val varName = eqMatch.groupValues[1]
            val op = eqMatch.groupValues[2]
            val value = eqMatch.groupValues[3]
            val action = eqMatch.groupValues[4]
            return IfParseResult("str:$op:\$$varName:$value", action)
        }

        // 处理 exists:路径 / not_exists:路径
        val pathMatch = Regex("""^(not_exists|exists):(\S+)\s+(.+)$""").find(body)
        if (pathMatch != null) {
            val condType = pathMatch.groupValues[1]
            val path = pathMatch.groupValues[2]
            val action = pathMatch.groupValues[3]
            return IfParseResult("$condType:$path", action)
        }

        return IfParseResult(null, null)
    }

    private fun evaluateIfCondition(condition: String, variables: Map<String, String>): Boolean {
        return when {
            condition.startsWith("defined:") -> {
                val parts = condition.removePrefix("defined:").split(":", limit = 2)
                val varValue = variables[parts[0].removePrefix("$")]
                val expected = parts.getOrElse(1) { "true" }.toBoolean()
                val isDefined = !varValue.isNullOrBlank()
                isDefined == expected
            }
            condition.startsWith("str:") -> {
                val rest = condition.removePrefix("str:")
                val op = rest.take(2)  // == or !=
                val rest2 = rest.drop(2).removePrefix(":")  // 去掉 op 后的分隔冒号
                val parts = rest2.split(":", limit = 2)
                val varName = parts[0].removePrefix("$")
                val varValue = variables[varName] ?: ""
                val expected = parts.getOrElse(1) { "" }
                if (op == "==") varValue == expected else varValue != expected
            }
            condition.startsWith("exists:") -> true  // 由调用方评估路径条件
            condition.startsWith("not_exists:") -> false
            else -> true
        }
    }

    private fun replaceVars(text: String, variables: Map<String, String>): String {
        var result = text
        variables.forEach { (key, value) ->
            result = result.replace("\${$key}", value)
            result = result.replace("$$key", value)
        }
        return result
    }
}

/** 通用变量替换工具函数 */
private fun replaceVars(text: String, variables: Map<String, String>): String {
    var result = text
    variables.forEach { (key, value) ->
        result = result.replace("\${$key}", value)
        result = result.replace("$$key", value)
    }
    return result
}
