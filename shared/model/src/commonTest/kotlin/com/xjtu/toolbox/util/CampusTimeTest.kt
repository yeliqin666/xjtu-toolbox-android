package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class CampusTimeTest {
    private fun at(iso: String) = object : Clock {
        override fun now(): Instant = Instant.parse(iso)
    }

    @Test
    fun utcEveningIsAlreadyTomorrowOnCampus() {
        // 北京时间 10-18 01:30；UTC 机器上还是 10-17
        assertEquals(LocalDate(2026, 10, 18), todayOnCampus(at("2026-10-17T17:30:00Z")))
    }

    @Test
    fun justBeforeCampusMidnightStaysToday() {
        assertEquals(LocalDate(2026, 10, 17), todayOnCampus(at("2026-10-17T15:59:59Z")))
        assertEquals(23, nowOnCampus(at("2026-10-17T15:59:59Z")).hour)
    }
}
