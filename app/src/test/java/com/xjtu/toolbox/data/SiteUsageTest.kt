package com.xjtu.toolbox.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteUsageTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `常用的排前面，久不用的会被新近常用的超过`() {
        var e = emptyMap<String, SiteUsage.Entry>()
        repeat(6) { e = SiteUsage.record(e, "jwxt", 0L) }
        e = SiteUsage.record(e, "library", 0L)
        assertEquals(listOf("jwxt", "library"), SiteUsage.top(e, 2, 0L))
        // 三周后连用三次图书馆：教务衰减到 6/8，图书馆约 3.1
        repeat(3) { e = SiteUsage.record(e, "library", 21 * day) }
        assertEquals(listOf("library", "jwxt"), SiteUsage.top(e, 2, 21 * day))
    }

    @Test
    fun `编解码往返，坏数据跳过`() {
        val e = SiteUsage.record(SiteUsage.record(emptyMap(), "ssn", 1000L), "coupon", 2000L)
        assertEquals(e.keys, SiteUsage.decode(SiteUsage.encode(e)).keys)
        assertEquals(setOf("a"), SiteUsage.decode("a:1.5:10,broken,b:x:1,:2:3").keys)
    }
}
