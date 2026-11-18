package com.xjtu.toolbox.widget

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.schedule.attendanceMarksOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun marks(status: WaterType, name: String = "高等数学") = attendanceMarksOf(
        listOf(
            AttendanceWaterRecord(
                sbh = "1", startTime = 3, endTime = 4, week = 6, date = day,
                courseName = name, status = status,
            ),
        ),
    )

    private fun scene(
        nowMinute: Int,
        attendance: com.xjtu.toolbox.schedule.SlotAttendanceMarks?,
    ) = SmartWidgetUpdater.sceneOf(
        courses = listOf(course), nowMinute = nowMinute, balance = "¥12.30",
        attendance = attendance, date = day,
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
}
