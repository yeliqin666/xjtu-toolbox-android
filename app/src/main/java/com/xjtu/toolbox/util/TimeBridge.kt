package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * 迁移期的**边界转换**：`:core` 已经改说 kotlinx-datetime，`:app` 还剩 java.time。
 *
 * 每把 :app 的一处纯逻辑搬进 :core，这里就会多（或少）一条。它存在的意义是让搬迁可以
 * **一批一批走**，而不是被「全仓 java.time 一次性换掉」绑成一个不可回退的大提交。
 * 目标状态是这个文件被删掉 —— 所以：
 *   - 只做逐字段搬运，不许在这里加任何业务逻辑；
 *   - 新写的 :app 代码不要用它们（直接用 kotlinx-datetime）；
 *   - 谁把某个文件的 java.time 全换掉了，就把对应的那条删掉。
 *
 * ⚠️ 转换本身是逐位无损的（同一年/月/日/时/分/秒/纳秒），但**时区语义**不在类型里：
 * `java.time.LocalDateTime.now()` 用的是 JVM 默认时区，`kotlinx` 那边的 now 要显式给
 * `TimeZone.currentSystemDefault()`。所以这里只搬「没有时区的那部分」，now 一律在调用方算。
 */
internal fun java.time.LocalDateTime.toKx(): LocalDateTime =
    LocalDateTime(year, monthValue, dayOfMonth, hour, minute, second, nano)

internal fun java.time.LocalTime.toKx(): LocalTime =
    LocalTime(hour, minute, second, nano)

/** `java.time.Instant` → `kotlin.time.Instant`，按「秒 + 纳秒」搬运，不丢精度。 */
internal fun java.time.Instant.toKx(): Instant = Instant.fromEpochSeconds(epochSecond, nano.toLong())

/**
 * `java.time.LocalDate` ↔ `kotlinx.datetime.LocalDate`，年/月/日逐个搬。
 *
 * 两个方向都要：`TermWeeks` 这类搬进 :core 的纯逻辑**收 kotlinx 日期、也吐 kotlinx 日期**
 * （`dateOf` 就是吐日期的那种），所以 :app 侧既要往进转、也要往回转。
 * 手写而不是用 kotlinx-datetime 的 `toKotlinLocalDate()/toJavaLocalDate()`：那两个是
 * jvmMain 专属 API，与这里的另外三条（手写、逐字段）风格不一致，且 `dayOfMonth` 在新版里
 * 已是 deprecated —— 逐字段写反而更稳。
 */
internal fun java.time.LocalDate.toKx(): LocalDate = LocalDate(year, monthValue, dayOfMonth)

internal fun LocalDate.toJavaTime(): java.time.LocalDate = java.time.LocalDate.of(year, month.ordinal + 1, day)
