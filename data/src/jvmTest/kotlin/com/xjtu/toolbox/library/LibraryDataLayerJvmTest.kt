package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.error.SessionExpiredFailure
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * **阶段 0 · 图书馆这条竖切在 JVM 上跑通的证明**（`docs/desktop-port-plan.md` §6 Stage 0）。
 *
 * ## 它测的不是「解析函数」，而是整条链路
 *
 * 夹具是学校那几个页面/接口的**原文形状**（照 :app 原有单测与 HAR 记录写），流程是真的：
 * 起一个本地 HTTP 服务器 → `LibraryApi` 自己拼 URL、带 Referer、读响应、解析、判成功/失败 →
 * 断言模型逐字段。
 *
 * 于是这一条测试同时钉住四件在设计文档里被点名的事：
 *
 * | 要验的 | 在哪里 |
 * |---|---|
 * | **读**：校区 / 区域 / 座位 / 平面图 / 我的预约 | [读路径与 Android 端同一份解析] |
 * | **写**：预约、换座（含「换座后复核我的预约」）、取消（含动作后复核） | [写路径] |
 * | **会话失效**：响应体是登录页时抛 `:data` 的会话失效异常 | [会话失效被认出来] |
 * | **图片字节**：认文件头、不看 Content-Type（经 WebVPN 时类型头会没） | [平面图字节] |
 *
 * ## 为什么不用 `SiteSession`、也不用改生产代码
 *
 * 这一份数据层吃的是一条缝（[LibrarySession]）：`:app` 填 `SiteSession`，这里填
 * [DirectLibrarySession] + 一个**把图书馆域名重写到本地端口**的 okhttp 拦截器。
 * 生产代码里因此没有一行「为了测试而留的口子」——`LibraryPages.BASE_URL` 仍是学校的地址，
 * 重写发生在测试自己的客户端上。这也正是把数据层摘出 Android 的意义：同一份代码在 JVM 上
 * 能**真跑**，而不是只能编译。
 */
class LibraryDataLayerJvmTest {

    private val libraryHost = "rg.lib.xjtu.edu.cn"

    /** 服务器可变状态：动作打进来时翻一下，好让「动作后复核」有东西可查。 */
    private val swapped = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    private lateinit var server: HttpServer
    private lateinit var base: String

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    /** 302 到「我的预约」页：`bookSeat` 正是按「最终落到 /my/」判成功的。 */
    private fun redirectToMy(exchange: HttpExchange) {
        exchange.responseHeaders.add("Location", "$base/my/")
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondHtml(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json", body.toByteArray())

    // ── 夹具：与学校页面同形状 ─────────────────────────────────────

    /** `/modify`：账号资料页，校区是 `select#rplace` 的选中项。 */
    private val modifyPage = """
        <html><body><form>
          <input name="csrf_token" value="tok-1">
          <input id="email" name="email" value="a@b.c">
          <input id="tel" name="tel" value="13800000000">
          <select id="rplace" name="rplace">
            <option value="east" selected>兴庆</option>
            <option value="west">雁塔</option>
          </select>
        </form></body></html>
    """.trimIndent()

    /** `/qspace`：区域名（`sp`）+ 空座统计（`scount`）。 */
    private val qspaceJson = """
        {"sp":{"north2east":"北楼二层外文库（东）","north2west":"北楼二层外文库（西）"},
         "scount":{"north2east":[24,9],"north2west":[18,2]}}
    """.trimIndent()

    /** `/qseat`：`seat` 里 0 = 空位，非 0 = 被占。 */
    private val qseatJson = """
        {"scount":{"north2east":[24,9]},
         "seat":{"A10":0,"A02":1,"A01":0}}
    """.trimIndent()

    /** `/qseatuist`：五个元素的数组，第 5 个是状态码（2 = 空闲）。坐标是字符串。 */
    private val qseatuistJson = """
        {"A01":["10","20","30","40","2"],"cancel":["0","0","0","0","2"],"A02":["50","20","30","40","0"]}
    """.trimIndent()

    /** 一张最小的 JPEG 头（`getPlanImage` 只认文件头，不解码）。 */
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)

    /** 「我的预约」：`div.well` 是当前预约、`div.notwell` 是历史。座位行是第一个 `<hr>` 的尾随文本。 */
    private fun myPage(seat: String): String = """
        <html><body>
        <div class="well"><div class="row">
          <div class="col-md-9 cta-contents"><h3 class="cta-title">业务类型:&nbsp;预约座位<br>
          <hr>北楼二层外文库（东）&nbsp;$seat
          <h4><a href="/updateseat">我想换座</a></h4></div>
          <div class="col-md-3 cta-button"><center><h4>预约状态:</h4><h3>已预约</h3>
          <script>showConfirmModal('确认', 'cancel', '88')</script>
          <script>showConfirmModal('确认', 'ruguan1', '88')</script>
          </center></div>
        </div></div>
        </body></html>
    """.trimIndent()

    private val noBookingPage = """
        <html><body><div class="notwell">暂无预约记录</div></body></html>
    """.trimIndent()

    /** 一个「被重定向到统一身份认证」的响应体：`id="loginForm"` 就足够让本端判成会话失效。 */
    private val loginPage = """<html><body><form id="loginForm" action="/cas/login"></form></body></html>"""

    /** `/qspace` 这一枪是否回登录页（只有「会话失效」那条测试会打开它）。 */
    private val loginMode = AtomicBoolean(false)

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == "/modify" -> respondHtml(exchange, modifyPage)
            // 会话失效那条路：只有那条测试会把 `/qspace` 换成登录页
            path == "/qspace" -> if (loginMode.get()) respondHtml(exchange, loginPage) else respondJson(exchange, qspaceJson)
            path == "/qseat" -> respondJson(exchange, qseatJson)
            path == "/qseatuist" -> respondJson(exchange, qseatuistJson)
            path == "/seat/" -> redirectToMy(exchange)
            path == "/updateseat/" -> {
                swapped.set(true)
                respondHtml(exchange, "<html><body>已换座</body></html>")
            }
            path == "/my/" && "cancel=1" in query -> {
                cancelled.set(true)
                respondHtml(exchange, "<html><body><div class='alert'>已取消</div></body></html>")
            }
            path == "/my/" -> respondHtml(exchange, if (cancelled.get()) noBookingPage else myPage(if (swapped.get()) "A02" else "A01"))
            // 只有那一张图存在；其余（含错误页）走 404，好让「不是图片就返回 null」那条断言有意义
            path.endsWith("/north2east.jpg") -> respond(exchange, 200, "image/jpeg", jpegBytes)
            path.startsWith("/static/images/ui10/") -> respondHtml(exchange, "<html><body>not found</body></html>")
            else -> respond(exchange, 404, "text/plain", "nope".toByteArray())
        }
    }

    /**
     * 起本地服务器 + 一个**把图书馆域名重写到本地**的客户端。
     *
     * 重写放在拦截器里而不是改 `LibraryPages.BASE_URL`：生产代码不该为测试留口子，
     * 而「同一份代码在 JVM 上真跑」这条证明也不需要口子 —— 一个真实存在的 okhttp 客户端
     * 配上一条 URL 重写就够了（这也顺便测了 okhttp 在桌面 JVM 上的可用性）。
     */
    private fun <T> withLibraryServer(block: suspend (LibraryApi, OkHttpClient) -> T): T {
        swapped.set(false)
        cancelled.set(false)
        loginMode.set(false)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/", ::handle)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        val local = base.toHttpUrl()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != libraryHost) {
                chain.proceed(request)
            } else {
                val rewritten = request.url.newBuilder()
                    .scheme(local.scheme).host(local.host).port(local.port)
                    .build()
                chain.proceed(request.newBuilder().url(rewritten).build())
            }
        }.build()
        try {
            return runBlocking { block(LibraryApi(DirectLibrarySession(client)), client) }
        } finally {
            server.stop(0)
        }
    }

    // ══════ 读路径与 Android 端同一份解析 ══════

    @Test
    fun `读：校区取自 modify 页的 rplace`() = withLibraryServer { api, _ ->
        assertEquals(LibraryCampus.XINGQING, api.getCurrentCampus())
    }

    @Test
    fun `读：区域名从 qspace 的 sp 学出来，空座统计按兴庆白名单过滤`() = withLibraryServer { api, _ ->
        val areas = api.getFloorAreas("xingqing2floor")
        assertEquals(
            mapOf("north2east" to "北楼二层外文库（东）", "north2west" to "北楼二层外文库（西）"),
            areas,
        )
        // 学到的区域名参与反查；楼层码也学到了（`qseat` 之前要先 `qspace` 定位楼层）
        assertEquals("北楼二层外文库（东）", api.areaNameOf("north2east"))
        assertEquals("xingqing2floor", api.floorOfArea("north2east"))
        assertEquals(mapOf("north2east" to AreaStats(9, 24), "north2west" to AreaStats(2, 18)), api.cachedAreaStats)
    }

    @Test
    fun `读：座位按字母前缀+数字排序，0 才是空位`() = withLibraryServer { api, _ ->
        val result = api.getSeats("north2east")
        val success = assertIs<SeatResult.Success>(result)
        assertEquals(listOf("A01", "A02", "A10"), success.seats.map { it.seatId })
        assertEquals(listOf(true, false, true), success.seats.map { it.available })
        // 附带的区域统计与 qspace 那份口径一致（filterScount 之后的）
        assertEquals(AreaStats(9, 24), success.areaStatsMap.getValue("north2east"))
    }

    @Test
    fun `读：平面图丢掉 cancel 那两个非座位矩形，状态码 2 才算空闲`() = withLibraryServer { api, _ ->
        val layout = api.getSeatLayout("north2east")
        assertEquals(listOf("A01", "A02"), layout.seats.map { it.seatId })
        assertEquals(PlanSeat.FREE, layout.seats.first { it.seatId == "A01" }.status)
        assertEquals(PlanSeat.BOOKED, layout.seats.first { it.seatId == "A02" }.status)
        assertEquals(10f, layout.seats.first { it.seatId == "A01" }.left)
    }

    @Test
    fun `读：我的预约：座位号、区域、状态与动作地址`() = withLibraryServer { api, _ ->
        val booking = api.fetchMyBooking().getOrThrow()
        assertEquals("A01", booking?.seatId)
        assertEquals("北楼二层外文库（东）", booking?.area)
        assertEquals("已预约", booking?.statusText)
        assertEquals(
            mapOf(
                "取消预约" to "${LibraryPages.BASE_URL}/my/?cancel=1&ri=88",
                "入馆签到" to "${LibraryPages.BASE_URL}/my/?firstruguan=1&ri=88",
            ),
            booking?.actionUrls,
        )
    }

    @Test
    fun `平面图字节：认文件头，不看 Content-Type`() = withLibraryServer { api, _ ->
        val bytes = api.getPlanImage("north2east.jpg")
        assertTrue(bytes != null && bytes.size == jpegBytes.size, "应原样返回 JPEG 字节")
        // 非图片（HTML 错误页）返回 null，不抛
        assertEquals(null, api.getPlanImage("nope.jpg"))
    }

    // ══════ 写路径 ══════

    @Test
    fun `写：预约成功落在 my 页（重定向即成功）`() = withLibraryServer { api, _ ->
        val result = api.bookSeat("A01", "north2east", autoSwap = false)
        assertTrue(result.success, "应成功：${result.message}")
        assertContains(result.message, "预约成功")
        assertTrue(result.finalUrl.orEmpty().contains("/my/"))
    }

    @Test
    fun `写：换座后按「我的预约」的实际座位号判定成功`() = withLibraryServer { api, _ ->
        val result = api.swapSeat("A02", "north2east")
        assertTrue(result.success, "换座应成功：${result.message}")
        assertContains(result.message, "A02")
        // 复核那一枪真的打到了服务器（否则服务端状态不会翻）
        assertTrue(swapped.get(), "应走过 /updateseat/")
    }

    @Test
    fun `写：取消预约以「我的预约消失了」判定`() = withLibraryServer { api, _ ->
        val result = api.executeAction("${LibraryPages.BASE_URL}/my/?cancel=1&ri=88")
        assertTrue(result.success, "取消应成功：${result.message}")
        assertTrue(cancelled.get(), "应打过 cancel 那条地址")
    }

    // ══════ 会话失效被认出来 ══════

    @Test
    fun `会话失效：响应体是登录页时抛共享基类的异常，且实现 SessionExpiredFailure`() {
        withLibraryServer { api, _ ->
            // 让 /qspace 回一个登录页：`LibraryApi` 的请求包装层读了 body 就会判成「被弹到登录页了」，
            // 于是抛会话失效异常（搬迁前那一行是 `throw AuthExpiredException("图书馆")`）。
            loginMode.set(true)
            val e = assertFailsWith<SessionExpiredException> { api.getFloorAreas("xingqing2floor") }
            assertEquals("图书馆", e.siteName)
            assertEquals("图书馆登录态已失效", e.message)
            // 与 :app 的 AuthExpiredException 同一个来路：共享的 ViewModel/屏按这个标记接口认领「静默重登」
            assertIs<SessionExpiredFailure>(e)
            // 而且它仍是 IOException —— 站点层按 IOException 记「登录失败、进冷却」，父类型不能换
            assertIs<java.io.IOException>(e)
        }
    }
}
