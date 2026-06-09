package com.adbhelper.app.core.script

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
    val awaitConfirm: suspend (CompletableDeferred<Unit>) -> Unit
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
            val result = evalArithExpr(resolved)
            ctx.mergedVariables[varName] = result.toString()
            ctx.emitLine("[set /a] $varName = $result")
        } catch (e: Exception) {
            ctx.emitLine("[错误] 算术表达式求值失败: $resolved — ${e.message}")
        }
        ctx.addResult(CommandResult(command = effectiveCommand, result = null))
    }

    /** 简单整数表达式求值，支持 + - * / % 和括号 */
    companion object {
        fun evalArithExpr(expr: String): Long {
            val tokens = tokenize(expr)
            val pos = intArrayOf(0)
            val result = parseExpr(tokens, pos)
            if (pos[0] < tokens.size) {
                throw IllegalArgumentException("多余的 token: ${tokens[pos[0]]}")
            }
            return result
        }

        private data class Token(val value: String, val isOp: Boolean)

        private fun tokenize(expr: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < expr.length) {
                when {
                    expr[i].isWhitespace() -> i++
                    expr[i] in "+-*/%()" -> {
                        // 处理一元负号: 如果 - 在开头或前面是运算符/左括号
                        if (expr[i] == '-' && (tokens.isEmpty() || (tokens.last().isOp && tokens.last().value != ")"))) {
                            // 一元负号：读取数字并取反
                            i++
                            val num = StringBuilder("-")
                            while (i < expr.length && expr[i].isDigit()) {
                                num.append(expr[i])
                                i++
                            }
                            if (num.length == 1) throw IllegalArgumentException("一元负号后缺少数字")
                            tokens.add(Token(num.toString(), false))
                        } else {
                            tokens.add(Token(expr[i].toString(), true))
                            i++
                        }
                    }
                    expr[i].isDigit() -> {
                        val num = StringBuilder()
                        while (i < expr.length && expr[i].isDigit()) {
                            num.append(expr[i])
                            i++
                        }
                        tokens.add(Token(num.toString(), false))
                    }
                    else -> throw IllegalArgumentException("无法识别的字符: ${expr[i]}")
                }
            }
            return tokens
        }

        // expr = term ((+|-) term)*
        private fun parseExpr(tokens: List<Token>, pos: IntArray): Long {
            var result = parseTerm(tokens, pos)
            while (pos[0] < tokens.size && tokens[pos[0]].value in listOf("+", "-")) {
                val op = tokens[pos[0]].value
                pos[0]++
                val right = parseTerm(tokens, pos)
                result = if (op == "+") result + right else result - right
            }
            return result
        }

        // term = factor ((*|/|%) factor)*
        private fun parseTerm(tokens: List<Token>, pos: IntArray): Long {
            var result = parseFactor(tokens, pos)
            while (pos[0] < tokens.size && tokens[pos[0]].value in listOf("*", "/", "%")) {
                val op = tokens[pos[0]].value
                pos[0]++
                val right = parseFactor(tokens, pos)
                result = when (op) {
                    "*" -> result * right
                    "/" -> {
                        if (right == 0L) throw ArithmeticException("除零")
                        result / right
                    }
                    "%" -> {
                        if (right == 0L) throw ArithmeticException("除零")
                        result % right
                    }
                    else -> result
                }
            }
            return result
        }

        // factor = number | (expr)
        private fun parseFactor(tokens: List<Token>, pos: IntArray): Long {
            if (pos[0] >= tokens.size) throw IllegalArgumentException("表达式不完整")
            val token = tokens[pos[0]]
            return if (!token.isOp) {
                pos[0]++
                token.value.toLong()
            } else if (token.value == "(") {
                pos[0]++ // skip (
                val result = parseExpr(tokens, pos)
                if (pos[0] >= tokens.size || tokens[pos[0]].value != ")") {
                    throw IllegalArgumentException("缺少右括号")
                }
                pos[0]++ // skip )
                result
            } else {
                throw IllegalArgumentException("意外的 token: ${token.value}")
            }
        }
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

/** 通用变量替换工具函数 */
private fun replaceVars(text: String, variables: Map<String, String>): String {
    var result = text
    variables.forEach { (key, value) ->
        result = result.replace("\${$key}", value)
        result = result.replace("$$key", value)
    }
    return result
}
