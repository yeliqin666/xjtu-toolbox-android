package com.xjtu.toolbox.schedule

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExamChangesTest {
    private val now = LocalDateTime(2026, 1, 5, 12, 0)
    private val math = ExamItem("高等数学", "MATH1", "2026-01-08", "09:00-11:00", "主楼B-205", "37")

    @Test
    fun `日期没变只换了考场也要报`() {
        val changed = math.copy(location = "主楼A-101")
        assertEquals(listOf("高等数学：考场 主楼B-205 → 主楼A-101"), ExamCountdown.changes(listOf(math), listOf(changed), now))
    }

    @Test
    fun `新增和消失的场次都报，已经考完的不管`() {
        val ended = ExamItem("早考完", "OLD", "2026-01-02")
        val phys = ExamItem("大学物理", "PHYS", "2026-01-09", "14:30-16:30", "东2-301")
        val out = ExamCountdown.changes(listOf(math, ended), listOf(phys), now)
        assertTrue(out.any { it.startsWith("新增 大学物理") })
        assertTrue(out.any { it.startsWith("高等数学：考试表里没有这场了") })
        assertEquals(2, out.size)
    }

    @Test
    fun `第一次拉和空表都不报`() {
        assertTrue(ExamCountdown.changes(null, listOf(math), now).isEmpty())
        assertTrue(ExamCountdown.changes(listOf(math), emptyList(), now).isEmpty())
    }
}