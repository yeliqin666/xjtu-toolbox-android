package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** 本机时区的「今天」。 */
fun todayInSystemZone(): LocalDate = nowInSystemZone().date

/** 本机时区的「此刻」，精确到纳秒。 */
fun nowInSystemZone(): LocalDateTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
