package com.xjtu.toolbox.notification

import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.CampusZoneId
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class DynamicSchedulePlanTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private fun now(time: String, date: LocalDate = monday) = ZonedDateTime.parse("${date}T$time+08:00[Asia/Shanghai]")
    private fun course(name: String = "高等数学", start: Int = 1, end: Int = 2) = CourseItem(
        courseName = name, location = "中二-1200", weekBits = "11", dayOfWeek = 1,
        startSection = start, endSection = end, courseCode = name,
    )
    private fun agenda(start: Int = 600, end: Int = 630) = course("小组讨论").copy(
        courseCode = "custom_1", courseType = "日程", startMinuteOfDay = start, endMinuteOfDay = end,
    )

    @Test fun `课前展示下一项 精确到秒排开始边界`() {
        val plan = planDynamicSchedule(listOf(course(), agenda()), monday, now("07:59:59"))
        assertEquals("高等数学", plan.current!!.course.courseName)
        assertFalse(plan.ongoing)
        assertEquals(now("08:00:00").toInstant().toEpochMilli(), plan.nextRefreshAt)
        assertEquals(1, plan.coursesLeft)
        assertEquals(1, plan.agendasLeft)
    }

    @Test fun `开始时切到进行中 结束时切到下一项且更新数量`() {
        val items = listOf(course(), agenda())
        val during = planDynamicSchedule(items, monday, now("08:00:00"))
        assertTrue(during.ongoing)
        assertEquals(now("09:50:00").toInstant().toEpochMilli(), during.nextRefreshAt)
        val after = planDynamicSchedule(items, monday, now("09:50:00"))
        assertEquals("小组讨论", after.current!!.course.courseName)
        assertFalse(after.ongoing)
        assertEquals(0, after.coursesLeft)
        assertEquals(1, after.agendasLeft)
    }

    @Test fun `重叠安排先显示最早结束的进行项并保留其他安排`() {
        val short = agenda(510, 540)
        val plan = planDynamicSchedule(listOf(course(), short), monday, now("08:30:00"))
        assertEquals(short, plan.current!!.course)
        assertEquals(now("09:00:00").toInstant().toEpochMilli(), plan.nextRefreshAt)
        assertEquals("高等数学", planDynamicSchedule(listOf(course(), short), monday, now("09:00:00")).current!!.course.courseName)
    }

    @Test fun `节假日不推教务课程 自建日程与自建课程照常`() {
        val customClass = course("自建实验课").copy(courseCode = "custom_2", courseType = "自定义")
        val plan = planDynamicSchedule(listOf(course(), agenda(), customClass), monday, now("07:00:00"), setOf(monday))
        assertEquals(customClass, plan.current!!.course)
        assertEquals(1, plan.coursesLeft)
        assertEquals(1, plan.agendasLeft)
    }

    @Test fun `分钟级日程不用课节时间 结束为午夜仍正确收起`() {
        val plan = planDynamicSchedule(listOf(agenda(1430, 1440)), monday, now("23:51:00"))
        assertTrue(plan.ongoing)
        assertEquals(1430, plan.current!!.startMinute)
        assertEquals(now("00:00:00", monday.plusDays(1)).toInstant().toEpochMilli(), plan.nextRefreshAt)
    }

    @Test fun `夏季午后课程按夏季作息 冬季不延后`() {
        val summerMonday = LocalDate.of(2026, 6, 1)
        val item = course(start = 5, end = 6)
        val summer = planDynamicSchedule(listOf(item), summerMonday, now("13:00:00", summerMonday))
        val winter = planDynamicSchedule(listOf(item), monday, now("13:00:00"))
        assertEquals(870, summer.current!!.startMinute)
        assertEquals(840, winter.current!!.startMinute)
    }

    @Test fun `缺学期日期 已结束 错周次 错星期 开学前均不猜课程`() {
        val item = course()
        assertNull(planDynamicSchedule(listOf(item), null, now("07:00:00")).current)
        assertNull(planDynamicSchedule(listOf(item), monday, now("09:50:00")).current)
        assertNull(planDynamicSchedule(listOf(item.copy(weekBits = "01")), monday, now("07:00:00")).current)
        assertNull(planDynamicSchedule(listOf(item.copy(dayOfWeek = 2)), monday, now("07:00:00")).current)
        assertNull(planDynamicSchedule(listOf(item), monday.plusWeeks(1), now("07:00:00")).current)
    }

    @Test fun `今日无剩余时收起 跨天仍安排下一次检查`() {
        val plan = planDynamicSchedule(emptyList(), monday, now("23:59:59"))
        assertNull(plan.current)
        assertEquals(now("00:00:00", monday.plusDays(1)).toInstant().toEpochMilli(), plan.nextRefreshAt)
    }

    @Test fun `设备时区不同也按校园时间选择课程`() {
        val campusNow = now("08:30:00")
        val utcNow = campusNow.withZoneSameInstant(java.time.ZoneOffset.UTC)
        assertEquals(
            planDynamicSchedule(listOf(course()), monday, campusNow),
            planDynamicSchedule(listOf(course()), monday, utcNow),
        )
        assertEquals(CampusZoneId, campusNow.zone)
    }
}
