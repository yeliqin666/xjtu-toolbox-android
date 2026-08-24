package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(AgentWeb.looksLikeCaptcha("<html>sogou.com/antispider 访问过于频繁</html>"))
        assertFalse(AgentWeb.looksLikeCaptcha("<html><h1>西安交通大学</h1></html>"))
    }

    @Test
    fun wechatMarkdown_readsJsContent() {
        val md = AgentWeb.htmlToMarkdown(
            """
            <html><body>
            <div class="rich_media_title">壳标题</div>
            <div id="js_content" style="visibility:hidden">
              <p>校历已发布，详见教务处通知。秋季学期从九月开始，请同学们及时查看学院安排以及各学院通知。</p>
            </div>
            </body></html>
            """.trimIndent(),
            "https://mp.weixin.qq.com/s/abc",
        )
        assertTrue(md.contains("校历已发布"))
        assertFalse(md.contains("壳标题"))
    }

    @Test
    fun sogouClickParams_appendsKH() {
        val url = "https://weixin.sogou.com/link?url=" + "x".repeat(40)
        val out = AgentWeb.withSogouClickParams(url, k = 3, extraOffset = 15)
        assertTrue(out.contains("&k=3&h="))
        assertEquals(out, AgentWeb.withSogouClickParams(out, k = 3))
    }

    @Test
    fun jinaReaderUrl_prefixesOnce() {
        assertEquals(
            "https://r.jina.ai/https://mp.weixin.qq.com/s/a",
            AgentWeb.jinaReaderUrl("https://mp.weixin.qq.com/s/a"),
        )
        assertEquals(
            "https://r.jina.ai/https://example.com",
            AgentWeb.jinaReaderUrl("https://r.jina.ai/https://example.com"),
        )
    }

    @Test
    fun wechatBlock_emptyJsContent() {
        assertTrue(AgentWeb.looksLikeWeChatBlock("<html>环境异常</html>"))
        assertTrue(
            AgentWeb.looksLikeWeChatBlock(
                """<div id="js_content"></div>""",
            ),
        )
        assertFalse(AgentWeb.looksLikeWeChatBlock("<html><p>普通网页</p></html>"))
    }

    @Test
    fun truncateMarkdown_appendsMarker() {
        val cut = AgentWeb.truncateMarkdown("abcd", 2)
        assertTrue(cut.startsWith("ab"))
        assertTrue(cut.contains("truncated"))
    }

    @Test
    fun parseWikiOpenSearch_jsonArray() {
        val body = """["交大",["西安交通大学"],["中国高校"],["https://zh.wikipedia.org/wiki/西安交通大学"]]"""
        val rows = AgentWeb.parseWikiOpenSearch(body, 5)
        assertEquals(1, rows.size)
        assertEquals("西安交通大学", rows[0].first)
        assertTrue(rows[0].second.contains("wikipedia.org"))
        assertTrue(rows[0].third.contains("高校"))
    }

    @Test
    fun capToolResult_keepsHeadAndTail() {
        val long = "HEAD" + "x".repeat(5000) + "TAIL"
        val cut = AgentRunner.capToolResult(long)
        assertTrue(cut.startsWith("HEAD"))
        assertTrue(cut.endsWith("TAIL"))
        assertTrue(cut.contains("中间已省略") || cut.contains("省略"))
        assertTrue(cut.length < long.length)
    }

    @Test
    fun capToolResult_fetchKeepsMoreThanDefault() {
        val long = "HEAD" + "m".repeat(9000) + "TAIL"
        val asList = AgentRunner.capToolResult(long, "get_notifications")
        val asFetch = AgentRunner.capToolResult(long, "web_fetch")
        assertTrue(asList.contains("中间已省略") || asList.contains("省略"))
        assertEquals(long, asFetch)
        assertTrue(AgentRunner.toolResultCap("web_search") > AgentRunner.toolResultCap("get_grades"))
    }

    @Test
    fun remainingToolHint_onlyWhenLow() {
        assertNull(AgentRunner.remainingToolHint(0, 3))
        assertNull(AgentRunner.remainingToolHint(8, 3))
        val low = AgentRunner.remainingToolHint(8, 6)!!
        assertTrue(low.contains("还剩 2 次"))
        assertTrue(!low.contains("web_fetch"))
        assertTrue(AgentRunner.remainingToolHint(8, 8)!!.contains("已用尽"))
    }
}
