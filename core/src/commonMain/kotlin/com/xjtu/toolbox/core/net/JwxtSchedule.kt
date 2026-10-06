package com.xjtu.toolbox.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 教务课表的一行 —— campus-api 给的是**上游原始 47 列**（字段名就是上游字段名）。
 * 这里只钉住三端真正要用的那几列，其余靠 `ignoreUnknownKeys` 忽略（上游随时加列）。
 *
 * ⚠️ 周次必须用 `ZCMC` 文本（形如 `1-3周,5-7周(单),9-12周,14-16周`），**不要用 `SKZC` 位串** ——
 * 上游位宽 16/18 不齐，已弃用。这是 campus-api 文档里点名的坑，两个端都不能踩。
 */
@Serializable
data class ScheduleRow(
    @SerialName("KCM") val courseName: String? = null,
    @SerialName("SKJS") val teacher: String? = null,
    @SerialName("JASMC") val classroom: String? = null,
    @SerialName("SKXQ") val dayOfWeek: String? = null,
    @SerialName("KSJC") val startSection: String? = null,
    @SerialName("JSJC") val endSection: String? = null,
    @SerialName("ZCMC") val weeksText: String? = null,
    @SerialName("JXBID") val classId: String? = null,
)

@Serializable
data class ScheduleData(
    val term: String? = null,
    val rows: List<ScheduleRow> = emptyList(),
    val count: Int = 0,
)

/**
 * `/api/jwxt/term-start` 的 `data`：学期号 + **第 1 周周一** + 总周数。
 * 实测 `2026-2027-1` → `startDate=2026-09-14`、`totalWeeks=18`。
 * 上游课表不含日期，所以「今天是第几周」必须从这一天起算。
 */
@Serializable
data class TermStartData(
    val term: String? = null,
    val startDate: String? = null,
    val totalWeeks: Int = 0,
)
