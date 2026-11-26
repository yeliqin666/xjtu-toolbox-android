package com.xjtu.toolbox.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

class CompactTermNameTest {
    @Test
    fun `学年缩成两位，学期名保留`() {
        assertEquals("2026-27 第一学期", compactTermName("2026-2027学年 第一学期"))
        assertEquals("2025-26 第一学期", compactTermName("2025-2026 学年第一学期"))
        assertEquals("2025-26 夏季小学期", compactTermName("2025-2026学年 夏季小学期"))
        assertEquals("2025-2026-1", compactTermName("2025-2026-1"))
    }
}
