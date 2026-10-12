package com.xjtu.toolbox.agent

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.FakeUpstreamProxySelector
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 模型列表取数（`:app` → `:data` 的那一条）。
 *
 * 与 `LibraryDataLayerJvmTest` 同一条纪律：**真跑**，不 mock 取数本身 ——
 * 起一个本地 HTTP 服务器当真服务商，让 `AgentModelFetcher` 自己拼 `{baseUrl}/models`、
 * 带上 `Authorization`、读响应、解析 `data[].id`。断言的是模型 id 的去重与排序、
 * 以及三种失败各自抛出可读的话（配置页靠这句话降级成手填）。
 *
 * ⚠️ 密钥全是编造的，从不发往真服务商。
 */
class AgentModelFetcherJvmTest {

    private lateinit var server: HttpServer
    private var port = 0

    private val status = AtomicInteger(200)
    private val body = AtomicReference("")
    private val authHeader = AtomicReference<String?>(null)
    private val hits = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        // ⚠️ 进程级的两件前置，顺序不能错（见 `FakeCampusProxy.installFakeUpstreams` 的 KDoc）：
        // `HttpClients.base` 是进程级 `by lazy`，它在 `build()` 那一刻把**当时的**默认 selector 与
        // 平台 trust manager 都抄进配置。`:data:jvmTest` 里那一大批「真登录」用例靠的就是那两份
        // **共享**前置（它们自己起假上游、再改 `FakeUpstreamProxySelector.port`）。本测试类姓
        // `agent`，按类名序排在 attendance/venue/card 之前，又是这个模块里**第一个**碰
        // `HttpClients.base` 的（下面 `AgentModelFetcher` 的 `private val client`）—— 不先装的话，
        // 抄进去的是系统默认 selector 与系统 CA，那一批用例随后全部指不到假上游
        //（selector 不对 ⇒“登录验证检测返回了异常数据”，信任库不对 ⇒ PKIX 失败），一次红一大片。
        FakeCampusProxy.installFakeUpstreams()
        // 本测试直连 127.0.0.1，把端口设回 0（进程默认状态，不走假上游代理）
        FakeUpstreamProxySelector.port = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/models") { exchange ->
            hits.incrementAndGet()
            authHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.get().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun config(apiKey: String = "sk-fake-model-list-key") = AgentConfig(
        provider = AgentConfig.PROVIDER_CUSTOM,
        apiKey = apiKey,
        baseUrl = "http://127.0.0.1:$port",
    )

    @Test
    fun `拉到的模型去重并排序，请求带上 Bearer 头`() {
        body.set("""{"object":"list","data":[{"id":"zz"},{"id":"aa"},{"id":"aa"},{"id":"mm"}]}""")

        val models = runBlocking { AgentModelFetcher.fetch(config()) }

        assertEquals(listOf("aa", "mm", "zz"), models)
        assertEquals("Bearer sk-fake-model-list-key", authHeader.get())
        assertEquals(1, hits.get())
    }

    @Test
    fun `列表里的条目缺 id 时单独丢掉，不连累整份`() {
        body.set("""{"data":[{"id":"a"},{"object":"model"},{"id":""},{"id":"b"}]}""")

        // 「没有 id 字段」的条目丢掉；申明成空串的照搬（与搬迁前一致：只过滤前者）
        assertEquals(listOf("", "a", "b"), runBlocking { AgentModelFetcher.fetch(config()) })
    }

    @Test
    fun `响应里没有 data 数组时报「格式无法解析」`() {
        body.set("""{"error":"nope"}""")

        val e = assertFailsWith<RuntimeException> { runBlocking { AgentModelFetcher.fetch(config()) } }
        assertEquals("响应格式无法解析", e.message)
    }

    @Test
    fun `服务商没返回任何模型时如实说，不返回空表`() {
        body.set("""{"data":[]}""")

        val e = assertFailsWith<RuntimeException> { runBlocking { AgentModelFetcher.fetch(config()) } }
        assertEquals("服务商未返回任何模型", e.message)
    }

    @Test
    fun `失败时优先用服务商给的 message，没有才退回 HTTP 码`() {
        status.set(401)
        body.set("""{"error":{"message":"Authentication Fails"}}""")
        val e = assertFailsWith<RuntimeException> { runBlocking { AgentModelFetcher.fetch(config()) } }
        assertEquals("Authentication Fails", e.message)

        body.set("不是 JSON")
        val e2 = assertFailsWith<RuntimeException> { runBlocking { AgentModelFetcher.fetch(config()) } }
        assertEquals("HTTP 401", e2.message)
    }

    @Test
    fun `没填密钥时直接报错，一个请求都不发`() {
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { AgentModelFetcher.fetch(config(apiKey = "  ")) }
        }
        assertEquals("请先填写 API Key", e.message)
        assertTrue(hits.get() == 0, "没密钥就不该发请求")
    }
}
