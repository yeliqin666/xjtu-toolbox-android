package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 考试列表排序的契约单测（plan2 §1.3④）：未结束的按时间正序在前、日期待定的排最后，
 * 已结束的单独收着并按日期倒序（最近考完的排最上面）。
 */
class ExamListTest {

    private val today: LocalDate = LocalDate.of(2027, 1, 10)
    private fun at(h: Int) = today.atTime(h, 0)

    private fun exam(date: String, name: String, time: String = "14:30-16:30") =
        ExamItem(
            courseName = name,
            courseCode = name,
            examDate = date,
            examTime = time,
            location = "主楼A-101",
            seatNumber = "1",
        )

    @Test
    fun 未结束的按日期正序_日期待定排最后() {
        val soonFar = exam("2027-01-13", "远一点")
        val soonNear = exam("2027-01-11", "近一点")
        val undated = exam("", "没日期")
        val data = sortExamsForList(listOf(soonFar, undated, soonNear), at(9))
        assertEquals(listOf("近一点", "远一点", "没日期"), data.active.map { it.courseName })
        assertEquals(0, data.ended.size)
    }

    @Test
    fun 已结束的单独收着_按日期倒序() {
        val oldest = exam("2027-01-05", "最早考完")
        val newest = exam("2027-01-09", "最近考完")
        val data = sortExamsForList(listOf(oldest, newest), at(9))
        assertEquals(listOf("最近考完", "最早考完"), data.ended.map { it.courseName })
        assertEquals(0, data.active.size)
    }

    @Test
    fun 今天已经考完的算已结束() {
        val morning = exam("2027-01-10", "上午那场", time = "08:00-09:00")
        val data = sortExamsForList(listOf(morning), at(10))
        assertEquals(1, data.ended.size)
        assertEquals(0, data.active.size)
    }

    @Test
    fun 按课程名_日期_时间去重() {
        val a = exam("2027-01-11", "重复课")
        val b = exam("2027-01-11", "重复课")
        val data = sortExamsForList(listOf(a, b), at(9))
        assertEquals(1, data.total)
    }

    @Test
    fun 全部考完时_active为空_ended有内容() {
        val data = sortExamsForList(listOf(exam("2027-01-01", "早考完了")), at(9))
        assertEquals(0, data.active.size)
        assertEquals(1, data.ended.size)
    }
}
