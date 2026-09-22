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
    fun httpUrl_isAllowed() {
        assertTrue(AgentWeb.isPublicHttpUrl("http://org.xjtu.edu.cn/list.htm"))
        assertTrue(AgentWeb.isPublicHttpUrl("https://www.bing.com/search?q=a"))
        assertFalse(AgentWeb.isPublicHttpUrl("file:///etc/passwd"))
        assertFalse(AgentWeb.isPublicHttpUrl("http://127.0.0.1/"))
        assertFalse(AgentWeb.isPublicHttpUrl("http://192.168.1.1/"))
    }

    /** 360 的真实地址在 data-mdurl，href 是 so.com/link 跳转链——以前整条被当站内链接丢掉。 */
    @Test
    fun parseSo360Html_usesMdurlAndKeepsJumpLinks() {
        val html = """
            <li class="res-list">
              <h3 class="res-title"><a href="https://www.so.com/link?m=abc" data-mdurl="http://gs.xjtu.edu.cn/info/1145/6378.htm">创新港班车运行时间表</a></h3>
              <p class="res-desc">研究生院通知</p>
            </li>
            <li class="res-list">
              <h3 class="res-title"><a href="https://www.so.com/link?m=def">只有跳转链的结果</a></h3>
            </li>
            <li class="res-list">
              <h3 class="res-title"><a href="https://baike.so.com/doc/1.html">360 百科</a></h3>
            </li>
            <li class="res-list">
              <h3 class="res-title"><a href="https://www.so.com/link?m=v" data-mdurl="https://tv.360kan.com/s?q=西交 创新港 校车&amp;src=x">短视频聚合</a></h3>
            </li>
        """.trimIndent()
        val rows = AgentWeb.parseSo360Html(html, 5)
        assertEquals(2, rows.size)
        assertEquals("http://gs.xjtu.edu.cn/info/1145/6378.htm", rows[0].second)
        assertEquals("研究生院通知", rows[0].third)
        assertTrue(rows[1].second.contains("so.com/link?"))
    }

    /** 百度：mu 是真实地址就用它；阿拉丁卡片的占位 mu 退回标题跳转链。 */
    @Test
    fun parseBaiduHtml_prefersRealMu() {
        val html = """
            <div id="content_left">
              <div class="result c-container" mu="http://oa.xjtu.edu.cn/notice.jsp?id=1">
                <h3 class="t"><a href="https://www.baidu.com/link?url=aaa">班车时刻调整通知</a></h3>
                <div class="c-abstract">节后调整各校区班车运行时刻</div>
              </div>
              <div class="result-op c-container" mu="http://nourl.ubs.baidu.com/279">
                <h3 class="t"><a href="https://www.baidu.com/link?url=bbb">阿拉丁卡片</a></h3>
              </div>
            </div>
        """.trimIndent()
        val rows = AgentWeb.parseBaiduHtml(html, 5)
        assertEquals(2, rows.size)
        assertEquals("http://oa.xjtu.edu.cn/notice.jsp?id=1", rows[0].second)
        assertTrue(rows[0].third.contains("班车"))
        assertTrue(rows[1].second.startsWith("https://www.baidu.com/link?url="))
    }

    @Test
    fun baiduBlock_detectsCaptchaRedirect() {
        assertTrue(AgentWeb.looksLikeBaiduBlock("", "https://wappass.baidu.com/static/captcha/tuxing_v2.html"))
        assertTrue(AgentWeb.looksLikeBaiduBlock("<title>百度安全验证</title>", "https://www.baidu.com/s"))
        assertFalse(AgentWeb.looksLikeBaiduBlock("<div id=\"content_left\"></div>", "https://www.baidu.com/s?wd=x"))
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
    fun capToolResult_cutsWholeLinesAndCountsThem() {
        val rows = (1..400).map { "课程$it｜周一 08:00–09:50｜主楼A-${100 + it}" }
        val cut = AgentRunner.capToolResult(rows.joinToString("\n"))
        val kept = cut.split('\n')
        // 每一行要么是完整的原行，要么是省略标记，不会劈成半行
        assertTrue(kept.all { it in rows || it.contains("省略") })
        val omitted = Regex("""省略中间 (\d+) 行""").find(cut)!!.groupValues[1].toInt()
        assertEquals(400, kept.size - 1 + omitted)
        assertTrue(cut.startsWith("课程1｜"))
        assertTrue(cut.endsWith("课程400｜周一 08:00–09:50｜主楼A-500"))
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
    fun remainingToolHint_onlyWhenFuseBlows() {
        // 平时不提次数，只在保险丝烧断那一刻说一句
        assertNull(AgentRunner.remainingToolHint(0))
        assertNull(AgentRunner.remainingToolHint(AgentRunner.TOOL_CALL_FUSE - 1))
        assertTrue(AgentRunner.remainingToolHint(AgentRunner.TOOL_CALL_FUSE)!!.contains("已用尽"))
    }
}
