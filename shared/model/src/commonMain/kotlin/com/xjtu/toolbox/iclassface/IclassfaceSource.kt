package com.xjtu.toolbox.iclassface

import kotlinx.datetime.LocalDate

/** 一条签到/刷卡记录。 */
data class CheckinRecord(
    val name: String,
    val studentNo: String,
    val time: String,       // "2026-06-04 14:14:37"
    val location: String,   // "中3-2306"
    val type: String        // "电子班牌刷卡" / "电子班牌人脸识别"
)

/** 人脸识别签到（`iclassface.xjtu.edu.cn`）的取数端口。 */
interface IclassfaceSource {

    /** 查询指定日期的签到记录。 */
    suspend fun fetchRecords(date: LocalDate): List<CheckinRecord>
}
