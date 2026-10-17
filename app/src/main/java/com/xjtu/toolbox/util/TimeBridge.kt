package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/** `:core`（kotlinx-datetime）与 `:app`（java.time）之间的边界转换。 */
internal fun java.time.LocalDateTime.toKx(): LocalDateTime =
    LocalDateTime(year, monthValue, dayOfMonth, hour, minute, second, nano)

internal fun java.time.LocalTime.toKx(): LocalTime =
    LocalTime(hour, minute, second, nano)

/** `java.time.Instant` → `kotlin.time.Instant`，按「秒 + 纳秒」搬运，不丢精度。 */
internal fun java.time.Instant.toKx(): Instant = Instant.fromEpochSeconds(epochSecond, nano.toLong())

/** `java.time.LocalDate` ↔ `kotlinx.datetime.LocalDate`。 */
internal fun java.time.LocalDate.toKx(): LocalDate = LocalDate(year, monthValue, dayOfMonth)

internal fun LocalDate.toJavaTime(): java.time.LocalDate = java.time.LocalDate.of(year, month.ordinal + 1, day)

/** 时刻那一条：`HomeHero` 算出的「下一节课什么时候开始」要进 `:app` 的提醒评估（它说 java.time）。 */
internal fun LocalDateTime.toJavaTime(): java.time.LocalDateTime =
    java.time.LocalDateTime.of(year, month.ordinal + 1, day, hour, minute, second, nanosecond)
