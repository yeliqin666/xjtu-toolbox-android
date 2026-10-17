package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.error.SessionExpiredFailure
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** 图书馆这条竖切在 JVM 上跑通的证明。 */
class LibraryDataLayerJvmTest {

    private lateinit var server: HttpServer
    private lateinit var fake: LibraryFakeUpstream
    private lateinit var base: String

    /** 起本地服务器 + 一个把图书馆域名重写到本地的客户端。 */
    private fun <T> withLibraryServer(block: suspend (LibraryApi, OkHttpClient) -> T): T {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        base = "http://127.0.0.1:${server.address.port}"
        fake = LibraryFakeUpstream(base)
        server.createContext("/", fake::handle)
        server.start()
        val local = base.toHttpUrl()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != LibraryFakeUpstream.LIBRARY_HOST) {
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
        assertTrue(bytes != null && bytes.size == fake.jpegBytes.size, "应原样返回 JPEG 字节")
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
        assertTrue(fake.swapped.get(), "应走过 /updateseat/")
    }

    @Test
    fun `写：取消预约以「我的预约消失了」判定`() = withLibraryServer { api, _ ->
        val result = api.executeAction("${LibraryPages.BASE_URL}/my/?cancel=1&ri=88")
        assertTrue(result.success, "取消应成功：${result.message}")
        assertTrue(fake.cancelled.get(), "应打过 cancel 那条地址")
    }

    // ══════ 会话失效被认出来 ══════

    @Test
    fun `会话失效：响应体是登录页时抛共享基类的异常，且实现 SessionExpiredFailure`() {
        withLibraryServer { api, _ ->
            // /qspace 回登录页 ⇒ `LibraryApi` 判成被弹回登录页，抛会话失效异常。
            fake.loginMode.set(true)
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
