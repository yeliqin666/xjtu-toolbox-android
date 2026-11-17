package com.xjtu.toolbox.widget

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.schedule.attendanceMarksOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 智能卡片上课那一屏：按用户要求，本堂考勤状态直接摆在卡片上，不再是「查看考勤」按钮。
 *
 * 考勤索引按「周次 + 星期 + 节次」取，判据与课表角标同一份（`attendanceMarksOf`），
 * 这里只验卡片取哪一格、取不到时说什么。
 */
class SmartWidgetTest {

    private val course = WidgetCourse(
        name = "高等数学", location = "主楼A-202",
        startMinute = 10 * 60, endMinute = 11 * 60 + 40, color = 0,
        startSection = 3,
    )

    private fun marks(status: WaterType) = attendanceMarksOf(
        listOf(
            AttendanceWaterRecord(
                sbh = "1", startTime = 3, endTime = 4, week = 6, date = "2026-11-17",
                courseName = "高等数学", status = status,
            ),
        ),
    )

    @Test
    fun `上课这一屏把本堂考勤状态写在卡片上`() {
        // 2026-11-17 是周二，第 6 周第 3 节 —— 就是这一格
        val scene = SmartWidgetUpdater.sceneOf(
            courses = listOf(course), nowMinute = 10 * 60 + 5, balance = "¥12.30",
            attendance = marks(WaterType.NORMAL), week = 6, dayOfWeek = 2,
        )
        assertEquals("上课中", scene.label)
        assertEquals("高等数学", scene.title)
        assertEquals("考勤不再只给按钮：状态本身要显示出来（这里是正常）", "已记考勤 · 正常", scene.status)
    }

    @Test
    fun `缺勤照实说`() {
        val scene = SmartWidgetUpdater.sceneOf(
            courses = listOf(course), nowMinute = 10 * 60, balance = "¥1.00",
            attendance = marks(WaterType.ABSENCE), week = 6, dayOfWeek = 2,
        )
        assertEquals("已记考勤 · 缺勤", scene.status)
    }

    @Test
    fun `没有落盘考勤时说未记录，不冒充正常`() {
        val scene = SmartWidgetUpdater.sceneOf(
            courses = listOf(course), nowMinute = 10 * 60, balance = "¥1.00",
            attendance = null, week = 6, dayOfWeek = 2,
        )
        assertEquals("考勤未记录", scene.status)
    }

    @Test
    fun `算不出周次就不查考勤`() {
        val scene = SmartWidgetUpdater.sceneOf(
            courses = listOf(course), nowMinute = 10 * 60, balance = "¥1.00",
            attendance = marks(WaterType.NORMAL), week = 0, dayOfWeek = 2,
        )
        assertEquals("考勤未记录", scene.status)
    }

    @Test
    fun `不是上课时间就不带考勤那一行`() {
        // 9:30：既不在早饭窗口（6:30–9:00），也还没进课前 20 分钟（9:40 起）
        val scene = SmartWidgetUpdater.sceneOf(
            courses = listOf(course), nowMinute = 9 * 60 + 30, balance = "¥1.00",
            attendance = marks(WaterType.NORMAL), week = 6, dayOfWeek = 2,
        )
        assertEquals("下一节 10:00", scene.label)
        assertNull(scene.status)
    }
}
