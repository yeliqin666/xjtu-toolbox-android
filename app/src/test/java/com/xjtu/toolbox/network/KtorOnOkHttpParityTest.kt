package com.xjtu.toolbox.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.CookieManager
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.java.net.cookiejar.JavaNetCookieJar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **差分测试**：同一个 `OkHttpClient` 上，用纯 okhttp 发一次、用 Ktor 的 API 发一次，
 * 服务端看到的必须是同一个请求，客户端拿到的必须是同一个响应。
 *
 * 为什么值得单独写：把 78 个文件从 okhttp 搬到 Ktor 的前提是
 * [asToolboxKtorClient] 那句注释里的承诺 ——「引擎、cookie jar、拦截器、跳转、超时全都不变，
 * 只换写法」。这类承诺不能靠读文档，只能靠真 socket 对着跑：这里起一个 JDK 自带的
 * `com.sun.net.httpserver.HttpServer`（不引任何新依赖），把它看到的 method/path/header/body
 * 记下来逐条比对。
 *
 * 会话相关的那三条（cookie 由同一个 jar 管、跟随跳转、UA 补全）是**最要命的三条**：
 * 搬错了不会编译报错，只会在用户那儿表现成「登录态莫名失效」「学校站点被跳转吃掉」。
 */
class KtorOnOkHttpParityTest {

    private data class Seen(
        val method: String,
        val path: String,
        val ua: String?,
        val referer: String?,
        val cookie: String?,
        val contentType: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val seen = Collections.synchronizedList(mutableListOf<Seen>())

    /** 每个请求都记一条，并按路径给出固定响应。 */
    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        seen += Seen(
            method = exchange.requestMethod,
            path = exchange.requestURI.toString(),
            ua = exchange.requestHeaders.getFirst("User-Agent"),
            referer = exchange.requestHeaders.getFirst("Referer"),
            cookie = exchange.requestHeaders.getFirst("Cookie"),
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            body = body,
        )
        when {
            exchange.requestURI.path == "/redirect" -> {
                exchange.responseHeaders.add("Location", "/echo")
                exchange.sendResponseHeaders(302, -1)
            }
            exchange.requestURI.path == "/cookie" -> {
                exchange.responseHeaders.add("Set-Cookie", "sid=abc123; Path=/")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("cookie-set".toByteArray())
            }
            else -> {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("echo:${exchange.requestMethod}:$body".toByteArray())
            }
        }
        exchange.close()
    }

    private lateinit var okhttp: OkHttpClient
    private lateinit var ktor: io.ktor.client.HttpClient

    @Before
    fun setUp() {
        seen.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/", ::handle)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"

        // 与 :app 生产同形：从 HttpClients.base 派生（带上补 UA 的拦截器）+ 一个真 cookie jar
        okhttp = HttpClients.base.newBuilder()
            .cookieJar(JavaNetCookieJar(CookieManager()))
            .build()
        ktor = okhttp.asToolboxKtorClient()
    }

    @After
    fun tearDown() {
        // ⚠️ **故意不 close 这个 Ktor 客户端**：它会从 OkHttpClient 派生一个客户端，而 okhttp 的
        // newBuilder() 共用同一个 Dispatcher ⇒ close() 会把 HttpClients.base 的 Dispatcher 也关掉，
        // 之后全进程所有请求都拓 `executor rejected`。这条是 KtorOnOkHttpParityTest 拓出来的
        // （先写过 close，后两个用例全红），也是 asToolboxKtorClient 的 KDoc 里那条「建了就留着」的由来。
        server.stop(0)
    }

    /** Content-Type 的 charset 大小写与参数写法两边天生不同，这里只比主类型。 */
    private fun mediaTypeOf(raw: String?): String? = raw?.substringBefore(';')?.trim()?.lowercase()

    @Test
    fun `GET 与 POST 的 method_path_UA_body 与纯 okhttp 完全一致`() {
        val url = "$base/echo?a=1&b=%E4%B8%AD"

        // ① 纯 okhttp
        okhttp.newCall(
            Request.Builder().url(url).header("Referer", "https://jwxt.xjtu.edu.cn/").get().build()
        ).execute().use { assertEquals(200, it.code) }
        val rawGet = seen.removeLast()

        // ② 同一个 client，改用 Ktor 的 API
        runBlocking {
            val resp = ktor.get(url) { header("Referer", "https://jwxt.xjtu.edu.cn/") }
            assertEquals(200, resp.status.value)
            assertTrue("响应体应与纯 okhttp 那条同形", resp.bodyAsText().startsWith("echo:GET:"))
        }
        val ktorGet = seen.removeLast()

        assertEquals(rawGet.method, ktorGet.method)
        assertEquals("url 里的查询参数与百分号编码必须一致", rawGet.path, ktorGet.path)
        assertEquals("没有自写 UA 的请求都要被 HttpClients.base 的拦截器补成 APP_UA", APP_UA, rawGet.ua)
        assertEquals(APP_UA, ktorGet.ua)
        assertEquals(rawGet.referer, ktorGet.referer)

        // ③ POST：两边都发同样的字节
        val payload = "grant_type=password&x=1"
        okhttp.newCall(
            Request.Builder().url("$base/post")
                .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        ).execute().use { assertEquals(200, it.code) }
        val rawPost = seen.removeLast()

        runBlocking {
            ktor.post("$base/post") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(payload)
            }
        }
        val ktorPost = seen.removeLast()

        assertEquals("POST", rawPost.method)
        assertEquals(rawPost.method, ktorPost.method)
        assertEquals(rawPost.path, ktorPost.path)
        assertEquals("请求体必须逐字节一致", rawPost.body, ktorPost.body)
        assertEquals(payload, ktorPost.body)
        assertEquals(mediaTypeOf(rawPost.contentType), mediaTypeOf(ktorPost.contentType))
    }

    /**
     * Ktor 默认会塞 `User-Agent: ktor-client`（引擎层，关不掉），而 App 的 UA 由
     * `HttpClients.base` 的拦截器在「没有 UA 时」补 —— 所以底座必须用 DefaultRequest 把默认 UA
     * 改回 APP_UA，而调用方自己写的 UA 仍要能覆盖（扫码那套 SuperApp 后缀靠它）。
     */
    @Test
    fun `UA 默认是 APP_UA，调用方显式写的 UA 优先生效`() {
        runBlocking { ktor.get("$base/echo").bodyAsText() }
        assertEquals("默认必须是 APP_UA，而不是 ktor-client", APP_UA, seen.removeLast().ua)

        val custom = APP_UA + SUPERAPP_UA_SUFFIX
        runBlocking { ktor.get("$base/echo") { header("User-Agent", custom) }.bodyAsText() }
        assertEquals("调用方写了 UA 就不能被默认值顶掉", custom, seen.removeLast().ua)
    }

    @Test
    fun `cookie 由同一个 jar 管：okhttp 写的、Ktor 读得到（否则会话必断）`() {
        // ① 用 okhttp 拿一个 Set-Cookie
        okhttp.newCall(Request.Builder().url("$base/cookie").get().build()).execute().use { it.close() }

        // ② 同一个 client、改用 Ktor 发下一个请求：必须带上刚设的 cookie
        val cookieAtServer = runBlocking {
            ktor.get("$base/echo").bodyAsText()
            seen.removeLast().cookie
        }
        assertTrue(
            "Ktor 走的是同一个 OkHttpClient，cookie 必须还在（实得 $cookieAtServer）",
            cookieAtServer?.contains("sid=abc123") == true,
        )
    }

    @Test
    fun `跟随跳转与 UA 补全仍是那个 client 在负责`() {
        val finalStatus = runBlocking { ktor.get("$base/redirect").status.value }
        assertEquals(200, finalStatus)
        val hops = seen.toList()
        assertEquals("应该看到跳转的两跳", 2, hops.size)
        assertEquals("/redirect", hops[0].path)
        assertEquals("/echo", hops[1].path)
        assertTrue("每一跳都要带 APP_UA（统一认证的 TGC 绑定 UA）", hops.all { it.ua == APP_UA })
    }

    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
