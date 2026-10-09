package com.xjtu.toolbox.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.utils.io.readRemaining
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray

/**
 * [ReAuthCall] 与 [toKtorReply] 的机制测试 —— 这两件事都是「搬了才敢动 60 个调用点」的前提：
 *
 * 1. **重认证重放**：401 → 回调重认证 → 原样重放一次 → 仍 401 就交给会话抛
 *    （原 `executeWithReAuth` 的 `retried` 语义）；
 * 2. **peek 语义**：okhttp 有 `peekBody(8192)`，Ktor 没有 —— 文本类整读进内存并留前缀；
 *    二进制类不读、保持流式（下载几十 MB 的 PDF 不能整份进堆）。
 *
 * 用 JDK 自带的 `com.sun.net.httpserver.HttpServer`（真 socket、不引依赖），
 * 因为这两件事都发生在「连接与流的层面」，MockEngine 层面测不出来。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/network/ReAuthCallTest.kt`，随 [ReAuthCall] /
 * [KtorReply] 一起搬进 `:core:jvmTest`。两处刻意的差异：
 *  1. JUnit → `kotlin.test`（:core 的测试壳统一是这个，断言逐条保留；kotlin.test 的
 *     断言信息是**最后一个参数**，所以下面一律用具名 `message =`，与 JUnit 的参数序无关）；
 *  2. 客户端从 `HttpClients.base.asToolboxKtorClient()`（:app 的生产 OkHttpClient）换成
 *     [createToolboxClient] —— 它用的还是同一个 OkHttp 引擎（`ToolboxEngine.jvm.kt`），
 *     而本测试要验的是**重放与 peek 两件事本身**，不依赖 :app 那套 UA/WebVPN 拦截器。
 */
class ReAuthCallTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var http: HttpClient
    private val paths = Collections.synchronizedList(mutableListOf<String>())

    /** 置为 true 后 `/guarded` 不再返回 401（模拟「重认证成功了」）。 */
    private val reAuthenticated = AtomicBoolean(false)

    private val bigHtml = "<html><body>" + "课表".repeat(4000) + "</body></html>"
    private val pdfBytes = ByteArray(300_000) { (it % 251).toByte() }

    private fun handle(exchange: HttpExchange) {
        paths += exchange.requestURI.path
        when (exchange.requestURI.path) {
            "/guarded" -> if (reAuthenticated.get()) respond(exchange, 200, "text/html; charset=utf-8", "ok".toByteArray())
            else respond(exchange, 401, "text/html; charset=utf-8", "<html>CAS 登录页</html>".toByteArray())
            "/big" -> respond(exchange, 200, "text/html; charset=utf-8", bigHtml.toByteArray())
            "/binary" -> respond(exchange, 200, "application/pdf", pdfBytes)
            else -> respond(exchange, 404, "text/plain", "nope".toByteArray())
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    /** 每个测试自己起停服务器（`com.sun.net.httpserver` 是进程级资源，起停比共享更干净）。 */
    private fun <T> withServer(block: () -> T): T {
        paths.clear()
        reAuthenticated.set(false)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/", ::handle)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        http = createToolboxClient()
        try {
            return block()
        } finally {
            // 与 KtorOnOkHttpParityTest 同一条规矩：绝不 close 客户端（会关掉共用 Dispatcher）
            server.stop(0)
        }
    }

    private fun authFailureOn401(reply: KtorReply) = reply.status == 401

    @Test
    fun `401 之后重认证并原样重放一次，第二次成功即返回`() = withServer {
        runBlocking {
            var reAuthCount = 0
            val reply = ReAuthCall(http).execute(
                block = { url("$base/guarded") },
                isAuthFailure = ::authFailureOn401,
                onAuthFailure = { reAuthCount++; reAuthenticated.set(true) },
                onRepeatFailure = { throw AssertionError("不该走到第二次失败") },
            )
            assertEquals(200, reply.status)
            assertEquals("ok", reply.text())
            assertEquals(1, reAuthCount, message = "重认证只做一次")
            assertEquals(listOf("/guarded", "/guarded"), paths.toList(), message = "应该正好发两次（原请求 + 重放）")
        }
    }

    @Test
    fun `重放后仍失效时交给会话抛（这里用异常代替 AuthExpiredException）`() = withServer {
        runBlocking {
            var detected = 0
            val e = assertFailsWith<IllegalStateException> {
                ReAuthCall(http).execute(
                    block = { url("$base/guarded") },
                    isAuthFailure = ::authFailureOn401,
                    onAuthFailureDetected = { detected++ },
                    onAuthFailure = { /* 重认证“失败”了：不动 flag */ },
                    onRepeatFailure = { throw IllegalStateException("会话在这里抛 AuthExpiredException") },
                )
            }
            assertEquals("会话在这里抛 AuthExpiredException", e.message)
            assertEquals(2, detected, message = "两次失效都要留下判据（状态码/URL/preview 的日志）")
            assertEquals(2, paths.size, message = "只重放一次，不无限重试")
        }
    }

    @Test
    fun `文本类：正文完整读入，peek 是前 8KB`() = withServer {
        runBlocking {
            val reply = http.get("$base/big").toKtorReply()
            assertEquals(200, reply.status)
            assertEquals(bigHtml.toByteArray().size, reply.bytes.size, message = "完整正文必须一个字节不少")
            assertEquals(bigHtml, reply.text())
            // peek 是**前 8192 个字节**（按字节截，所以字符数会少于 8192：中文一个字符占 3 字节，
            // 而且截断处可能把一个多字节字符切成两半 —— 判定登录页不需要在意这些）
            assertEquals(
                reply.bytes.copyOf(KtorReply.PEEK_LIMIT).decodeToString(),
                reply.peek,
                message = "peek 必须正好等于正文前 8192 字节",
            )
            assertTrue(reply.peek!!.length < reply.text().length, message = "peek 应该是前缀而不是全文")
            assertTrue(reply.peek!!.startsWith("<html><body>"))
        }
    }

    @Test
    fun `二进制类：不 peek、不读进内存，流式读出来仍然完整`() = withServer {
        runBlocking {
            val reply = http.get("$base/binary").toKtorReply()
            assertEquals(200, reply.status)
            assertNull(reply.peek, message = "二进制不可能是登录页，照原实现不 peek")
            assertEquals(0, reply.bytes.size, message = "不该整份进内存")
            val streamed = reply.stream().readRemaining().readByteArray()
            assertEquals(pdfBytes.size, streamed.size, message = "流式读出来必须逐字节等于服务端发的")
            assertTrue(pdfBytes.contentEquals(streamed))
        }
    }

    @Test
    fun `文本类判定与 SiteSession 的 isTextualResponse 同一套`() {
        assertTrue(isTextualContentType(null))
        assertTrue(isTextualContentType("text/html; charset=utf-8"))
        assertTrue(isTextualContentType("application/json"))
        assertTrue(isTextualContentType("application/xml"))
        assertTrue(isTextualContentType("APPLICATION/JAVASCRIPT"))
        assertTrue(!isTextualContentType("application/pdf"))
        assertTrue(!isTextualContentType("image/png"))
        assertTrue(!isTextualContentType("application/octet-stream"))
    }
}
