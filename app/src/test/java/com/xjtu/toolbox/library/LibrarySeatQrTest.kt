package com.xjtu.toolbox.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySeatQrTest {

    @Test
    fun `parses desk qr exactly as printed`() {
        val qr = LibrarySeatQr.parse("http://rg.lib.xjtu.edu.cn:8086/qavail/?seat=056&sp=north4southwest")!!
        // 座位号原样保留前导零，这就是预约接口的 kid
        assertEquals("056", qr.seat)
        assertEquals("north4southwest", qr.areaCode)
        assertEquals("北楼四层西南侧", qr.areaName)
        assertEquals(LibraryCampus.XINGQING, qr.area?.campus)
    }

    @Test
    fun `accepts letter seats, https and trailing spaces`() {
        assertEquals("D004", LibrarySeatQr.parse(" https://rg.lib.xjtu.edu.cn:8086/qavail/?seat=D004&sp=north2east \n")?.seat)
        assertEquals("01", LibrarySeatQr.parse("http://rg.lib.xjtu.edu.cn:8086/qavail?sp=inno1central&seat=01")?.seat)
    }

    @Test
    fun `rejects other links and unsafe values`() {
        assertNull(LibrarySeatQr.parse("https://login.xjtu.edu.cn/cas/qrcode?uuid=abc"))
        assertNull(LibrarySeatQr.parse("http://evil.example/qavail/?seat=056&sp=north4southwest"))
        assertNull(LibrarySeatQr.parse("http://rg.lib.xjtu.edu.cn:8086/seat/?kid=056&sp=north4southwest"))
        assertNull(LibrarySeatQr.parse("http://rg.lib.xjtu.edu.cn:8086/qavail/?seat=&sp=north4southwest"))
        assertNull(LibrarySeatQr.parse("http://rg.lib.xjtu.edu.cn:8086/qavail/?seat=056%26kid%3D1&sp=north4southwest"))
        assertNull(LibrarySeatQr.parse("随便一段文字"))
    }

    @Test
    fun `campus of areas incl unknown ones`() {
        assertEquals(LibraryCampus.YANTA, LibraryQrArea.campusOf("yanta2floor165"))
        assertEquals("雁塔 2楼165-236", LibraryQrArea.byCode("yanta2floor165")?.displayName)
        assertEquals(LibraryCampus.INNOVATION, LibraryQrArea.campusOf("inno3newarea"))
        assertEquals(LibraryCampus.XINGQING, LibraryQrArea.campusOf("south3middle"))
        assertEquals(33, LibraryQrArea.entries.size)
    }

    @Test
    fun `parses qavail status page`() {
        // 摘自 2026-09-22 的 /qavail/ 页面：h3 没闭合，脚本里也有「空闲」字样，不能误认
        val booked = """
            <script>btlist.innerHTML='抱歉，当前区域暂无空闲座位';</script>
            <div class="col-md-9 cta-contents">
            <h4>北楼四层西南侧&nbsp;</h4>
            <h4>座位号：056&nbsp;</h4>
            </div><div class="col-md-3 cta-button"><center>
            <h4>预约状态:</h4>
            <h3>座位已被预约<h3>
        """.trimIndent()
        val s = LibrarySeatAvailability.parse(booked) as LibrarySeatStatus.Known
        assertEquals("座位已被预约", s.statusText)
        assertEquals(false, s.isFree)

        val free = LibrarySeatAvailability.parse(booked.replace("座位已被预约", "座位空闲")) as LibrarySeatStatus.Known
        assertEquals(true, free.isFree)

        // 座位号不存在时页面没有座位信息
        assertEquals(LibrarySeatStatus.NotFound, LibrarySeatAvailability.parse("<html><body><h4>预约状态:</h4></body></html>"))
    }
}
