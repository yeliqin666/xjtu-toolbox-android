package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebTest {

    @Test
    fun htmlToMarkdown_headingsAndLinks() {
        val md = AgentWeb.htmlToMarkdown(
            """
            <html><body>
            <nav>ignore me</nav>
            <h1>校历</h1>
            <p>见 <a href="http://dean.xjtu.edu.cn/info.html">教务处</a>。</p>
            <script>captcha()</script>
            </body></html>
            """.trimIndent(),
            "http://dean.xjtu.edu.cn/",
        )
        assertTrue(md.contains("校历"))
        assertTrue(md.contains("http://dean.xjtu.edu.cn/info.html"))
        assertFalse(md.contains("ignore me"))
        assertFalse(md.contains("captcha()"))
    }

    @Test
    fun parseBingRss_items() {
        val xml = """
            <?xml version="1.0"?>
            <rss><channel>
              <item>
                <title>西安交通大学</title>
                <link>http://www.xjtu.edu.cn/</link>
                <description>学校官网</description>
              </item>
            </channel></rss>
        """.trimIndent()
        val rows = AgentWeb.parseBingRss(xml, 5)
        assertEquals(1, rows.size)
        assertEquals("西安交通大学", rows[0].first)
        assertEquals("http://www.xjtu.edu.cn/", rows[0].second)
        assertEquals("学校官网", rows[0].third)
    }

    @Test
    fun parseDuckDuckGoLite_linkText() {
        val html = """
            <table>
              <tr><td><a class="result-link" href="/l/?uddg=x">交大校历</a></td></tr>
              <tr><td class="result-snippet">2026 学期安排</td></tr>
              <tr><td><span class="link-text">dean.xjtu.edu.cn/calendar</span></td></tr>
            </table>
        """.trimIndent()
        val rows = AgentWeb.parseDuckDuckGoLite(html, 5)
        assertEquals(1, rows.size)
        assertEquals("交大校历", rows[0].first)
        assertEquals("https://dean.xjtu.edu.cn/calendar", rows[0].second)
        assertTrue(rows[0].third.contains("学期"))
    }

    @Test
    fun httpUrl_isAllowed() {
        assertTrue(AgentWeb.isPublicHttpUrl("http://org.xjtu.edu.cn/list.htm"))
        assertTrue(AgentWeb.isPublicHttpUrl("https://www.bing.com/search?q=a"))
        assertFalse(AgentWeb.isPublicHttpUrl("file:///etc/passwd"))
        assertFalse(AgentWeb.isPublicHttpUrl("http://127.0.0.1/"))
        assertFalse(AgentWeb.isPublicHttpUrl("http://192.168.1.1/"))
    }

    @Test
    fun parseJinaSearch_json() {
        val body = """
            {"code":200,"data":[
              {"title":"西安交通大学校历","url":"https://dean.xjtu.edu.cn/calendar","description":"本科校历"}
            ]}
        """.trimIndent()
        val rows = AgentWeb.parseJinaSearch(body, 5)
        assertEquals(1, rows.size)
        assertEquals("西安交通大学校历", rows[0].first)
        assertEquals("https://dean.xjtu.edu.cn/calendar", rows[0].second)
        assertTrue(rows[0].third.contains("校历"))
    }

    @Test
    fun parseDuckDuckGoHtml_cn() {
        val html = """
            <div class="result">
              <a class="result__a" href="https://dean.xjtu.edu.cn/info/1010/123.htm">交大校历</a>
              <a class="result__snippet">2026 学年学期安排</a>
            </div>
        """.trimIndent()
        val rows = AgentWeb.parseDuckDuckGoHtml(html, 5)
        assertEquals(1, rows.size)
        assertEquals("交大校历", rows[0].first)
        assertTrue(rows[0].second.contains("dean.xjtu.edu.cn"))
    }

    @Test
    fun parseSo360Html_skipsSelfLinks() {
        val html = """
            <li class="res-list">
              <h3><a href="https://news.xjtu.edu.cn/info.htm">交大新闻</a></h3>
              <p class="res-desc">学校官网新闻</p>
            </li>
            <li class="res-list">
              <h3><a href="https://www.so.com/s?q=x">相关搜索</a></h3>
              <p class="res-desc">站内</p>
            </li>
        """.trimIndent()
        val rows = AgentWeb.parseSo360Html(html, 5)
        assertEquals(1, rows.size)
        assertEquals("交大新闻", rows[0].first)
    }

    @Test
    fun looksLikeCaptcha_challengePage() {
        assertTrue(AgentWeb.looksLikeCaptcha("<html>请完成验证 geetest</html>"))
        assertFalse(AgentWeb.looksLikeCaptcha("<html><h1>西安交通大学</h1></html>"))
    }

    @Test
    fun truncateMarkdown_appendsMarker() {
        val cut = AgentWeb.truncateMarkdown("abcd", 2)
        assertTrue(cut.startsWith("ab"))
        assertTrue(cut.contains("truncated"))
    }
}
