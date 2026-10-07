package com.xjtu.toolbox.score

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/**
 * 成绩报表的模型与端口 —— 从 `:app/score/ScoreReportApi.kt` / `ScoreReportScreen.kt` 里切出来的那段纯的。
 *
 * `ReportedGrade` 就是教务帆软报表（FineReport）那一页表格的一行：课程名 / 学分 / 成绩文本 / 绩点 / 学期。
 * `@Serializable` 保持原样 —— `:app` 的 `DataCache` 按这个形状落盘，Web 端也用同一套键值缓存，
 * 所以它必须能被 kotlinx.serialization 序列化（两边同一份编码）。
 */
@Serializable
data class ReportedGrade(
    val courseName: String = "",
    val coursePoint: Double = 0.0,
    val score: String = "",      // 可能是数字或等级（如 "优秀"）
    val gpa: Double? = null,
    val term: String = "",       // 学期代码 "2024-2025-1"
)

/**
 * 成绩报表的**取数端口**。屏幕只认这个；两端各自注入实现：
 *  - `:app`  = `ScoreReportApi`（走 `jwxt` 帆软报表接口，okhttp，行为与搬迁前一致）；
 *  - Web     = [com.xjtu.toolbox.core.net.CampusGradesApi]（campus-api 的 `/api/jwxt/grades`，同源反代）。
 *
 * ⚠️ 两端**上游不是同一个接口**：`:app` 解析的是「成绩报表」HTML（帆软 renderlet），
 * campus-api 给的是「教务精确成绩」（`cjcx/xscjcx`）。两者的字段已经对齐到本模型
 * （学期代码同口径、成绩取 `level` 优先再退 `total`、绩点用上游 `XFJD`），但**条数与顺序可能不同** ——
 * 同一门课在本模型里应当完全一致，多出来的差异只可能来自「报表里有而精确成绩里没有」的课（反之亦然）。
 */
interface ScoreReportSource {
    suspend fun grades(): List<ReportedGrade>
}

/**
 * 成绩的 **SWR 缓存端口**（先给旧值秒显、后台再刷）。
 *
 * 为什么是端口而不是直接用 `:core` 的 `keyValueStore`：`:app` 侧的原实现是
 * `DataCache`（按账号隔离的 JSON 文件缓存，带 TTL 与安装包变更清理），Web 侧没有账号也没有文件系统。
 * 保留这个缝，App 的行为才能逐字不变（迁移前就是这么缓存的）。
 *
 * 实现方自己决定 key 与 TTL —— 屏幕只负责「读得到就先显示、拉到了就写回去」。
 */
interface ScoreReportCache {
    /** 读不到就是 null（含解析失败、过期）。 */
    fun read(): List<ReportedGrade>?

    fun write(grades: List<ReportedGrade>)
}

/**
 * `"%.2f".format(value)` 的跨端等价实现。
 *
 * `String.format` 是 `kotlin.text` 里的 **JVM 专属**扩展，而且在 JVM 上是默认导入 ——
 * 任何基于 import 的判据都看不见它（交接文档 §4.2 点名过这一类）。所以这里手写：
 * 四舍五入到分位、小数部分补齐两位；非有限值（NaN/Infinity）原样给出，
 * 与原实现在退化输入下的表现一致。
 */
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
