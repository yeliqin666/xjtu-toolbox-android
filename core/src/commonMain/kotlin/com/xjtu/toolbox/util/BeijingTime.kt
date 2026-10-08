package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/**
 * 校园上游给的 `"2026-10-07 20:00:02"`（东八区、不带偏移量）→ epoch 毫秒；认不出来返回 0。
 *
 * **为什么是固定 +08:00，而不是 `TimeZone.of("Asia/Shanghai")`**：Web（Wasm）上
 * kotlinx-datetime 的时区库要宿主提供 `@js-joda/timezone`，页面里没有 ⇒ `TimeZone.of(…)`
 * 抛「js-joda timezone database is not available」。这个异常被调用方的 `runCatching`
 * 吞掉之后，**每条时间都变 0**；而 0 又正好落进 `InboxRules` 的保留期判据
 * （`now - time < KEEP_MS`）里被当成「很久以前」⇒ 消息整批不显示、待办 `time=0` 也不可见。
 * App 端看不出来（JVM 自带 tzdb），只在 Web 端暴露 —— 实测踩到过一次。
 *
 * 中国大陆 1991 年之后不再有夏令时 ⇒ 对这些时间戳，固定 +08:00 与 `Asia/Shanghai`
 * **逐字同值**（:app 的 `SchoolInbox` 用的就是 `ZoneId.of("Asia/Shanghai")`）。
 * `LocalDateTime.parse` 是纯 Kotlin、不碰宿主，三端行为一致。
 */
internal fun beijingEpochMs(text: String?): Long {
    val t = text?.take(19)?.takeIf { it.length >= 19 } ?: return 0L
    return runCatching {
        LocalDateTime.parse(t.replace(' ', 'T')).toInstant(UtcOffset(hours = 8, minutes = 0)).toEpochMilliseconds()
    }.getOrDefault(0L)
}
