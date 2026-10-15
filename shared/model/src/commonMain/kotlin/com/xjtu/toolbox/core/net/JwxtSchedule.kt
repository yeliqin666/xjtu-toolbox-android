package com.xjtu.toolbox.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 教务课表的一行 —— campus-api 给的是上游原始 47 列（字段名就是上游字段名）。 */
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

/** `/api/jwxt/term-start` 的 `data`：学期号 + 第 1 周周一 + 总周数。 */
@Serializable
data class TermStartData(
    val term: String? = null,
    val startDate: String? = null,
    val totalWeeks: Int = 0,
)
