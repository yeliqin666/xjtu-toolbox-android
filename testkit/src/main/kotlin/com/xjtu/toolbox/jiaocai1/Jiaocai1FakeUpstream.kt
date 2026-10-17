package com.xjtu.toolbox.jiaocai1

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** 假的教材全文库上游（`jiaocai1.lib.xjtu.edu.cn`）。 */
class Jiaocai1FakeUpstream {

    companion object {
        /** 教材中心（登录入口 + 会话落点）。 */
        const val JIAOCAI_HOST = "jiaocai.lib.xjtu.edu.cn"

        /** 教材全文库（业务）。 */
        const val JIAOCAI1_HOST = "jiaocai1.lib.xjtu.edu.cn"

        const val BASE = "https://$JIAOCAI1_HOST"

        /** 教材中心的会话 cookie（`JiaocaiLogin` 走完 CAS 之后由本站种下）。 */
        const val SESSION_COOKIE = "SESSION_fake_jiaocai"

        /** 全文库自己那份会话 cookie（跟完 `portalLogin` 那条 JS 链之后种下）。 */
        const val PORTAL_COOKIE = "jiAocai1_session_fake"

        // ── 夹具样本（全是编造值） ──

        /** 检索命中的那本书（`ssno` 是全文库主键）。 */
        const val SSNO = "900000001"
        const val BOOK_TITLE = "示例教材：电路分析（第七版）上册"
        const val BOOK_AUTHOR = "示例甲"
        const val BOOK_PUBLISH_DATE = "2020-08-01"
        const val BOOK_CALL_NO = "TM133/8(7)"
        const val BOOK_THEME_WORD = "电路分析"
        const val BOOK_CLASSIFY_PATH = "工业技术 > 电工技术"

        /** 未命中任何书的检索词（用来演「没有匹配的书目」那一屏）。 */
        const val MISS_KEYWORD = "不存在的书名示例"

        /** 第二页的结果（总页数是 2：屏上的「加载更多」那一发会拉到它）。 */
        const val SSNO_PAGE2 = "900000002"
        const val BOOK_TITLE_PAGE2 = "示例教材：电路分析（第七版）下册"

        /** 阅读器句柄：`jpgPath` 是服务端下发的令牌路径（不要持久化）。 */
        const val JPG_PATH = "fake/reader/900000001/"

        /** 8 种页型各自的 [起, 止]（封底复用 cov 前缀、编号从 2 起，见 `Jiaocai1Paging`）。 */
        val PAGE_RANGES = listOf(
            1..2, // 封面（cov001 / cov002）
            1..1, // 书名页
            1..1, // 版权页
            1..1, // 前言
            1..3, // 目录
            1..24, // 正文
            1..1, // 附录
            2..2, // 封底
        )

        /** 正文页数（reader.shtml 里的 `var bookPages`）。 */
        const val BOOK_PAGES = 24

        /** 分类树里那两级（一级 + 一个子类，用来钉 `buildTree`）。 */
        const val ROOT_CATEGORY_ID = "0O"
        const val ROOT_CATEGORY_NAME = "工业技术（示例）"
        const val CHILD_CATEGORY_ID = "0O109101"
        const val CHILD_CATEGORY_NAME = "电工技术（示例）"

        /** 页图尺寸：与真站点的扫描页同比例（700:1050），但字节小得多。 */
        const val PAGE_WIDTH = 700
        const val PAGE_HEIGHT = 1050

        // ── 教材中心（`jiaocai.lib`）那一半："查教材"栅的检索 ──

        /** 检索命中的那本书（`content` 里只给文字，`ssno` 要进详情才拿得到）。 */
        const val SEARCH_BOOK_TITLE = "示例教材：高等数学（第七版）上册"
        const val SEARCH_BOOK_AUTHOR = "示例乙"
        const val SEARCH_BOOK_APP_ID = 7
        const val SEARCH_BOOK_ENGINE_ID = 3

        /** 未命中任何教材的检索词（`dataList` 空掉那一档）。 */
        const val MISS_SEARCH_KEYWORD = "没有这门课的教材示例"
    }

    /** 最近一次「查教材」检索的关键词（断言检索参数用）。 */
    @Volatile
    var lastJiaocaiKeyword: String? = null

        private set

    // ── 断言用：请求计数 ──

    /** 教材中心登录入口被摸了几次（登录链的第一个 302）。 */
    val loginEntryCalls = AtomicInteger(0)

    /** 全文库那张「跳转页」发出去几次（会话没建立时每发一次业务请求就来一张）。 */
    val portalJumpCalls = AtomicInteger(0)

    /** `portalLogin` 被跟了几跳。 */
    val portalLoginCalls = AtomicInteger(0)

    /** 取页图（`/jpath/` 下那些 `.jpg`）被要了几次。 */
    val pageImageCalls = AtomicInteger(0)

    /** `jiaocai.lib` 的检索（`/engine2/search/search-list`）被摸了几次。 */
    val jiaocaiSearchCalls = AtomicInteger(0)

    /** `jiaocai.lib` 的书目详情（`/engine2/d/…`）被摸了几次（屏上进详情才补 ssno）。 */
    val jiaocaiDetailCalls = AtomicInteger(0)

    /** 检索被 POST 了几次。 */
    val searchCalls = AtomicInteger(0)

    /** 最近一次检索表单的请求体。 */
    @Volatile
    var lastSearchBody: String? = null

        private set

    /** 最近一次 `goRead` 的 ssno。 */
    @Volatile
    var lastReaderSsno: String? = null

        private set

    /** 教材中心（`jiaocai.lib`）：登录入口那一半。 */
    fun handleJiaocai(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
        when {
            path == "/entry/login" -> {
                loginEntryCalls.incrementAndGet()
                if ("ticket=" in query) {
                    // CAS 回跳落点：种下教材中心的会话（`JiaocaiLogin.postLogin` 只记一行日志）
                    exchange.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=yes; Path=/")
                    respond(exchange, 200, "text/html; charset=utf-8", LOGIN_LANDING_HTML.toByteArray())
                } else {
                    // 无票 ⇒ 按真站点那样 302 到统一认证（service 就是本入口）
                    redirect(exchange, casLogin("https://$JIAOCAI_HOST$path"))
                }
            }
            path == "/engine2/search/search-list" -> {
                if (SESSION_COOKIE in cookie) {
                    jiaocaiSearchCalls.incrementAndGet()
                    lastJiaocaiKeyword = java.net.URLDecoder.decode(
                        query.substringAfter("keyWord=", "").substringBefore('&'),
                        "UTF-8",
                    )
                    val body = if (lastJiaocaiKeyword == MISS_SEARCH_KEYWORD) emptySearchListJson() else searchListJson()
                    respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray())
                } else {
                    jumpPageFor(exchange)
                }
            }
            path.startsWith("/engine2/d/") -> {
                if (SESSION_COOKIE in cookie) {
                    jiaocaiDetailCalls.incrementAndGet()
                    respond(exchange, 200, "text/html; charset=utf-8", detailHtml().toByteArray())
                } else {
                    jumpPageFor(exchange)
                }
            }
        }
    }

    /** 教材全文库（`jiaocai1.lib`）：业务那一半。 */
    fun handleJiaocai1(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()

        // ── 会话没建立时：不返数据，返一张只有一行 logoutUrl 的跳转页（`PortalRedirect` 跟它）──
        if (PORTAL_COOKIE !in cookie) {
            if (path == "/front/user/portalLogin") {
                portalLoginCalls.incrementAndGet()
                // 真实链条的最后一跳：拿到 uid/name，站点点下会话 —— 这一页里**没有** logoutUrl，
                // 所以客户端的 `PortalRedirect.follow` 认为链条走完了，回头重发原始请求。
                exchange.responseHeaders.add("Set-Cookie", "$PORTAL_COOKIE=yes; Path=/")
                respond(exchange, 200, "text/html; charset=utf-8", PORTAL_LOGIN_HTML.toByteArray())
            } else {
                portalJumpCalls.incrementAndGet()
                respond(exchange, 200, "text/html; charset=utf-8", jumpPage().toByteArray())
            }
            return
        }

        when {
            path == "/front/classify/info" -> respond(
                exchange,
                200,
                "application/json; charset=utf-8",
                CLASSIFY_JSON.toByteArray(),
            )
            path == "/front/book/search/index" -> {
                searchCalls.incrementAndGet()
                val raw = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                lastSearchBody = raw
                // 认一个「查不到」的关键词；第二页另给一本，避免 `key = { it.ssno }` 重键。
                val miss = formValue(raw, "sw") == MISS_KEYWORD
                val page = formValue(raw, "cpage")?.toIntOrNull() ?: 1
                respond(
                    exchange,
                    200,
                    "text/html; charset=utf-8",
                    when {
                        miss -> emptySearchHtml()
                        page >= 2 -> searchPage2Html()
                        else -> searchHtml()
                    }.toByteArray(),
                )
            }
            path == "/front/reader/goRead" -> {
                lastReaderSsno = query.substringAfter("ssno=", "").substringBefore('&')
                respond(exchange, 200, "text/html; charset=utf-8", readerHtml().toByteArray())
            }
            path.startsWith("/jpath/") && path.endsWith(".jpg") -> {
                pageImageCalls.incrementAndGet()
                respond(exchange, 200, "image/jpeg", pageJpeg(path.substringAfterLast('/')))
            }
            // 封面：真站点上是一张小图（搜索结果与书架里的缩略图走它）
            path.startsWith("/cover/") -> respond(exchange, 200, "image/jpeg", coverJpeg())
            // `/front/` 那一发只是 Referer 的落点，真站点上是首页；夹具给个空页就够
            else -> respond(exchange, 200, "text/html; charset=utf-8", "<html></html>".toByteArray())
        }
    }

    /** 跳转页：内容形状照真站点（只有一行 `var logoutUrl = "…"`，路径带转义的 `/`）。 */
    private fun jumpPage(): String = """
        <html><head><script>
        var logoutUrl = "https:\/\/$JIAOCAI1_HOST\/front\/user\/portalLogin?channel=$CHANNEL";
        location.href = logoutUrl;
        </script></head><body></body></html>
    """.trimIndent()

    private fun jumpPageFor(exchange: HttpExchange) {
        portalJumpCalls.incrementAndGet()
        respond(exchange, 200, "text/html; charset=utf-8", jumpPage().toByteArray())
    }

    /** `search-list` 的响应，形状照真站点：一行书目在 `data.dataList` 里，`content` 是`<br>` 分行的富文本。 */
    private fun searchListJson(): String = """
        {"data":{"dataList":[{
          "id":"general_55258665",
          "appId":$SEARCH_BOOK_APP_ID,
          "engineInstanceId":$SEARCH_BOOK_ENGINE_ID,
          "title":"<b>$SEARCH_BOOK_TITLE</b>",
          "author":"$SEARCH_BOOK_AUTHOR",
          "content":"获取方式一：本地全文<br>课程名称：示例课<br>教材名称：$SEARCH_BOOK_TITLE；编者：$SEARCH_BOOK_AUTHOR；出版社：示例出版社；ISBN号：978-7-0000-0000-1"
        }]}}
    """.trimIndent()

    /** 「查不到」那一档：`dataList` 是空的（与真站点同构）。 */
    private fun emptySearchListJson(): String = """{"data":{"dataList":[]}}"""

    /** 书目详情：真站点在这里给出完整富文本，`本地全文` 链接带着 `ssno`。 */
    private fun detailHtml(): String = """
        <html><body><div class="detail">
        <p>获取方式一：本地全文</p>
        <p><a href="http://$JIAOCAI1_HOST:9088/guajie/common?ssno=$SSNO&cpage=1&channel=$CHANNEL">本地全文</a></p>
        </div></body></html>
    """.trimIndent()

    private fun casLogin(service: String): String =
        "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=${encode(service)}"
    /** 第二页：只有一本（`data-cpage="2"`，`data-sum-page` 仍是 2）⇒ 屏上的「加载更多」拿得到东西。 */
    private fun searchPage2Html(): String = """
        <html><body><div id="result" data-cpage="2" data-sum-page="2" data-totalrow="31"><ul>
        <li>
          <a href="/front/reader/goRead?ssno=$SSNO_PAGE2&channel=$CHANNEL" title="$BOOK_TITLE_PAGE2">$BOOK_TITLE_PAGE2</a>
          <dl><dd>作者：$BOOK_AUTHOR</dd></dl>
        </li>
        </ul></div></body></html>
    """.trimIndent()

    private fun formValue(body: String, key: String): String? = body.split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=')
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    /** 空结果：一个 `<li>` 都没有（`parseSearchHtml` 会给出空的 books，但页码字段照旧）。 */
    private fun emptySearchHtml(): String = """
        <html><body><div id="result" data-cpage="1" data-sum-page="1" data-totalrow="0"><ul></ul></div></body></html>
    """.trimIndent()

    private fun searchHtml(): String = """
        <html><body>
        <div id="result" data-cpage="1" data-sum-page="2" data-totalrow="31">
        <ul>
        <li>
          <a href="/front/reader/goRead?ssno=$SSNO&channel=$CHANNEL" title="$BOOK_TITLE">$BOOK_TITLE</a>
          <img class="cover" src="$BASE/cover/$SSNO.jpg">
          <dl>
            <dd>作者：$BOOK_AUTHOR</dd>
            <dd>出版日期：$BOOK_PUBLISH_DATE</dd>
            <dd>主题词：$BOOK_THEME_WORD</dd>
            <dd>索书号：$BOOK_CALL_NO</dd>
            <dd>分类：$BOOK_CLASSIFY_PATH</dd>
          </dl>
        </li>
        </ul>
        </div>
        </body></html>
    """.trimIndent()

    /** reader.shtml：`jpgPath` / 8 组 `pages` / `bookPages` / `<title>` 四样都要有。 */
    private fun readerHtml(): String {
        val pairs = PAGE_RANGES.joinToString(",") { "[${it.first},${it.last}]" }
        return """
            <html><head><title>$BOOK_TITLE</title></head><body><script>
            var reader = { jpgPath: "$JPG_PATH", channel: $CHANNEL };
            var pages = [$pairs];
            var bookPages = $BOOK_PAGES;
            </script></body></html>
        """.trimIndent()
    }

    private val pageCache = ConcurrentHashMap<String, ByteArray>()

    /** 封面（示例）：240×320 的小图，同样程序生成。 */
    private fun coverJpeg(): ByteArray = pageCache.computeIfAbsent("__cover__") {
        val image = BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color(0x6B, 0x7B, 0xB8)
            g.fillRect(0, 0, 240, 320)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 28)
            val text = "示例教材"
            g.drawString(text, (240 - g.fontMetrics.stringWidth(text)) / 2, 170)
        } finally {
            g.dispose()
        }
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "jpg", out)
            out.toByteArray()
        }
    }
    /** 画一页（程序生成，不是截图）：白底 + 居中的页型/页号大字 + 右下角一行小字。 */
    private fun pageJpeg(fileName: String): ByteArray = pageCache.computeIfAbsent(fileName) { name ->
        val image = BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT)
            g.color = Color(0x22, 0x22, 0x22)
            g.font = Font("SansSerif", Font.BOLD, 56)
            val label = name.removeSuffix(".jpg")
            drawCentered(g, label, PAGE_HEIGHT / 2 - 40)
            g.font = Font("SansSerif", Font.PLAIN, 24)
            g.color = Color(0x88, 0x88, 0x88)
            drawCentered(g, "示例教材 · 夹具页", PAGE_HEIGHT / 2 + 40)
            g.color = Color(0xAA, 0xAA, 0xAA)
            g.drawString("jiaocai1 fixture", 24, PAGE_HEIGHT - 24)
        } finally {
            g.dispose()
        }
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "jpg", out)
            out.toByteArray()
        }
    }

    private fun drawCentered(g: java.awt.Graphics2D, text: String, y: Int) {
        val width = g.fontMetrics.stringWidth(text)
        g.drawString(text, (PAGE_WIDTH - width) / 2, y)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private val CHANNEL = "100"

    private val LOGIN_LANDING_HTML = """
        <html><head><title>教材中心</title></head><body>
        <div id="app">已登录（示例）</div>
        </body></html>
    """.trimIndent()

    private val PORTAL_LOGIN_HTML = """
        <html><head><title>portalLogin</title></head><body>
        <script>var user = { uid: "900000001", name: "示例甲" };</script>
        </body></html>
    """.trimIndent()

    /** `classify/info` 的响应：一级 + 一个子类（`buildTree` 按 `pid` / `id` 建树）。 */
    private val CLASSIFY_JSON = """
        {"classifyList":[
          {"classifyid":"$ROOT_CATEGORY_ID","classifyname":"$ROOT_CATEGORY_NAME","clevel":1,"pid":0,"id":11},
          {"classifyid":"$CHILD_CATEGORY_ID","classifyname":"$CHILD_CATEGORY_NAME","clevel":2,"pid":11,"id":12}
        ]}
    """.trimIndent()
}
