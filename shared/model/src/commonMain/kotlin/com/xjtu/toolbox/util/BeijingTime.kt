package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

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

/** 学校时区的「今天」。 */
fun todayOnCampus(clock: Clock = Clock.System): LocalDate = nowOnCampus(clock).date

/** 校园上游给的 `"2026-10-07 20:00:02"`（东八区、不带偏移量）→ epoch 毫秒；认不出来返回 0。 */
fun beijingEpochMs(text: String?): Long {
    val t = text?.take(19)?.takeIf { it.length >= 19 } ?: return 0L
    return runCatching {
        LocalDateTime.parse(t.replace(' ', 'T')).toInstant(UtcOffset(hours = 8, minutes = 0)).toEpochMilliseconds()
    }.getOrDefault(0L)
}

/**
 * 整个 App 面向用户的钟点、日期一律按 [CampusTimeZone]（北京时间）显示和推算，不跟本机时区走：
 * 人在海外或手机时区没设成中国，课表、提醒、通知时间也和学校对得上。
 */
fun campusDateTimeOf(epochMs: Long): LocalDateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(CampusTimeZone)

/** 北京时间的墙钟时刻 → epoch 毫秒（绝对时刻，排提醒用），与本机时区无关。 */
fun campusEpochMsOf(at: LocalDateTime): Long = at.toInstant(CampusTimeZone).toEpochMilliseconds()

/** epoch 毫秒 → 北京时间 `HH:mm`。 */
fun campusClockText(epochMs: Long): String = campusDateTimeOf(epochMs).let { "${pad2(it.hour)}:${pad2(it.minute)}" }

/** 本机时区此刻的 UTC 偏移和北京时间一样吗；不一样时显示「已按北京时间显示」。 */
fun isDeviceOnCampusTime(clock: Clock = Clock.System, deviceZone: TimeZone = TimeZone.currentSystemDefault()): Boolean {
    val now = clock.now()
    return deviceZone.offsetAt(now) == CampusTimeZone.offsetAt(now)
}

/** 本机不在北京时间时给的一行提示；在北京时间就是 null。 */
fun campusTimeHint(clock: Clock = Clock.System, deviceZone: TimeZone = TimeZone.currentSystemDefault()): String? =
    if (isDeviceOnCampusTime(clock, deviceZone)) null else "已按北京时间显示"

private fun pad2(v: Int) = v.toString().padStart(2, '0')
