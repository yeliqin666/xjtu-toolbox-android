package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.lms.LmsDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「接下来」构建函数的契约单测（plan2 §5.4）：过滤已提交、过滤过期/超窗、
 * 考试只取 SOON（1-3 天），今天截止的作业不重复出现（已经在时间轴本体里），按时间排序。
 */
class UpcomingTest {

    private val now: LocalDateTime = LocalDateTime.of(2027, 1, 10, 9, 0)

    private fun exam(date: String, name: String, time: String = "14:30-16:30") =
        ExamItem(courseName = name, courseCode = name, examDate = date, examTime = time, location = "教室", seatNumber = "")

    private fun due(
        courseName: String = "课程",
        title: String = "作业",
        deadline: String,
        submitted: Boolean = false,
    ) = LmsDue(courseId = 1, courseName = courseName, activityId = title.hashCode(), title = title, deadline = deadline, submitted = submitted, fetchedAt = 0)

    @Test
    fun 只取一到三天内的考试() {
        val tomorrow = exam("2027-01-11", "明天考")
        val today = exam("2027-01-10", "今天考") // 已在时间轴本体，不该出现在接下来
        val farAway = exam("2027-01-20", "很远的考试")
        val result = buildUpcoming(listOf(tomorrow, today, farAway), emptyList(), now)
        assertEquals(listOf("明天考"), result.map { it.title })
    }

    @Test
    fun 过滤已提交的作业() {
        val submitted = due(title = "已交", deadline = "2027-01-12T16:00:00Z", submitted = true)
        val notSubmitted = due(title = "没交", deadline = "2027-01-12T16:00:00Z", submitted = false)
        val result = buildUpcoming(emptyList(), listOf(submitted, notSubmitted), now)
        assertEquals(listOf("[课程] 没交"), result.map { it.title })
    }

    @Test
    fun 过滤七天以外的作业() {
        val withinWindow = due(title = "窗口内", deadline = "2027-01-16T09:00:00Z")
        val beyondWindow = due(title = "太远了", deadline = "2027-01-25T09:00:00Z")
        val result = buildUpcoming(emptyList(), listOf(withinWindow, beyondWindow), now)
        assertEquals(listOf("[课程] 窗口内"), result.map { it.title })
    }

    @Test
    fun 今天截止的作业不出现在接下来() {
        // 用系统默认时区把 now 当天晚上 8 点转成 Instant，跟 buildUpcoming 内部判断
        // 「是不是今天」用的是同一个时区，不受测试机时区影响。
        val deadline = now.withHour(20).atZone(ZoneId.systemDefault()).toInstant().toString()
        val today = due(title = "今天交", deadline = deadline)
        val result = buildUpcoming(emptyList(), listOf(today), now)
        assertTrue(result.none { it.title.contains("今天交") })
    }

    @Test
    fun 考试和作业按时间混排() {
        val laterExam = exam("2027-01-13", "考试")
        val soonerHomework = due(title = "作业", deadline = "2027-01-11T09:00:00Z")
        val result = buildUpcoming(listOf(laterExam), listOf(soonerHomework), now)
        assertEquals(listOf("[课程] 作业", "考试"), result.map { it.title })
    }
}
