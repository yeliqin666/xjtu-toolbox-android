package com.xjtu.toolbox.schedule

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseColorsHexTest {

    @Test
    fun `认三种写法，其余不认`() {
        assertEquals(Color(0xFF1565C0), CourseColors.parseHex("#1565C0"))
        assertEquals(Color(0xFF1565C0), CourseColors.parseHex(" 1565c0 "))
        assertEquals(Color(0xFFAABBCC), CourseColors.parseHex("#abc"))
        for (bad in listOf("", "#12345", "#1234567", "#GGGGGG", "red")) assertNull(bad, CourseColors.parseHex(bad))
    }

    @Test
    fun `转回 hex 去掉透明度`() {
        assertEquals("#1565C0", CourseColors.toHex(Color(0xFF1565C0)))
        assertEquals("#1565C0", CourseColors.toHex(Color(0x801565C0)))
    }
}
