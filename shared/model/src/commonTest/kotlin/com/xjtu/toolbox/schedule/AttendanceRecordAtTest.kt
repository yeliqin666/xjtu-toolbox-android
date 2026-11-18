package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 按日期取考勤（智能卡片用）。
 *
 * 2026-11-18 的真机反馈是「明明考勤正常，卡片却显示未记录」。成因是原来按**周次**取：
 * 教务的开学日和考勤系统的学期起始日未必同一天，差一天就整体错一周，于是查不到。
 * 这里钉住「按日期 + 节次 + 课名」这个不受周次影响的判据。
 */
class AttendanceRecordAtTest {

    private fun rec(
        date: String = "2026-11-17",
        startTime: Int = 3,
        endTime: Int = 4,
        week: Int = 6,
        name: String = "高等数学",
        code: String = "",
        status: WaterType = WaterType.NORMAL,
    ) = AttendanceWaterRecord(
        sbh = "$date-$startTime-$name", startTime = startTime, endTime = endTime,
        week = week, date = date, courseName = name, courseCode = code, status = status,
    )

    @Test
    fun `周次对不上也能按日期取到记录`() {
        // 学校算成第 6 周，教务那边可能算第 5 周：日期一致就该命中
        val marks = attendanceMarksOf(listOf(rec(week = 6, status = WaterType.ABSENCE)))
        assertEquals(WaterType.ABSENCE, marks.recordAt("2026-11-17", 3, 4, "高等数学")?.status)
        // 同一个索引按周次查第 5 周是查不到的 —— 这正是原来显示「未记录」的原因
        assertNull(marks.statusOf(5, 2, 3))
    }

    @Test
    fun `周次算不出来（0）的记录照样能按日期取到`() {
        val marks = attendanceMarksOf(listOf(rec(week = 0, status = WaterType.LATE)))
        assertEquals(WaterType.LATE, marks.recordAt("2026-11-17", 3)?.status)
    }

    @Test
    fun `同一天同节次的别的课不算`() {
        val marks = attendanceMarksOf(listOf(rec(name = "大学物理")))
        assertNull(marks.recordAt("2026-11-17", 3, 4, "高等数学"))
        assertEquals(WaterType.NORMAL, marks.recordAt("2026-11-17", 3, 4, "大学物理")?.status)
    }

    @Test
    fun `连上多节时容忍流水只报后一节`() {
        // 课表 3-4 节，流水记在 4 节：允许落在区间内
        val marks = attendanceMarksOf(listOf(rec(startTime = 4, endTime = 4, name = "高等数学")))
        assertEquals(WaterType.NORMAL, marks.recordAt("2026-11-17", 3, 4, "高等数学")?.status)
        // 但区间外的不算
        assertNull(marks.recordAt("2026-11-17", 3, 3, "高等数学"))
    }

    @Test
    fun `日期为空或节次非法时不给记录`() {
        val marks = attendanceMarksOf(listOf(rec()))
        assertNull(marks.recordAt("", 3))
        assertNull(marks.recordAt("2026-11-17", 0))
    }

    @Test
    fun `日期带时间后缀也认`() {
        val marks = attendanceMarksOf(listOf(rec(date = "2026-11-17 08:00:00")))
        assertEquals(WaterType.NORMAL, marks.recordAt("2026-11-17", 3, 4, "高等数学")?.status)
    }

    @Test
    fun `课程号优先：课名写法不一致也认得出是同一门`() {
        // 两个系统对括号的全角半角、班号后缀的处理不一致，逐字比会把同一门课判成两门
        val marks = attendanceMarksOf(listOf(rec(name = "高等数学（甲）", code = "MATH100101-05")))
        assertEquals(
            WaterType.NORMAL,
            marks.recordAt("2026-11-17", 3, 4, courseName = "高等数学(甲)", courseCode = "MATH100101-05")?.status,
            "课程号逐字相同就该命中，不用去看课名",
        )
        assertEquals(
            WaterType.NORMAL,
            marks.recordAt("2026-11-17", 3, 4, courseName = "随便什么名字", courseCode = "MATH100101-05")?.status,
            "有课程号时课名不参与判定",
        )
    }

    @Test
    fun `课程号只带上不同班号后缀也算同一门`() {
        val marks = attendanceMarksOf(listOf(rec(code = "MATH100101-05")))
        assertEquals(
            WaterType.NORMAL,
            marks.recordAt("2026-11-17", 3, 4, courseName = "高等数学", courseCode = "MATH100101-01")?.status,
        )
    }

    @Test
    fun `课名只统一全角半角与空白，不剥括号后缀`() {
        // 全角半角括号、多余空格都算同一种写法
        val marks = attendanceMarksOf(listOf(rec(name = "大学物理（实验）")))
        assertEquals(
            WaterType.NORMAL,
            marks.recordAt("2026-11-17", 3, 4, courseName = " 大学物理(实验) ")?.status,
        )
        // 但**不**做「剥掉结尾括号」那种归一化：「大学物理」和「大学物理（实验）」是两门课，
        // 同一节课两门都上时，认错就等于把讲座的考勤显示在实验课上
        assertNull(marks.recordAt("2026-11-17", 3, 4, courseName = "大学物理"))
        assertNull(marks.recordAt("2026-11-17", 3, 4, courseName = "大学物理实验"))
    }
}
