package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 本机时区的「今天」。
 *
 * `java.time.LocalDate.now()` 在 commonMain 不存在；而 `Clock.System.now().toLocalDateTime(
 * TimeZone.currentSystemDefault()).date` 是它的等价物（同样的系统默认时区、同样的「日历上的今天」）。
 * 这个写法原先在 `XjtuTime` 里有一份私有副本；`TermWeeks` 也要「今天」时就不该再抄第三份，
 * 所以提成共享的一行 —— 日期默认值是那种「抄一份就多一个口径漂移点」的东西。
 */
fun todayInSystemZone(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
