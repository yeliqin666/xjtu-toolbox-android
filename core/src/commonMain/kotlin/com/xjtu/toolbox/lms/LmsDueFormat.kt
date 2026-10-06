package com.xjtu.toolbox.lms

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 截止时间的解析与剩余时间的措辞。通知（LmsDeadlineWorker）和日程页「接下来」
 * （plan2 §5）共用这一份，别让两边各写一套、口径不一致。
 *
 * 上游给的是带时区的 UTC 串。列表接口带 `deadline`，优先用它——它才是老师设的截止，
 * 实测 4.6% 与 `endTime` 不同，且都是 `endTime` 比它早；缺失才退回 `endTime`，认不出就当没有截止时间。
 *
 * 为什么在 :core：`LmsActivity`（模型）本来就在这儿，格式化它却留在 :app 里，正是「同一个域
 * 被切成两半」的例子。搬进来时把 `java.time.Instant/Duration/ZonedDateTime` 换成了
 * `kotlin.time.Instant` —— `toString()` 仍是 ISO-8601（缓存里的字符串形状不变），
 * 而 Web 端也能算了（通知页的「剩 N 天 M 小时」将来在 web 上同样要显示）。
 */
fun LmsActivity.deadlineInstant(): Instant? {
    val raw = deadline?.takeIf { it.isNotBlank() } ?: endTime?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(stripZoneName(raw)) }.getOrNull()
}

/**
 * `ZonedDateTime.parse` 认 `2026-09-14T00:00:00+08:00[Asia/Shanghai]` 这种带时区名的形状，
 * ISO 的 `Instant.parse` 不认（它只认到偏移量）。上游两种都给过，所以这里把尾部的
 * `[Zone]` 去掉再解析 —— 偏移量还在，时刻不变。
 */
private fun stripZoneName(raw: String): String {
    val text = raw.trim()
    val bracket = text.indexOf('[')
    return if (bracket > 0) text.substring(0, bracket) else text
}

/**
 * 剩余时间的措辞：不到一小时说「不到 1 小时」，够一天说「剩 N 天 M 小时」，
 * 不够一天说「剩 N 小时」。
 */
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
