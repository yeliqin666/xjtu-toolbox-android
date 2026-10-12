package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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
fun todayInSystemZone(): LocalDate = nowInSystemZone().date

/**
 * 本机时区的「此刻」，精确到纳秒。`java.time.LocalDateTime.now()` 的等价物。
 *
 * 与 [todayInSystemZone] 同一行式子，只是不取 `.date`。判「今天这场考试考完没有」这类
 * 需要时刻的地方用它 —— 时刻一律在调用方算（`:app` 的 `TimeBridge` 那条纪律）。
 */
fun nowInSystemZone(): LocalDateTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
