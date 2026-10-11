package com.xjtu.toolbox.util

import kotlin.math.abs
import kotlin.math.round

/**
 * `"%.${digits}f".format(value)` 的**跨端**等价实现（HALF_UP，小数部分补零）。
 *
 * 为什么要它：`String.format` 是 `kotlin.text` 里 **JVM 专属**的扩展，而且在 JVM 上是默认导入 ——
 * 基于 import 的判据看不见它。成绩屏（`:core`，三个目标里含 wasm）从 `:app` 搬过来时带着 9 处
 * `"%.1f".format(...)` 这类写法，在 JVM 上编得过、到 wasm 就断（实测 `:core:compileKotlinWasmJs`）。
 *
 * `:core` 里已有同一条替换的先例：`score/ScoreReportModels.kt` 的 `formatTwoDecimals` /
 * `formatOneDecimal` —— 那两处只覆盖 1、2 位小数，而成绩屏的小数位数由「分类列绩点」那条设置给
 *（2/3/4 都可能），所以这里要一个位数可任选的那一份。
 *
 * 退化输入与 Java 一致：非有限值（NaN / ±Infinity）原样给出（`value.toString()`）。
 * 负数按绝对值四舍五入后再补负号。
 */
fun formatFixed(value: Double, digits: Int): String {
    if (!value.isFinite()) return value.toString()
    val safeDigits = digits.coerceIn(0, 8)
    var factor = 1L
    repeat(safeDigits) { factor *= 10L }
    val negative = value < 0
    val scaled = round(abs(value) * factor).toLong()
    val whole = scaled / factor
    if (safeDigits == 0) return if (negative) "-$whole" else "$whole"
    val frac = (scaled % factor).toString().padStart(safeDigits, '0')
    return if (negative) "-$whole.$frac" else "$whole.$frac"
}

/** [formatFixed] 的 `Float` 重载（屏上的回调收的就是 `Float`）。 */
fun formatFixed(value: Float, digits: Int): String = formatFixed(value.toDouble(), digits)
