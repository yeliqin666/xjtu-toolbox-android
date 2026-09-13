package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TermWeeksTest {

    /** 2026 秋季学期，开学日期是周一。 */
    private val start = LocalDate.of(2026, 8, 31)

    @Test
    fun `开学当天是第一周`() {
        assertEquals(1, TermWeeks.weekOf(start, start))
    }

    @Test
    fun `开学那周周日仍是第一周`() {
        assertEquals(1, TermWeeks.weekOf(start, LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `下一个周一进入第二周`() {
        assertEquals(2, TermWeeks.weekOf(start, LocalDate.of(2026, 9, 7)))
    }

    /**
     * issue #44 的第一层：开学前几天。
     *
     * 旧写法 `(daysBetween / 7) + 1` 在 Kotlin 里对负数向零截断，-5/7 得 0，
     * 于是开学前五天被算成"第 1 周"。这里必须是 0（上一周）。
     */
    @Test
    fun `开学前几天是第零周而不是第一周`() {
        assertEquals(0, TermWeeks.weekOf(start, LocalDate.of(2026, 8, 26)))
        assertEquals(0, TermWeeks.weekOf(start, LocalDate.of(2026, 8, 30)))
        assertEquals(-1, TermWeeks.weekOf(start, LocalDate.of(2026, 8, 19)))
    }

    @Test
    fun `开学日期落在周中时锚到那周的周一`() {
        // 教务给的是周三，第 1 周仍应从这一周的周一算起。
        val midWeekStart = LocalDate.of(2026, 9, 2)
        assertEquals(1, TermWeeks.weekOf(midWeekStart, LocalDate.of(2026, 8, 31)))
        assertEquals(1, TermWeeks.weekOf(midWeekStart, LocalDate.of(2026, 9, 6)))
        assertEquals(2, TermWeeks.weekOf(midWeekStart, LocalDate.of(2026, 9, 7)))
    }

    /**
     * issue #44 的第二层：课表还没拉到时 `totalWeeks` 是 0。
     *
     * 0 是"不知道"，不是"零周"。旧逻辑直接比 `rawWeek > totalWeeks`，
     * 任何周次都大于 0，于是报"学期已结束"——这就是开学前夕那条 bug 的样子。
     */
    @Test
    fun `课表未加载时不得判为学期已结束`() {
        val status = TermWeeks.statusOf(start, totalWeeks = 0, today = LocalDate.of(2026, 9, 3))
        assertEquals(TermWeeks.Status.Unknown(1), status)
        assertNull(TermWeeks.noteOf(status))
        assertEquals(1, TermWeeks.displayWeekOf(status))
    }

    @Test
    fun `开学前夕且课表未加载时提示距开学`() {
        val status = TermWeeks.statusOf(start, totalWeeks = 0, today = LocalDate.of(2026, 8, 26))
        assertEquals(TermWeeks.Status.BeforeTerm(1), status)
        assertEquals("距开学还有 1 周", TermWeeks.noteOf(status))
    }

    @Test
    fun `超出总周数才算学期结束`() {
        val lastWeek = LocalDate.of(2026, 12, 21)   // 第 17 周
        assertEquals(17, TermWeeks.weekOf(start, lastWeek))
        assertEquals(
            TermWeeks.Status.InTerm(17),
            TermWeeks.statusOf(start, totalWeeks = 18, today = lastWeek)
        )
        assertEquals(
            TermWeeks.Status.AfterTerm,
            TermWeeks.statusOf(start, totalWeeks = 16, today = lastWeek)
        )
    }

    @Test
    fun `学期已开始但第一门课在后面时提示尚未开课`() {
        val status = TermWeeks.statusOf(
            start, totalWeeks = 18, firstTeachWeek = 3, today = LocalDate.of(2026, 9, 3)
        )
        assertEquals(TermWeeks.Status.NotStartedYet(week = 1, firstTeachWeek = 3), status)
        assertEquals("尚未开课 · 第3周开始上课", TermWeeks.noteOf(status))
        assertEquals(3, TermWeeks.displayWeekOf(status))
    }

    @Test
    fun `周次与日期互为逆运算`() {
        listOf(1, 5, 18).forEach { week ->
            (1..7).forEach { dow ->
                val date = TermWeeks.dateOf(start, week, dow)
                assertEquals(week, TermWeeks.weekOf(start, date))
                assertEquals(dow, date.dayOfWeek.value)
            }
        }
    }

    @Test
    fun `第一门课的周次取最小的置位`() {
        val courses = listOf(
            courseWithBits("0001100000"),
            courseWithBits("0100000000"),
        )
        assertEquals(2, TermWeeks.firstTeachWeekOf(courses))
        assertNull(TermWeeks.firstTeachWeekOf(emptyList()))
        assertNull(TermWeeks.firstTeachWeekOf(listOf(courseWithBits("0000"))))
    }

    private fun courseWithBits(bits: String) = CourseItem(
        courseName = "测试课",
        teacher = "",
        location = "",
        weekBits = bits,
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        courseCode = "",
        courseType = "",
    )
}
