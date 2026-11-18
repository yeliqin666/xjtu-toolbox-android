package com.xjtu.toolbox.widget

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.schedule.attendanceMarksOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能卡片上课那一屏：本堂考勤状态直接摆在卡片上。
 *
 * 2026-11-18 的反馈定了口径：**没记录不能一律说「未记录」**——课前 / 刚上课时学校本来
 * 还没到出结论的时候，那是「待考勤」；上课五分钟还没刷上就该当迟到提醒；请假单独说。
 */
class SmartWidgetTest {

    private val course = WidgetCourse(
        name = "高等数学", location = "主楼A-202",
        startMinute = 10 * 60, endMinute = 11 * 60 + 40, color = 0,
        startSection = 3, endSection = 4,
    )

    private val day = "2026-11-17"

    private fun marks(status: WaterType, name: String = "高等数学", code: String = "") = attendanceMarksOf(
        listOf(
            AttendanceWaterRecord(
                sbh = "1", startTime = 3, endTime = 4, week = 6, date = day,
                courseName = name, courseCode = code, status = status,
            ),
        ),
    )

    private fun scene(
        nowMinute: Int,
        attendance: com.xjtu.toolbox.schedule.SlotAttendanceMarks?,
        swipe: LocalSwipe? = null,
        checkedAgoMin: Int? = null,
    ) = SmartWidgetUpdater.sceneOf(
        courses = listOf(course), nowMinute = nowMinute, balance = "¥12.30",
        attendance = attendance, date = day,
        swipe = swipe, checkedAgoMin = checkedAgoMin,
    )

    @Test
    fun `有记录就照实报`() {
        assertEquals("已打卡 · 正常", scene(10 * 60 + 10, marks(WaterType.NORMAL)).status)
        assertEquals("已打卡 · 迟到", scene(10 * 60 + 10, marks(WaterType.LATE)).status)
        assertEquals("已打卡 · 缺勤", scene(10 * 60 + 50, marks(WaterType.ABSENCE)).status)
        // 请假单独说，不带「打卡」——人根本没来，打卡这个词会误导
        assertEquals("已请假", scene(10 * 60 + 10, marks(WaterType.LEAVE)).status)
    }

    @Test
    fun `没记录时上课五分钟内算待考勤`() {
        assertEquals("待考勤", scene(10 * 60 + 1, null).status)
        assertEquals("待考勤", scene(10 * 60 + 4, null).status)
    }

    @Test
    fun `没记录时上课五分钟后就报迟到`() {
        assertEquals("迟到 · 还没刷上", scene(10 * 60 + 6, null).status)
        assertEquals("迟到 · 还没刷上", scene(11 * 60 + 30, null).status)
    }

    @Test
    fun `下课前一分钟还没记录仍报迟到`() {
        // 窗口是「课前 20 分钟 ～ 下课」，11:39 还在窗口内；下课之后这一屏就不再出现
        assertEquals("迟到 · 还没刷上", scene(11 * 60 + 39, null).status)
    }

    @Test
    fun `课前那一屏也给出待考勤`() {
        val s = scene(9 * 60 + 45, null)
        assertEquals("15 分钟后上课", s.label)
        assertEquals("待考勤", s.status)
    }

    @Test
    fun `按日期找记录，不受周次口径影响`() {
        // 同一天同一节次、但不是这门课：不能被认成自己的记录
        assertEquals("待考勤", scene(10 * 60 + 1, marks(WaterType.NORMAL, name = "大学物理")).status)
    }

    @Test
    fun `不是上课时间就不带考勤那一行`() {
        // 9:30：既不在早饭窗口（6:30–9:00），也还没进课前 20 分钟（9:40 起）
        val s = scene(9 * 60 + 30, marks(WaterType.NORMAL))
        assertEquals("下一节 10:00", s.label)
        assertNull(s.status)
    }

    @Test
    fun `认课优先用课程号，课名写法不一致也认得出`() {
        // 考勤系统的课名和课表里的写法未必一字不差（全角半角括号、班号后缀）
        val coded = WidgetCourse(
            name = "高等数学(甲)", location = "主楼A-202",
            startMinute = 10 * 60, endMinute = 11 * 60 + 40, color = 0,
            startSection = 3, endSection = 4, courseCode = "MATH100101-05",
        )
        val s = SmartWidgetUpdater.sceneOf(
            courses = listOf(coded), nowMinute = 10 * 60 + 10, balance = "¥12.30",
            attendance = marks(WaterType.NORMAL, name = "高等数学（甲）", code = "MATH100101-05"),
            date = day,
        )
        assertEquals("已打卡 · 正常", s.status)
    }

    @Test
    fun `只有真的记录才不需要再盯着看`() {
        // 没记录 = 结论随时会被学校那条记录推翻，卡片要自己回来看一眼
        assertTrue("待考勤要盯着", scene(10 * 60 + 1, null).needsWatching)
        assertTrue("迟到·还没刷上更要盯着", scene(10 * 60 + 6, null).needsWatching)
        assertFalse("有记录就不必再回来", scene(10 * 60 + 10, marks(WaterType.NORMAL)).needsWatching)
        assertFalse("请假也是定论", scene(10 * 60 + 10, marks(WaterType.LEAVE)).needsWatching)
        // 不是上课那一屏就没有「等他刷卡」这回事
        assertFalse("别的场景不用盯", scene(9 * 60 + 30, marks(WaterType.NORMAL)).needsWatching)
    }

    @Test
    fun `刷卡流水里有记录就先报已刷卡，别喊还没刷上`() {
        // 考勤结果要等结算，但刷卡流水是实时的：这段时间里刷过，就不该继续催人去补刷
        val swipe = LocalSwipe(minuteOfDay = 9 * 60 + 58, location = "主楼A-202")
        val s = scene(10 * 60 + 6, attendance = null, swipe = swipe)
        assertEquals("已刷卡 · 待结算", s.status)
        assertEquals("要如实说这是刷卡时刻，不是「刚刚查过」", "刷卡 09:58", s.meta)
        assertFalse("已经刷上了，只差结算，不必再五分钟问一次", s.needsWatching)
    }

    @Test
    fun `刷卡时间落在本节课窗口外就不算这节课的`() {
        // 上午的课刷的卡，不能拿来给下午的课充数
        val morning = LocalSwipe(minuteOfDay = 8 * 60 + 10)
        assertEquals("迟到 · 还没刷上", scene(10 * 60 + 6, attendance = null, swipe = morning).status)
        // 下课后过了很久的刷卡也不算（可能是别的课/别的活动）
        val evening = LocalSwipe(minuteOfDay = 20 * 60)
        assertEquals("迟到 · 还没刷上", scene(10 * 60 + 6, attendance = null, swipe = evening).status)
    }

    @Test
    fun `没记录时如实标出这个结论是什么时候查的`() {
        // 学校没有推送，卡片只能定时间去问：说清是哪一刻的事实，才不会被过期结论误导
        assertEquals("还未查询过", scene(10 * 60 + 6, null, checkedAgoMin = null).meta)
        assertEquals("刚刚查过", scene(10 * 60 + 6, null, checkedAgoMin = 0).meta)
        assertEquals("3 分钟前查过", scene(10 * 60 + 6, null, checkedAgoMin = 3).meta)
        // 有记录就是定论，不用再标时刻
        assertNull(scene(10 * 60 + 10, marks(WaterType.NORMAL), checkedAgoMin = 3).meta)
    }

    @Test
    fun `刷卡时刻解析`() {
        assertEquals(14 * 60 + 14, minuteOfDay("2026-11-18 14:14:37"))
        // 只有时间没有日期也认（学校两个接口的格式不完全一致）
        assertEquals(8 * 60 + 5, minuteOfDay("08:05"))
        assertNull(minuteOfDay(""))
        assertNull(minuteOfDay("乱麻"))
        assertNull(minuteOfDay("99:99"))
    }

    @Test
    fun `自查分档：课前十分钟起两分钟一次，课前五到上课后五一分钟一次`() {
        // 这门课 10:00 开始、11:40 下课
        val start = 10 * 60
        // 课前 20～10 分钟：还不必问（人还没到楼下）
        assertNull(SmartWidgetUpdater.attendancePollInterval(course, start - 12))
        // 课前 10 分钟起：每 2 分钟
        assertEquals(2, SmartWidgetUpdater.attendancePollInterval(course, start - 10))
        assertEquals(2, SmartWidgetUpdater.attendancePollInterval(course, start - 6))
        // 课前 5 分钟 ～ 上课后 5 分钟：刷卡高峰，每 1 分钟
        assertEquals(1, SmartWidgetUpdater.attendancePollInterval(course, start - 5))
        assertEquals(1, SmartWidgetUpdater.attendancePollInterval(course, start))
        assertEquals(1, SmartWidgetUpdater.attendancePollInterval(course, start + 5))
        // 之后到下课：已成定局，退回 5 分钟（只为发现补刷）
        assertEquals(5, SmartWidgetUpdater.attendancePollInterval(course, start + 6))
        // 下课之后这一屏不再显示考勤，也不该再查
        assertNull(SmartWidgetUpdater.attendancePollInterval(course, course.endMinute))
    }
}
