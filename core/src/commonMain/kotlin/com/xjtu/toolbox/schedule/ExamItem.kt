package com.xjtu.toolbox.schedule

import kotlinx.serialization.Serializable

/**
 * 一条考试安排（教务 `studentWdksapApp` 的 `wdksap.do`）。
 *
 * 从 `:app` 的 `schedule/ScheduleApi.kt` 原样切出来（注解、字段名、默认值一行未改）：
 * 取数搬到了 `:data`（`JwxtScheduleApi.examSchedule`），模型与包名留在共享层 ——
 * 它同时是**落盘缓存**的形状（`ScheduleCache` 的 `exams_<学期>` 键里存的就是这一串 JSON），
 * 换个模块或改个字段名等于让老缓存读不出来。
 */
@Serializable
data class ExamItem(
    val courseName: String = "",
    val courseCode: String = "",
    val examDate: String = "",
    val examTime: String = "",
    val location: String = "",
    val seatNumber: String = "",
)
