package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.schedule.ExamCountdown.ExamPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 考试状态的契约单测（plan2 第 0 步）。日程页、屁岱气泡、今日栏都按这里的规则判断，
 * 规则一变，这几处会一起变，所以先把边界钉死。
 */
class ExamCountdownTest {

    private val today: LocalDate = LocalDate.of(2027, 1, 10)

    private fun exam(date: String, time: String = "14:30-16:30", name: String = "高等数学") =
        ExamItem(
            courseName = name,
            courseCode = "MATH001",
            examDate = date,
            examTime = time,
            location = "主楼A-101",
            seatNumber = "12",
        )

    private fun at(h: Int, m: Int = 0): LocalDateTime = today.atTime(h, m)

    @Test
    fun 日期已过的是已结束() {
        assertEquals(ExamPhase.ENDED, ExamCountdown.phaseOf(exam("2027-01-09"), at(9)))
    }

    @Test
    fun 今天还没考完的是今天() {
        assertEquals(ExamPhase.TODAY, ExamCountdown.phaseOf(exam("2027-01-10"), at(15)))
    }

    @Test
    fun 今天已过结束时刻的是已结束() {
        assertEquals(ExamPhase.ENDED, ExamCountdown.phaseOf(exam("2027-01-10"), at(16, 31)))
    }

    @Test
    fun 今天但时间解析不出来的不提前判结束() {
        // 宁可一直标「今天」到半夜，也不能把还没考的压暗成「已结束」。
        assertEquals(ExamPhase.TODAY, ExamCountdown.phaseOf(exam("2027-01-10", time = "待定"), at(23)))
    }

    @Test
    fun 一到三天内是快考了_第四天是还早() {
        assertEquals(ExamPhase.SOON, ExamCountdown.phaseOf(exam("2027-01-11"), at(9)))
        assertEquals(ExamPhase.SOON, ExamCountdown.phaseOf(exam("2027-01-13"), at(9)))
        assertEquals(ExamPhase.LATER, ExamCountdown.phaseOf(exam("2027-01-14"), at(9)))
    }

    @Test
    fun 没有日期的单独归一类() {
        assertEquals(ExamPhase.UNDATED, ExamCountdown.phaseOf(exam(""), at(9)))
        assertEquals(ExamPhase.UNDATED, ExamCountdown.phaseOf(exam("下周"), at(9)))
    }

    @Test
    fun 年月日写法也认() {
        assertEquals(ExamPhase.SOON, ExamCountdown.phaseOf(exam("2027年1月12日"), at(9)))
    }

    @Test
    fun 时间段的各种连接符都认() {
        for (t in listOf("14:30-16:30", "14:30~16:30", "14：30—16：30", "14:30至16:30")) {
            assertEquals(t, LocalTime.of(16, 30), ExamCountdown.endTimeOf(exam("2027-01-10", time = t)))
            assertEquals(t, LocalTime.of(14, 30), ExamCountdown.startTimeOf(exam("2027-01-10", time = t)))
        }
        // 只写了一个时间：有开考时刻，没有结束时刻（不猜考多久）。
        assertEquals(LocalTime.of(9, 0), ExamCountdown.startTimeOf(exam("2027-01-10", time = "9:00")))
        assertNull(ExamCountdown.endTimeOf(exam("2027-01-10", time = "9:00")))
    }

    @Test
    fun 下一场会跳过今天已经考完的() {
        val done = exam("2027-01-10", time = "08:30-10:30", name = "上午那场")
        val tomorrow = exam("2027-01-11", name = "明天那场")
        val next = ExamCountdown.next(listOf(done, tomorrow), at(11))
        assertEquals("明天那场", next?.exam?.courseName)
        assertEquals(1, next?.daysLeft)
    }

    @Test
    fun 下一场在今天还没考完时就是今天这场() {
        val later = exam("2027-01-10", time = "14:30-16:30", name = "下午那场")
        val next = ExamCountdown.next(listOf(later, exam("2027-01-11")), at(11))
        assertEquals("下午那场", next?.exam?.courseName)
        assertEquals(0, next?.daysLeft)
    }

    @Test
    fun 按别的日期查时按那天零点算() {
        // 旧入口传的不是今天：那天的考试一场都还没考。
        val e = exam("2027-01-20", time = "08:30-10:30")
        assertEquals(0, ExamCountdown.next(listOf(e), LocalDate.of(2027, 1, 20))?.daysLeft)
    }
}
