package com.xjtu.toolbox.notification

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteSearchParseTest {

    private fun parse(body: String) =
        SiteSearch.parseVsb(Jsoup.parse("<html><body>$body</body></html>", "https://gs.xjtu.edu.cn/"), NotificationSource.GS, 1)

    @Test
    fun `标题包一层 div 时往上找日期，且不拿标题里的日期`() {
        val r = parse(
            """<ul>
              <li><div class="title"><a href="info/1011/1.htm">关于2025年5月1日放假安排的通知</a></div><span>2026-09-20</span></li>
              <li><div class="title"><a href="info/1011/2.htm">第二条通知的标题</a></div><span>2026-09-18</span></li>
            </ul>"""
        )
        assertEquals(listOf("2026-09-20", "2026-09-18"), r.page.items.map { it.date.toString() })
    }

    @Test
    fun `没有逐条行容器时不给每条都套上第一个日期`() {
        val r = parse(
            """<div class="list">
              <a href="info/1011/1.htm">第一条通知的标题</a><span>2026-09-20</span>
              <a href="info/1011/2.htm">第二条通知的标题</a><span>2026-09-18</span>
            </div>"""
        )
        assertTrue(r.page.items.isEmpty())
        assertEquals(2, r.undatedHits)
    }
}
