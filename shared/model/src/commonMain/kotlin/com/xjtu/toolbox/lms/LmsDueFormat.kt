package com.xjtu.toolbox.lms

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 截止时间的解析与剩余时间的措辞。 */
fun LmsActivity.deadlineInstant(): Instant? {
    val raw = deadline?.takeIf { it.isNotBlank() } ?: endTime?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(stripZoneName(raw)) }.getOrNull()
}

/** 解析一条上游时间串（不挑 deadline / endTime）。 */
fun lmsInstant(raw: String): Instant? = runCatching { Instant.parse(stripZoneName(raw)) }.getOrNull()

/** `ZonedDateTime.parse` 认 `2026-09-14T00:00:00+08:00[Asia/Shanghai]` 这种带时区名的形状， ISO 的 `Instant.parse` 不认（它只认到偏移量）。 */
private fun stripZoneName(raw: String): String {
    val text = raw.trim()
    val bracket = text.indexOf('[')
    return if (bracket > 0) text.substring(0, bracket) else text
}

/** 剩余时间的措辞：不到一小时说「不到 1 小时」，够一天说「剩 N 天 M 小时」，不够一天说「剩 N 小时」。 */
fun remaining(now: Instant, deadline: Instant): String {
    val totalMinutes = (deadline - now).inWholeMinutes
    if (totalMinutes < 60) return "不到 1 小时"
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    return when {
        days <= 0 -> "剩 $hours 小时"
        hours <= 0 -> "剩 $days 天"
        else -> "剩 $days 天 $hours 小时"
    }
}
