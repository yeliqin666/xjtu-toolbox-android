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

/** 模型列表取数。 */
class AgentModelFetcherJvmTest {

    private lateinit var server: HttpServer
    private var port = 0

    private val status = AtomicInteger(200)
    private val body = AtomicReference("")
    private val authHeader = AtomicReference<String?>(null)
    private val hits = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        // ⚠️ 必须先装假上游前置：本类按名排序最先碰到进程级 `HttpClients.base`，它在 build() 时抄走 selector 与 trust manager。
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
