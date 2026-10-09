package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `beijingEpochMs` 是 :core 里**唯一**解析「校园上游东八区时间串」的地方。
 *
 * 这个测试守两件事：
 * 1. 具体数值（不是拿同一套 API 自证）：`2026-10-07 20:00:02` +08:00 就是
 *    `2026-10-07T12:00:02Z`，epoch 毫秒固定；
 * 2. **与 :app 的口径逐字同值** —— :app 的 `SchoolInbox` 用
 *    `LocalDateTime.parse(...).atZone(ZoneId.of("Asia/Shanghai"))`。这里在 JVM 上
 *    直接比一遍（JVM 自带 tzdb，能跑）；Web 上 `TimeZone.of` 会抛，所以实现里用的是
 *    固定 +08:00 —— 中国大陆 1991 年后无夏令时，两者对这些时间戳同值。
 */
class BeijingTimeTest {

    @Test
    fun parsesUpstreamShape() {
        // 2026-10-07 20:00:02 +08:00 == 2026-10-07T12:00:02Z
        assertEquals(1_791_374_402_000L, beijingEpochMs("2026-10-07 20:00:02"))
        // 2026-10-07 01:43:11 +08:00 == 2026-10-06T17:43:11Z
        assertEquals(1_791_308_591_000L, beijingEpochMs("2026-10-07 01:43:11"))
    }

    @Test
    fun matchesAppZoneForTheseTimestamps() {
        val samples = listOf(
            "2026-10-07 20:00:02",
            "2026-10-07 01:43:11",
            "2026-01-01 00:00:00",
            "2026-12-31 23:59:59",
        )
        for (s in samples) {
            val viaApp = LocalDateTime.parse(s.replace(' ', 'T'))
                .toInstant(TimeZone.of("Asia/Shanghai"))
                .toEpochMilliseconds()
            assertEquals(viaApp, beijingEpochMs(s), "与 :app 的 Asia/Shanghai 口径不一致：$s")
        }
    }

    @Test
    fun toleratesTrailingFractionAndRejectsGarbage() {
        // 上游偶尔带毫秒；只取前 19 位，值不变
        assertEquals(beijingEpochMs("2026-10-07 20:00:02"), beijingEpochMs("2026-10-07 20:00:02.123"))
        // 认不出来就 0（调用方按「没有时间」处理，不要抛）
        assertEquals(0L, beijingEpochMs(null))
        assertEquals(0L, beijingEpochMs(""))
        assertEquals(0L, beijingEpochMs("2026-10-07"))
        assertEquals(0L, beijingEpochMs("None"))
        assertEquals(0L, beijingEpochMs("不是时间"))
    }
}
