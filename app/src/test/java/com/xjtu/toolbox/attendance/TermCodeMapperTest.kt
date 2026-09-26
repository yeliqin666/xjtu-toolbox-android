package com.xjtu.toolbox.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

class TermCodeMapperTest {

    @Test
    fun `first semester maps to dash-1`() {
        assertEquals("2026-2027-1", TermCodeMapper.termCodeOf("2026-2027", "第一学期"))
    }

    @Test
    fun `second semester maps to dash-2`() {
        assertEquals("2026-2027-2", TermCodeMapper.termCodeOf("2026-2027", "第二学期"))
    }

    @Test
    fun `third and fourth semester (short summer terms) map correctly`() {
        assertEquals("2026-2027-3", TermCodeMapper.termCodeOf("2026-2027", "第三学期"))
        assertEquals("2026-2027-4", TermCodeMapper.termCodeOf("2026-2027", "第四学期"))
    }

    @Test
    fun `blank year yields blank code`() {
        assertEquals("", TermCodeMapper.termCodeOf("", "第一学期"))
    }

    @Test
    fun `unrecognized semester name yields blank code`() {
        assertEquals("", TermCodeMapper.termCodeOf("2026-2027", "未知学期"))
    }
}
