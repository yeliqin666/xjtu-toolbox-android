package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.ScheduleRow
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * :core 的课表口径测试。
 *
 * 这些断言之所以值钱，是因为它们跑在 **CI 门禁**里（`wasmJsNodeTest` + `jvmTest`）：
 * 周次展开与学期日期换算一旦在某端漂移，这里先红，而不是等到 Web 与 Android 显示两张不同课表。
 *
 * ⚠️ 口径以 J1900 的 `jwxt2caldav.js::parseZcmc` 为准（交接文档 §7）：同一个 `ZCMC`
 * 在日历和 App 里必须展开成同一串周次，否则「同一个人的日历与课表互相打脸」。
 */
class CourseWeeksTest {

    @Test
    fun singleWeekAndRange() {
        assertEquals(listOf(16), parseWeeksText("16周"))
        assertEquals(listOf(1, 2, 3), parseWeeksText("1-3周"))
    }

    @Test
    fun oddEvenSegmentsArePerSegment() {
        // 单双周是**逐段**的属性，不是整串的属性 —— 上游最常见的形状
        assertEquals(listOf(5, 7), parseWeeksText("5-7周(单)"))
        assertEquals(listOf(6), parseWeeksText("5-7周(双)")) // 双周取偶数：5-7 里只剩 6
        assertEquals(
            listOf(1, 2, 3, 5, 7, 9, 10, 11, 12, 14, 15, 16),
            parseWeeksText("1-3周,5-7周(单),9-12周,14-16周"), // 实测报文里的原样
        )
    }

    @Test
    fun garbageIsSkippedNotThrown() {
        // 与权威实现的一处有意差异：解析不了的段落**跳过**而不是抛异常（这里跑在用户面前）
        assertTrue(parseWeeksText(null).isEmpty())
        assertTrue(parseWeeksText("").isEmpty())
        assertTrue(parseWeeksText("待定").isEmpty())
        assertTrue(parseWeeksText("17-15周").isEmpty())
        assertEquals(listOf(1, 2), parseWeeksText("1-2周, 待定"))
    }

    @Test
    fun termStartDrivesWeekMath() {
        val termStart = LocalDate.parse("2026-09-14") // 实测 campus-api：2026-2027-1 的第 1 周周一
        assertEquals(1, termStart.weekOf("2026-09-14")) // 开学当天
        assertEquals(1, termStart.weekOf("2026-09-20")) // 第 1 周周日
        assertEquals(2, termStart.weekOf("2026-09-21")) // 第 2 周周一
        assertEquals(1, termStart.weekOf("2026-09-01")) // 开学前 → 夹到第 1 周
        assertEquals(21, termStart.weekOf("2027-02-01")) // 由调用方按 totalWeeks 再夹
        // dateOf 的 week 与 weekOf 一样是 **1-based**。这里原先是 2026-09-24 / 2026-10-01，
        // 比正确值整整多一周（注释写「第 1 周周四」却给了第 2 周的日期），
        // 而且与上面 1-based 的 weekOf 断言自相矛盾。
        // 事实：2026-09-14 是**周一**（isoweekday=1，python 验过），所以第 1 周周四是 09-17。
        assertEquals(LocalDate.parse("2026-09-17"), termStart.dateOf(1, 4)) // 第 1 周周四
        assertEquals(LocalDate.parse("2026-09-24"), termStart.dateOf(2, 4)) // 第 2 周周四
    }

    /**
     * 拿**真实报文形状**（照 campus-api 实测响应截取：`"3"` 这种字符串数字、null 列）
     * 走一遍上游行 → 共享模型的收敛。
     */
    @Test
    fun realShapedRowBecomesCourseSlot() {
        val slot = ScheduleRow(
            courseName = "国际学术交流英语",
            teacher = "邵娟",
            classroom = "外文楼A-612",
            dayOfWeek = "4",
            startSection = "3",
            endSection = "4",
            weeksText = "1-3周,5-7周(单),9-12周,14-16周",
            classId = "202620271ENGL20501207",
        ).toCourseSlot()

        assertEquals("国际学术交流英语", slot.courseName)
        assertEquals(4, slot.dayOfWeek)
        assertEquals(3, slot.startSection)
        assertEquals(4, slot.endSection)
        assertEquals(listOf(1, 2, 3, 5, 7, 9, 10, 11, 12, 14, 15, 16), slot.weeks)

        // 缺关键列的行收敛成 0/空，由调用方决定丢不丢 —— 不让整表炸掉
        val broken = ScheduleRow(dayOfWeek = null, startSection = "3", weeksText = "1周").toCourseSlot()
        assertEquals(0, broken.dayOfWeek)
        assertTrue(broken.weeks.isNotEmpty())
    }
}
