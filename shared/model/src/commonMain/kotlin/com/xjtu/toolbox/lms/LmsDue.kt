package com.xjtu.toolbox.lms

/**
 * 一条「没提交、有截止时间」的作业，日程页「今日」栏读这份缓存。
 *
 * @param exam 学堂的「测试」：来自首页待办接口，`activityId` 是测试 id，只进待办，不进日程和提醒。
 */
@kotlinx.serialization.Serializable
data class LmsDue(
    val courseId: Int = 0,
    val courseName: String = "",
    val activityId: Int = 0,
    val title: String = "",
    val deadline: String = "",
    val submitted: Boolean = false,
    val fetchedAt: Long = 0L,
    val exam: Boolean = false,
)
