package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.formatFixed
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * `calculate` 工具：极简安全表达式求值（四则、括号、幂 `^`、一元正负），不依赖任何脚本引擎。
 *
 * 从 `:app` 的 `AgentToolRegistry` 逐字搬来，**一行平台代码都没有**。搬迁时替换的写法三处
 * （每条在仓库里都有先例）：
 *  - `"%.4f".format(v)` -> [formatFixed]（`String.format` 是 JVM 专属，而且在 JVM 上是默认
 *    导入 => import 判据看不见它；`util/NumberText.kt` 就是为这条替换写的）；
 *  - `Math.floor` / `Math.pow` / `Math.abs` -> `kotlin.math` 的同名函数。
 *
 * 与 `%.4f` 只剩一条差别：[formatFixed] 内部按 `abs(v) * 10^4` 取整，绝对值超过约 9.2e14
 * 会溢出 Long。真正的整数已被 `v == floor(v)` 那一支接走，剩下的得是「15 位以上有效数字、
 * 又不是整数」的值 —— 表达式里写得出来也无人在意，测试不覆盖这一档。
 */
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
