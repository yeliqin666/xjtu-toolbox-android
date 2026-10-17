package com.xjtu.toolbox.util

import kotlin.math.abs
import kotlin.math.round

/** `"%.${digits}f".format(value)` 的跨端等价实现（HALF_UP，小数部分补零）。 */
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

/** `%02d` 的跨端等价实现。 */
fun formatTwoDigits(value: Int): String = value.toString().padStart(2, '0')
