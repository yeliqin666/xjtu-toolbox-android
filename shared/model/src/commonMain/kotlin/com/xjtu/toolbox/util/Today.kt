package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** 本机时区的「今天」。只用于纯显示；业务日期用 [todayOnCampus]。 */
fun todayInSystemZone(): LocalDate = nowInSystemZone().date

/** 本机时区的「此刻」，精确到纳秒。只用于纯显示；业务时间用 [nowOnCampus]。 */
fun nowInSystemZone(): LocalDateTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
