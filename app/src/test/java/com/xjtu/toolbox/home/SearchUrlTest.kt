package com.xjtu.toolbox.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchUrlTest {
    @Test
    fun keepsExplicitScheme() {
        assertEquals("http://bkkq.xjtu.edu.cn/a?b=1", searchUrlOf(" http://bkkq.xjtu.edu.cn/a?b=1 "))
        assertEquals("HTTPS://example.com", searchUrlOf("HTTPS://example.com"))
        assertEquals("http://localhost:8080/x", searchUrlOf("http://localhost:8080/x"))
    }

    @Test
    fun addsHttpsToBareDomains() {
        assertEquals("https://example.com", searchUrlOf("example.com"))
        assertEquals("https://org.xjtu.edu.cn/openplatform/", searchUrlOf("org.xjtu.edu.cn/openplatform/"))
        assertEquals("https://example.com:8443/p?q=1#h", searchUrlOf("example.com:8443/p?q=1#h"))
        assertEquals("https://10.6.0.1/login", searchUrlOf("10.6.0.1/login"))
    }

    @Test
    fun rejectsOrdinarySearchText() {
        for (text in listOf("", "  ", "课表", "gpa 怎么算", "http://", "3.14", "空教室.com", "a.b", "foo bar.com", "xjtu"))
            assertNull(text, searchUrlOf(text))
    }
}
