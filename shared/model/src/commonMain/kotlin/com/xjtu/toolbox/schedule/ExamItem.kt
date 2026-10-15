package com.xjtu.toolbox.schedule

import kotlinx.serialization.Serializable

/** 一条考试安排（教务 `studentWdksapApp` 的 `wdksap.do`）。 */
@Serializable
data class ExamItem(
    val courseName: String = "",
    val courseCode: String = "",
    val examDate: String = "",
    val examTime: String = "",
    val location: String = "",
    val seatNumber: String = "",
)
