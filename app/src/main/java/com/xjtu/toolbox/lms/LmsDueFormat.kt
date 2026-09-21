package com.xjtu.toolbox.lms

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 截止时间的解析与剩余时间的措辞。通知（LmsDeadlineWorker）和日程页「接下来」
 * （plan2 §5）共用这一份，别让两边各写一套、口径不一致。
 *
 * 上游给的是带时区的 UTC 串。列表接口带 `deadline`，优先用它——它才是老师设的截止，
 * 实测 4.6% 与 `endTime` 不同，且都是 `endTime` 比它早；缺失才退回 `endTime`，认不出就当没有截止时间。
 */
fun LmsActivity.deadlineInstant(): Instant? {
    val raw = deadline?.takeIf { it.isNotBlank() } ?: endTime?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
}

/**
 * 剩余时间的措辞：不到一小时说「不到 1 小时」，够一天说「剩 N 天 M 小时」，
 * 不够一天说「剩 N 小时」。
 */
fun remaining(now: Instant, deadline: Instant): String {
    val totalMinutes = Duration.between(now, deadline).toMinutes()
    if (totalMinutes < 60) return "不到 1 小时"
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    return when {
        days <= 0 -> "剩 $hours 小时"
        hours <= 0 -> "剩 $days 天"
        else -> "剩 $days 天 $hours 小时"
    }
}
