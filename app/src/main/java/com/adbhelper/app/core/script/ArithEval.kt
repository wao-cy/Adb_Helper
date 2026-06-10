package com.adbhelper.app.core.script

/** 简单整数表达式求值，支持 + - * / % 和括号 */
object ArithEvaluator {

    fun eval(expr: String): Long {
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
                    if (expr[i] == '-' && (tokens.isEmpty() || (tokens.last().isOp && tokens.last().value != ")"))) {
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
            pos[0]++
            val result = parseExpr(tokens, pos)
            if (pos[0] >= tokens.size || tokens[pos[0]].value != ")") {
                throw IllegalArgumentException("缺少右括号")
            }
            pos[0]++
            result
        } else {
            throw IllegalArgumentException("意外的 token: ${token.value}")
        }
    }
}
