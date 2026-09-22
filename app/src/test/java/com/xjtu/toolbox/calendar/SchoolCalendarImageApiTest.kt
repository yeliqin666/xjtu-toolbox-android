package com.xjtu.toolbox.calendar

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class SchoolCalendarImageApiTest {

    @Test
    fun `parses dean calendar page in page order and infers years`() {
        // 摘自 dean.xjtu.edu.cn/xxfw/xl.htm 的结构：纯文字链接、logo、重复链接都要跳过
        val html = """
            <a href="../index.htm"><img src="../images/logo.png"></a>
            <a href="../2026-2027.jpg" target="_blank"><img src="/__local/a.jpg"></a>
            <a href="../2025-2026-new.png" target="_blank"><img src="/__local/b.png"></a>
            <a href="../2025-2026-new.png" target="_blank">文字链接</a>
            <a href="http://jwc.xjtu.edu.cn/__local/6/B2/x.jpg"><img src="/__local/c.jpg"></a>
            <a href="http://jwc.xjtu.edu.cn/__local/D/AA/y.png" title="2022-2023学年校历"><img src="/__local/d.png"></a>
        """.trimIndent()
        val images = SchoolCalendarImageApi.parseImages(Jsoup.parse(html, SchoolCalendarImageApi.PAGE_URL))
        assertEquals(
            listOf(
                SchoolCalendarImage("2026-2027", "https://dean.xjtu.edu.cn/2026-2027.jpg"),
                SchoolCalendarImage("2025-2026", "https://dean.xjtu.edu.cn/2025-2026-new.png"),
                SchoolCalendarImage("2024-2025", "https://jwc.xjtu.edu.cn/__local/6/B2/x.jpg"),
                SchoolCalendarImage("2022-2023", "https://jwc.xjtu.edu.cn/__local/D/AA/y.png"),
            ),
            images,
        )
    }

    @Test
    fun `leading unknown years are inferred from the first known one`() {
        val images = SchoolCalendarImageApi.inferYears(
            listOf(SchoolCalendarImage(null, "a"), SchoolCalendarImage("2025-2026", "b"))
        )
        assertEquals("2026-2027", images[0].year)
        assertEquals("image/png", SchoolCalendarImage("2025-2026", "https://x/y.PNG").mimeType)
        assertEquals("西安交通大学校历 2026-2027.jpg", SchoolCalendarImage("2026-2027", "https://x/2026-2027.jpg").fileName)
    }
}
