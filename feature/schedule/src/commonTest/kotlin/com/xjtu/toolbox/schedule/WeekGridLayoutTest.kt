package com.xjtu.toolbox.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 周视图纵轴布局（[layoutWeekGrid]）和条目起止（[CourseItem.clockMinutes]）。
 * 夏令作息：第 4 节 11:10–12:00，第 5 节 14:30–15:20，第 8 节 17:40–18:30，第 9 节 19:40–20:30，
 * 第 11 节 21:40–22:30；冬令第 5 节 14:00–14:50，第 11 节 21:10–22:00。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/schedule/WeekGridLayoutTest.kt`：`WeekGridLayout`
 * 与 `CourseItem` 搬进 commonMain 后，那边按旧签名调用、运行期 `NoSuchMethodError`
 * （交接文档 §1.1）。断言与注释原样搬来，只换了壳：
 * JUnit → kotlin.test；`assertEquals(message, expected, actual)` 的参数序在 kotlin.test 里
 * 是 `(expected, actual, message)`，两处带名字的断言已相应改写。
 */
class WeekGridLayoutTest {

    private fun section(name: String, day: Int, start: Int, end: Int) = CourseItem(
        courseName = name, dayOfWeek = day, startSection = start, endSection = end,
    )

    private fun timed(name: String, day: Int, start: String, end: String) = CourseItem(
        courseName = name, dayOfWeek = day, startSection = 1, endSection = 1,
        startMinuteOfDay = start.minutes(), endMinuteOfDay = end.minutes(),
    )

    private fun String.minutes() = split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

    private fun summerWeek(vararg slots: ScheduleSlot) = layoutWeekGrid(slots.toList()) { true }

    private fun WeekGridLayout.placed(name: String) = slots.single { it.slot.slotName == name }

    private fun WeekGridLayout.rowOf(section: Int) = rows.indexOfFirst { it.section == section }

    private fun WeekGridLayout.labels() = rows.map { it.section?.toString() ?: it.label }

    // ── 条目起止 ──

    @Test
    fun `clock minutes prefer valid custom clock, else sections of the given season`() {
        assertEquals(840 to 890, section("冬五", 1, 5, 5).clockMinutes(summer = false))
        assertEquals(870 to 920, section("夏五", 1, 5, 5).clockMinutes(summer = true))
        assertEquals(0 to 60, timed("零点", 1, "00:00", "01:00").clockMinutes(true))
        assertEquals(23 * 60 to 24 * 60, timed("到午夜", 1, "23:00", "24:00").clockMinutes(true))
    }

    @Test
    fun `invalid custom clock falls back to sections`() {
        val reversed = CourseItem(dayOfWeek = 1, startSection = 1, endSection = 2, startMinuteOfDay = 600, endMinuteOfDay = 500)
        val beyondDay = CourseItem(dayOfWeek = 1, startSection = 1, endSection = 2, startMinuteOfDay = 1400, endMinuteOfDay = 1500)
        val zeroLength = CourseItem(dayOfWeek = 1, startSection = 1, endSection = 2, startMinuteOfDay = 600, endMinuteOfDay = 600)
        listOf(reversed, beyondDay, zeroLength).forEach { assertEquals(480 to 590, it.clockMinutes(true)) }
    }

    @Test
    fun `legacy custom entries without clock read sections as hours from 8am, like the editor`() {
        val legacy = CourseItem(startSection = 5, endSection = 6, courseCode = "${CUSTOM_COURSE_CODE_PREFIX}1")
        // 第 5–6「节」= 12:00–14:00，两套作息都一样
        assertEquals(720 to 840, legacy.clockMinutes(summer = true))
        assertEquals(720 to 840, legacy.clockMinutes(summer = false))
        assertEquals(480 to 1320, CourseItem(startSection = -3, endSection = 99, courseCode = "${CUSTOM_COURSE_CODE_PREFIX}2").clockMinutes(true))
    }

    @Test
    fun `sections outside the timetable are clamped, end before start uses the start section`() {
        assertEquals(480 to 530, CourseItem(startSection = 0, endSection = 0).clockMinutes(true))
        assertEquals(1300 to 1350, CourseItem(startSection = 14, endSection = 20).clockMinutes(true))
        assertEquals(870 to 920, CourseItem(startSection = 5, endSection = 3).clockMinutes(true))
    }

    // ── 纵轴 ──

    @Test
    fun `plain week draws sections 1-10 with lunch and dinner bands only`() {
        val layout = summerWeek(section("高数", 1, 1, 2))
        assertEquals(listOf("1", "2", "3", "4", "午休", "5", "6", "7", "8", "晚休", "9", "10"), layout.labels())
    }

    @Test
    fun `section courses cover exactly their rows`() {
        val layout = summerWeek(section("物理", 1, 3, 4), section("线代", 1, 5, 6))
        assertEquals(layout.rowOf(3).toFloat(), layout.placed("物理").start)
        // 3-4 节的下沿是午休带的上沿，不盖住午休
        assertEquals((layout.rowOf(4) + 1).toFloat(), layout.placed("物理").end)
        assertEquals(layout.rowOf(5).toFloat(), layout.placed("线代").start)
        assertEquals((layout.rowOf(6) + 1).toFloat(), layout.placed("线代").end)
    }

    @Test
    fun `section 11 gets its own row instead of overlapping section 10`() {
        val layout = summerWeek(section("形策", 3, 9, 10), section("研讨", 3, 11, 11))
        assertEquals("11", layout.labels().last())
        assertEquals(2, buildConflictGroups(layout.slots).size)
    }

    @Test
    fun `mixed-season week aligns each day to its own timetable`() {
        // 10/1 所在那周：周三夏令、周四冬令
        val layout = layoutWeekGrid(
            listOf(section("物理", 3, 5, 6), section("英语", 4, 3, 4), section("体育", 4, 5, 6), section("电路", 4, 7, 8)),
        ) { day -> day <= 3 }
        listOf("物理" to 5, "体育" to 5, "电路" to 7).forEach { (name, first) ->
            assertEquals(layout.rowOf(first).toFloat(), layout.placed(name).start, name)
            assertEquals((layout.rowOf(first + 1) + 1).toFloat(), layout.placed(name).end, name)
        }
        // 两套时间各自记着，左轴能都标出来
        assertEquals(870 to 920, layout.times(summer = true)[layout.rowOf(5)])
        assertEquals(840 to 890, layout.times(summer = false)[layout.rowOf(5)])
        // 周四上午的英语和下午的体育不冲突
        assertEquals(4, buildConflictGroups(layout.slots).size)
    }

    @Test
    fun `timed entry on a winter day lands by the winter timetable`() {
        val layout = layoutWeekGrid(listOf(timed("答疑", 4, "14:00", "14:50"))) { false }
        assertEquals(layout.rowOf(5).toFloat(), layout.placed("答疑").start)
        assertEquals((layout.rowOf(5) + 1).toFloat(), layout.placed("答疑").end)
    }

    @Test
    fun `entries inside a rest band land in the band`() {
        val layout = summerWeek(timed("午饭会", 2, "12:30", "13:30"))
        val lunch = layout.rows.indexOfFirst { it.label == "午休" }
        val meeting = layout.placed("午饭会")
        // 夏令午休 12:00–14:30
        assertEquals(lunch + 0.2f, meeting.start, 1e-4f)
        assertEquals(lunch + 0.6f, meeting.end, 1e-4f)
        assertTrue(layout.isOccupied(lunch))
    }

    @Test
    fun `band pieces split occupied from free time, merging overlaps across days`() {
        val layout = summerWeek(
            timed("午饭会", 2, "12:30", "13:30"),
            timed("答疑", 3, "13:00", "13:45"),
        )
        val lunch = layout.rows.indexOfFirst { it.label == "午休" }
        val pieces = layout.pieces(lunch)
        assertEquals(listOf(false, true, false), pieces.map { it.occupied })
        assertEquals(0.2f, pieces[1].from, 1e-4f)
        assertEquals(0.7f, pieces[1].to, 1e-4f)
        assertEquals(1f, pieces.last().to, 1e-4f)
    }

    @Test
    fun `early and late entries add bands instead of being squashed`() {
        val layout = summerWeek(timed("晨跑", 1, "06:30", "07:30"), timed("夜宵", 5, "22:40", "23:50"))
        val labels = layout.labels()
        assertEquals("早间", labels.first())
        assertEquals("夜间", labels.last())
        // 夜宵在第 11 节之后，第 11 节也要画出来
        assertTrue("11" in labels)
        assertEquals(0f, layout.placed("晨跑").start, 1e-4f)
        assertEquals(layout.rows.size.toFloat(), layout.placed("夜宵").end, 1e-4f)
    }

    @Test
    fun `short entry in a break is stretched to the minimum and conflicts with what it then overlaps`() {
        // 8:50–9:00 是第 1、2 节之间的课间，不占高度；拉到 20 分钟后是 8:50–9:10，和第 2 节重叠
        val layout = summerWeek(timed("课间", 1, "08:50", "09:00"), section("二节", 1, 2, 2))
        val gap = layout.placed("课间")
        assertEquals(layout.rowOf(2).toFloat(), gap.start, 1e-4f)
        assertEquals(layout.rowOf(2) + 0.2f, gap.end, 1e-4f)
        assertEquals(1, buildConflictGroups(layout.slots).size)
    }

    @Test
    fun `minimum length never runs past midnight`() {
        val layout = summerWeek(timed("深夜", 7, "23:59", "24:00"))
        val slot = layout.placed("深夜")
        assertEquals(layout.rows.size.toFloat(), slot.end, 1e-4f)
        assertTrue(slot.start < slot.end)
    }

    @Test
    fun `invalid weekdays are dropped`() {
        val layout = summerWeek(section("零", 0, 1, 2), section("八", 8, 1, 2), section("一", 1, 1, 2))
        assertEquals(listOf("一"), layout.slots.map { it.slot.slotName })
    }

    @Test
    fun `now line sits inside the dinner band during dinner`() {
        val layout = summerWeek()
        val dinner = layout.rows.indexOfFirst { it.label == "晚休" }
        val pos = layout.positionOf("18:33".minutes(), summer = true)
        assertTrue(pos > dinner && pos < dinner + 1)
    }

    // ── 冲突分组 ──

    @Test
    fun `conflict groups are transitive per day and ignore touching edges`() {
        val layout = summerWeek(
            timed("A", 1, "08:00", "09:00"),
            timed("B", 1, "08:30", "10:30"),
            timed("C", 1, "10:20", "11:00"),
            section("D", 1, 5, 5),
            section("E", 1, 6, 6),
            timed("F", 2, "08:00", "09:00"),
        )
        val groups = buildConflictGroups(layout.slots).map { g -> g.slots.map { it.slot.slotName }.sorted() }
        assertEquals(listOf(listOf("A", "B", "C"), listOf("D"), listOf("E"), listOf("F")), groups.sortedBy { it.first() })
    }
}
