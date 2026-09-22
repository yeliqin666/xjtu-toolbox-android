package com.xjtu.toolbox.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreReportTermTest {

    private fun code(text: String) = ScoreReportApi.termCodeFromHeading(text)

    @Test
    fun `普通学期照旧`() {
        assertEquals("2024-2025-1", code("2024-2025学年 第一学期"))
        assertEquals("2024-2025-2", code("2024-2025学年 第二学期"))
        assertEquals("2024-2025-1", code("2024-2025 学年 1 学期"))
    }

    /** 成绩报表里小学期的标题就是这么写的；以前认不出，小学期的课被并进了春季学期。 */
    @Test
    fun `夏季小学期单独成组`() {
        assertEquals("2025-2026-3", code("2025-2026学年 夏季小学期"))
        assertEquals("2024-2025-3", code("2024-2025学年 暑期学期"))
        assertEquals("2024-2025-3", code("2024-2025学年 短学期"))
    }

    /** 暑假重修、补考出的成绩单独一组，不并进上一学期。 */
    @Test
    fun `暑假单独成组`() {
        assertEquals("2024-2025-4", code("2024-2025学年 暑假"))
    }

    @Test
    fun `不是学期标题返回 null`() {
        assertNull(code("课程"))
        assertNull(code("学分 成绩"))
    }
}
