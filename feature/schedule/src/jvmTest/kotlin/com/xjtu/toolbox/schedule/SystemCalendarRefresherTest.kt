package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** 系统日历（#462）的重读调度：回前台 / 跨天直接重读，日历库变化防抖后重读，窗口前后各多一天。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemCalendarRefresherTest {

    private val today = LocalDate(2027, 1, 10)
    private fun midnight(day: Int) = LocalDateTime(2027, 1, day, 0, 0).toInstant(CampusTimeZone)
    private val sample = SystemCalendarEvent("生日", midnight(10), midnight(11), allDay = true)

    @Test
    fun 重读时按放宽一天的窗口去要并交出结果() = runTest {
        val windows = mutableListOf<Pair<Instant, Instant>>()
        var shown: List<SystemCalendarEvent> = emptyList()
        val refresher = SystemCalendarRefresher(
            scope = backgroundScope, days = 8, today = { today },
            load = { from, to -> windows += from to to; listOf(sample) },
            onLoaded = { shown = it },
        )
        refresher.refresh()
        runCurrent()
        assertEquals(listOf(midnight(9) to midnight(19)), windows)
        assertEquals(listOf(sample), shown)
    }

    @Test
    fun 日历库连着变几下只重读一次() = runTest {
        var loads = 0
        val changes = MutableSharedFlow<Unit>()
        val refresher = SystemCalendarRefresher(
            scope = backgroundScope, days = 8, today = { today },
            load = { _, _ -> loads++; emptyList() },
            onLoaded = {},
        )
        backgroundScope.launch { refresher.watch(changes) }
        runCurrent()
        repeat(3) {
            changes.emit(Unit)
            advanceTimeBy(100)
        }
        assertEquals(0, loads)
        advanceTimeBy(SystemCalendarRefresher.DEFAULT_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, loads)
        changes.emit(Unit)
        advanceTimeBy(SystemCalendarRefresher.DEFAULT_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(2, loads)
    }

    @Test
    fun 回到前台和跨天时直接重读且用新的日期() = runTest {
        val windows = mutableListOf<Instant>()
        val refresher = SystemCalendarRefresher(
            scope = backgroundScope, days = 8, today = { today },
            load = { from, _ -> windows += from; emptyList() },
            onLoaded = {},
        )
        refresher.refresh() // 回到前台（onResume）
        runCurrent()
        refresher.refresh(LocalDate(2027, 1, 11)) // 跨天
        runCurrent()
        assertEquals(listOf(midnight(9), midnight(10)), windows)
    }

    @Test
    fun 读失败记为空表不抛出() = runTest {
        var shown: List<SystemCalendarEvent>? = null
        var error: Exception? = null
        val refresher = SystemCalendarRefresher(
            scope = backgroundScope, days = 8, today = { today },
            load = { _, _ -> throw IllegalStateException("provider gone") },
            onLoaded = { shown = it },
            onError = { error = it },
        )
        refresher.refresh()
        runCurrent()
        assertTrue(shown!!.isEmpty())
        assertEquals("provider gone", error?.message)
    }
}
