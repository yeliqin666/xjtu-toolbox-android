package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.formatFixed
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** `calculate` 工具：极简安全表达式求值（四则、括号、幂 `^`、一元正负），不依赖任何脚本引擎。 */
fun agentCalculate(expr: String): String {
    if (expr.isBlank()) return ToolReply.missing("expression")
    return try {
        val v = ExprEval(expr).parse()
        val s = if (v.isFinite() && v == floor(v) && abs(v) < 1e15)
            v.toLong().toString()
        else formatFixed(v, 4).trimEnd('0').trimEnd('.')
        "$expr = $s"
    } catch (e: Exception) {
        "无法计算「$expr」：${e.message ?: "表达式有误"}"
    }
}

/** 极简安全表达式求值：四则、括号、幂(^)、一元正负。不依赖任何脚本引擎。 */
private class ExprEval(private val s: String) {
    private var pos = 0
    fun parse(): Double {
        val v = expr(); skipWs()
        if (pos < s.length) throw IllegalArgumentException("多余字符")
        return v
    }
    private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
    private fun peek(): Char { skipWs(); return if (pos < s.length) s[pos] else '\u0000' }
    private fun expr(): Double {
        var v = term()
        while (true) when (peek()) {
            '+' -> { pos++; v += term() }
            '-' -> { pos++; v -= term() }
            else -> return v
        }
    }
    private fun term(): Double {
        var v = power()
        while (true) when (peek()) {
            '*' -> { pos++; v *= power() }
            '/' -> { pos++; v /= power() }
            else -> return v
        }
    }
    private fun power(): Double {
        val b = unary()
        return if (peek() == '^') { pos++; b.pow(power()) } else b
    }
    private fun unary(): Double = when (peek()) {
        '-' -> { pos++; -unary() }
        '+' -> { pos++; unary() }
        else -> atom()
    }
    private fun atom(): Double {
        if (peek() == '(') {
            pos++; val v = expr()
            if (peek() != ')') throw IllegalArgumentException("缺少 )")
            pos++; return v
        }
        skipWs()
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
        if (pos == start) throw IllegalArgumentException("非法字符")
        return s.substring(start, pos).toDouble()
    }
}
