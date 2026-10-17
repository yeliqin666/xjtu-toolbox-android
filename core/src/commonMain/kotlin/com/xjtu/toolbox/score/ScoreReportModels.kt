package com.xjtu.toolbox.score

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/** 成绩报表的模型与端口。 */
@Serializable
data class ReportedGrade(
    val courseName: String = "",
    val coursePoint: Double = 0.0,
    val score: String = "",      // 可能是数字或等级（如 "优秀"）
    val gpa: Double? = null,
    val term: String = "",       // 学期代码 "2024-2025-1"
)

/** 成绩报表的取数端口。 */
interface ScoreReportSource {
    suspend fun grades(): List<ReportedGrade>
}

/** 成绩的 SWR 缓存端口（先给旧值秒显、后台再刷）。 */
interface ScoreReportCache {
    /** 读不到就是 null（含解析失败、过期）。 */
    fun read(): List<ReportedGrade>?

    fun write(grades: List<ReportedGrade>)
}

/** `"%.2f".format(value)` 的跨端等价实现。 */
fun formatTwoDecimals(value: Double): String = formatFixed(value, 2)

/** `"%.1f".format(value)` 的跨端等价实现，见 [formatTwoDecimals]。 */
fun formatOneDecimal(value: Double): String = formatFixed(value, 1)

private fun formatFixed(value: Double, digits: Int): String {
    if (!value.isFinite()) return value.toString()
    val factor = when (digits) {
        1 -> 10L
        else -> 100L
    }
    val negative = value < 0
    val scaled = round(abs(value) * factor).toLong()
    val whole = scaled / factor
    val frac = (scaled % factor).toString().padStart(digits, '0')
    return if (negative) "-$whole.$frac" else "$whole.$frac"
}
