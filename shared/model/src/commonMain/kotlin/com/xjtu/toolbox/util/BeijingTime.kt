package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** 学校所在时区的 IANA id。java.time 那一侧用 `ZoneId.of(CAMPUS_TIME_ZONE_ID)`。 */
const val CAMPUS_TIME_ZONE_ID = "Asia/Shanghai"

/**
 * 学校所在时区。业务日期（「今天」的课、签到、流水窗口、空教室、场馆）都按它算，
 * 不跟运行机器走 —— `:server` 常部署在 UTC 的 Linux 上，出国的桌面端用户也一样。
 * wasmJs 没带时区库时 `TimeZone.of` 认不出 IANA id，退回固定东八区（1991 年后无夏令时，结果相同）。
 */
val CampusTimeZone: TimeZone by lazy {
    runCatching { TimeZone.of(CAMPUS_TIME_ZONE_ID) }.getOrElse { UtcOffset(hours = 8).asTimeZone() }
}

/** 学校时区的「此刻」。 */
fun nowOnCampus(clock: Clock = Clock.System): LocalDateTime = clock.now().toLocalDateTime(CampusTimeZone)

/** 学校时区的「今天」。业务日期一律用它；[todayInSystemZone] 只留给纯显示。 */
fun todayOnCampus(clock: Clock = Clock.System): LocalDate = nowOnCampus(clock).date

/** 校园上游给的 `"2026-10-07 20:00:02"`（东八区、不带偏移量）→ epoch 毫秒；认不出来返回 0。 */
fun beijingEpochMs(text: String?): Long {
    val t = text?.take(19)?.takeIf { it.length >= 19 } ?: return 0L
    return runCatching {
        LocalDateTime.parse(t.replace(' ', 'T')).toInstant(UtcOffset(hours = 8, minutes = 0)).toEpochMilliseconds()
    }.getOrDefault(0L)
}
