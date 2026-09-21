package com.xjtu.toolbox.jiaocai

import org.junit.Assert.assertEquals
import org.junit.Test

class IsbnTest {

    @Test
    fun `strips hyphens`() {
        assertEquals("9787040396638", normalizeIsbn("978-7-04-039663-8"))
    }

    @Test
    fun `strips spaces`() {
        assertEquals("9787040396638", normalizeIsbn("978 7 04 039663 8"))
    }

    @Test
    fun `uppercases the check digit when it is X`() {
        assertEquals("710210031X", normalizeIsbn("7-102-10031-x"))
    }

    @Test
    fun `isbn13 passes through unchanged apart from separators`() {
        assertEquals("9787115546081", normalizeIsbn("978-7-115-54608-1"))
    }

    @Test
    fun `already clean input is unaffected`() {
        assertEquals("9787040396638", normalizeIsbn("9787040396638"))
    }
}
