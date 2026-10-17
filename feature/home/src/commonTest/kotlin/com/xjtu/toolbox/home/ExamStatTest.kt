package com.xjtu.toolbox.home

import com.xjtu.toolbox.schedule.ExamItem
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExamStatTest {
    private val now = LocalDateTime(2026, 1, 5, 12, 0)

    @Test
    fun `一场时写倒计时和考场座号`() {
        val s = assertNotNull(examStat(listOf(ExamItem("高等数学", examDate = "2026-01-08", examTime = "09:00-11:00", location = "主楼B-205", seatNumber = "37")), now))
        assertEquals("高等数学", s.value)
        assertEquals("还有 3 天 · 09:00 · 主楼B-205 · 座 37", s.detail)
    }

    @Test
    fun `7 天内扎堆时标题写场数，今天已考完的不算`() {
        val exams = listOf(
            ExamItem("已考完", examDate = "2026-01-05", examTime = "08:00-10:00"),
            ExamItem("大学物理", examDate = "2026年1月6日", examTime = "14:30-16:30", location = "东2-301"),
            ExamItem("线性代数", examDate = "2026-01-09"),
            ExamItem("下周的", examDate = "2026-01-20"),
        )
        val s = assertNotNull(examStat(exams, now))
        assertEquals("大学物理", s.value)
        assertEquals("近 7 天 2 场 · 明天就考 · 14:30 · 东2-301", s.detail)
    }

    @Test
    fun `全考完了没有这一格`() {
        assertNull(examStat(listOf(ExamItem("x", examDate = "2026-01-01")), now))
    }
}