package com.xjtu.toolbox.lms

/**
 * 一条「没提交、有截止时间」的作业，日程页「今日」栏的「接下来」（plan2 §5）读这份缓存。
 *
 * @param deadline ISO-8601，取自 [LmsActivity.deadlineInstant]（优先 `deadline`，
 *   缺失退回 `endTime`），与截止提醒通知同一口径。
 * @param submitted 详情里的 `user_submit_count > 0`（列表不给，见 `LmsDueCollector.collect`）。
 *
 * 从 `:app` 的 `schedule/…` 一侧搬进 `:core`（同包同名，`:app` 的调用点一行未改）：
 * 日程页要把它摆进「接下来」那一列，而屏已经在 `:core`。落盘那半（`LmsDueStore`）仍在 `:app`
 * —— 它读的是 `DataCache` 里那份 JSON（键 `lms_due_items`），走宿主槽位 `ScheduleHost.dueHomeworks`。
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
)
