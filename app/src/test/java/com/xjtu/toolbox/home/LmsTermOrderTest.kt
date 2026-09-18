package com.xjtu.toolbox.home

import com.xjtu.toolbox.lms.LmsAcademicYear
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.lms.LmsSemester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「思源」卡片选学期的回归测试。
 *
 * 数据形状取自 2026-09-18 抓到的 `/api/my-courses` 真实响应：34 门课，
 * `academic_year.sort` **恒为 0**，`semester.sort` 是「越旧越大」（2025-1=5 > 2025-2=4 > 2026-1=1），
 * `semester.name` / `real_name` 全是 null。旧公式 `yearSort * 1000 + semSort` 因此在真实数据上
 * 会选中 **2025-1（最旧学期）**，首页于是长期显示一年前的作业。
 */
class LmsTermOrderTest {

    private fun course(
        name: String,
        semesterCode: String,
        yearCode: String,
        semesterSort: Int,
        yearSort: Int = 0,
    ) = LmsCourseSummary(
        id = name.hashCode(),
        name = name,
        academicYear = LmsAcademicYear(code = yearCode, name = yearCode, sort = yearSort),
        semester = LmsSemester(code = semesterCode, sort = semesterSort),
    )

    /** 真实账号的学期分布（课程数按实测：11 / 13 / 9）+ 1 门学期元数据为 null 的课。 */
    private val realShaped = buildList {
        repeat(11) { add(course("当前学期$it", "2026-1", "2026", semesterSort = 1)) }
        repeat(13) { add(course("上学年春季$it", "2025-2", "2025", semesterSort = 4)) }
        repeat(9) { add(course("上学年秋季$it", "2025-1", "2025", semesterSort = 5)) }
        add(LmsCourseSummary(id = -1, name = "学期元数据为 null 的课"))
    }

    @Test
    fun order_newerTermIsGreater() {
        val fall2026 = lmsTermOrder("2026-1", "2026")!!
        val spring2025 = lmsTermOrder("2025-2", "2025")!!
        val autumn2025 = lmsTermOrder("2025-1", "2025")!!
        assertTrue(fall2026 > spring2025)
        assertTrue(spring2025 > autumn2025)
    }

    @Test
    fun order_usesSemesterCodeYear() {
        // semester.code 自带学年，比 academic_year.code 更具体
        assertEquals(202601, lmsTermOrder("2026-1", "1999"))
        assertEquals(202502, lmsTermOrder("2025-2", "1999"))
    }

    @Test
    fun order_fallsBackToAcademicYearCode() {
        assertEquals(202600, lmsTermOrder(null, "2026"))
        assertEquals(202600, lmsTermOrder("", "2026"))
        // 学年 code 也可能是区间写法
        assertEquals(202600, lmsTermOrder(null, "2026-2027"))
    }

    @Test
    fun order_unknownReturnsNull() {
        assertNull(lmsTermOrder(null, null))
        assertNull(lmsTermOrder("", ""))
        assertNull(lmsTermOrder("abc", "xyz"))
    }

    @Test
    fun pick_realData_selectsCurrentTerm() {
        val picked = newestTermCourses(realShaped, max = 6)
        assertEquals(6, picked.size)
        assertEquals(setOf("2026-1"), picked.map { it.semester.code }.toSet())
        // 学期元数据为 null 的课不该混进来
        assertTrue(picked.none { it.id == -1 })
    }

    @Test
    fun pick_oldFormulaWouldHaveSelectedOldestTerm() {
        // 记录这个 bug 的成因：真实数据上旧公式命中 2025-1，而不是 2026-1
        val oldNewest = realShaped.maxOf { it.academicYear.sort * 1000 + it.semester.sort }
        val oldPicked = realShaped.filter { it.academicYear.sort * 1000 + it.semester.sort == oldNewest }
        assertEquals(setOf("2025-1"), oldPicked.map { it.semester.code }.toSet())
    }

    @Test
    fun pick_respectsMax() {
        assertEquals(3, newestTermCourses(realShaped, max = 3).size)
    }

    @Test
    fun pick_noTermMetadataAtAll_keepsCourses() {
        // 一门都认不出学期时不能把首页清空：退化成不筛
        val anonymous = listOf(
            LmsCourseSummary(id = 1, name = "A"),
            LmsCourseSummary(id = 2, name = "B"),
        )
        assertEquals(listOf(1, 2), newestTermCourses(anonymous, max = 6).map { it.id })
    }

    @Test
    fun pick_emptyList() {
        assertTrue(newestTermCourses(emptyList(), max = 6).isEmpty())
    }
}
