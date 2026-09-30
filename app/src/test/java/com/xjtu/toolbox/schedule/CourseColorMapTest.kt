package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseColorMapTest {

    private val names = listOf("高等数学", "大学物理", "线性代数", "大学英语", "程序设计", "思想道德与法治", "工程图学")

    @Test
    fun `同一批课程的默认色互不相同`() {
        val map = courseColorMap(names)
        assertEquals(names.size, map.values.toSet().size)
    }

    @Test
    fun `结果与输入顺序无关`() {
        assertEquals(courseColorMap(names), courseColorMap(names.reversed()))
    }

    @Test
    fun `没有撞色时和单门课的默认色一致`() {
        // 单独一门课不会撞色：思源学堂只认识单门课，取到的必须与课表同色
        for (n in names) assertEquals(defaultCourseColor(n), courseColorMap(listOf(n)).getValue(n))
    }

    @Test
    fun `超过色板数量才允许重复`() {
        val many = (1..COURSE_COLORS.size + 3).map { "课程$it" }
        val map = courseColorMap(many)
        assertEquals(many.size, map.size)
        assertTrue(map.values.toSet().size == COURSE_COLORS.size)
    }
}
