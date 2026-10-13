package com.xjtu.toolbox.agent

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.FakeUpstreamProxySelector
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject

/**
 * 流式传输（[SseAgentTransport]）—— `:app` 的 `AgentRunner.streamOnce` 搬进 `:data` 之后的那一份。
 *
 * 本地服务器当真服务商（真发 HTTP、真按 SSE 回），断言的是三件事：
 *
 *  - **聚合口径**：正文 / 思维链 / tool_calls（分片下发按 index 拼） / finish_reason / usage；
 *  - **请求的形状**：URL 由 `effectiveBaseUrl` 拼、三条 header、请求体逐字就是循环给的那一份；
 *  - **失败口径**：非 2xx 抛的是 [agentHttpErrorText] 那一句（与设置页同源，两端必须一致）。
 *
 * 密钥全是编造的（ASCII：OkHttp 会校 header 值，非 ASCII 直接抛），从不发往真服务商。
 */
class SseAgentTransportJvmTest {

    private lateinit var server: HttpServer
    private var port = 0

    private val path = AtomicReference("")
    private val authHeader = AtomicReference<String?>(null)
    private val acceptHeader = AtomicReference<String?>(null)
    private val contentTypeHeader = AtomicReference<String?>(null)
    private val postedBody = AtomicReference<String?>(null)

    /** (HTTP 码, 响应体)。每个用例自己换。 */
    @Volatile
    private var response: Pair<Int, String> = 200 to ""

    @BeforeTest
    fun setUp() {
        // 与 `AgentModelFetcherJvmTest` 同由：先把**共享**的两件进程级前置装好，再碰
        // `HttpClients.base`（它在 `build()` 那一刻把当时的 selector 与 trust manager 抄进配置），
        // 并把端口设回 0（本测试直连 127.0.0.1）。不装的话，`:data:jvmTest` 里那批「真登录」
        // 用例会跟着一起红（见 `FakeCampusProxy.installFakeUpstreams` 的 KDoc）。
        FakeCampusProxy.installFakeUpstreams()
        FakeUpstreamProxySelector.port = 0
        // 传输层把增量回调派发到 `Dispatchers.Main.immediate`（上屏口径）；JVM 单测里那份要自己装。
        Dispatchers.setMain(UnconfinedTestDispatcher())
        port = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v1/chat/completions") { exchange ->
            path.set(exchange.requestURI.path)
            authHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            acceptHeader.set(exchange.requestHeaders.getFirst("Accept"))
            contentTypeHeader.set(exchange.requestHeaders.getFirst("Content-Type"))
            postedBody.set(String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8))
            val (code, body) = response
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        Dispatchers.resetMain()
    }

    private fun config(provider: String = AgentConfig.PROVIDER_CUSTOM, apiKey: String = FAKE_KEY) = AgentConfig(
        provider = provider,
        apiKey = apiKey,
        baseUrl = "http://127.0.0.1:$port/v1",
    )

    @Test
    fun 逐条聚合正文_思维链_分片工具调用与用量() = runBlocking {
        response = 200 to SSE_FULL
        val deltas = mutableListOf<String>()
        val reasoningDeltas = mutableListOf<String>()

        val result = SseAgentTransport().stream(
            config(), JsonObject(emptyMap()),
            onDelta = { deltas += it },
            onReasoningDelta = { reasoningDeltas += it },
        )

        assertEquals("你好，世界", result.content)
        assertEquals("先想想", result.reasoningContent)
        // id / name 在前一片、arguments 分两片 —— 按 index 拼回一条完整的调用
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls[0].id)
        assertEquals("get_grades", result.toolCalls[0].name)
        assertEquals("""{"term":"2025-2026-1"}""", result.toolCalls[0].arguments)
        assertEquals("tool_calls", result.finishReason)
        assertEquals(123L, result.totalTokens)
        // 增量是当场回调的，顺序 = 服务端下发顺序
        assertEquals(listOf("你好", "，世界"), deltas)
        assertEquals(listOf("先想想"), reasoningDeltas)
    }

    @Test
    fun 请求的形状与搬迁前一致() = runBlocking {
        response = 200 to "data: [DONE]\n"
        val reqBody = JsonObject(mapOf("model" to kotlinx.serialization.json.JsonPrimitive("deepseek-flash")))

        SseAgentTransport().stream(config(apiKey = FAKE_KEY), reqBody, {}, {})

        assertEquals("/v1/chat/completions", path.get())
        assertEquals("Bearer $FAKE_KEY", authHeader.get())
        assertEquals("text/event-stream", acceptHeader.get())
        // OkHttp 会给 media type 补上 charset（搬迁前就是这一串）
        assertEquals("application/json; charset=utf-8", contentTypeHeader.get())
        // 循环拼好的那一份原样发出去（传输层不改请求体）
        assertEquals(reqBody.toString(), postedBody.get())
    }

    @Test
    fun 非_2xx_抛的是同一句中文() = runBlocking {
        // 401：服务端原文不进文案（用户看的是「key 无效」和去哪改）
        response = 401 to """{"error":{"message":"Invalid API key"}}"""
        var msg = assertFailsWith<RuntimeException> {
            SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        }.message
        assertEquals("API Key 无效，请到屁岱设置里检查。", msg)

        response = 429 to "{}"
        msg = assertFailsWith<RuntimeException> {
            SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        }.message
        assertEquals("请求太频繁，请稍后再试。", msg)

        // 500/502/503 有自己的话（服务端原文多半是网关页）
        response = 502 to "<html>bad gateway</html>"
        msg = assertFailsWith<RuntimeException> {
            SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        }.message
        assertEquals("AI 服务商暂时繁忙（HTTP 502），请稍后再试。", msg)

        // 参数类错误保留服务端原文，便于排查
        response = 400 to """{"error":{"message":"model not found"}}"""
        msg = assertFailsWith<RuntimeException> {
            SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        }.message
        assertEquals("LLM 请求失败：model not found", msg)

        // 连错误 JSON 都不是：退回 HTTP 码，也要有可读的一句话
        response = 418 to "nope"
        msg = assertFailsWith<RuntimeException> {
            SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        }.message
        assertEquals("LLM 请求失败：HTTP 418", msg)
    }

    @Test
    fun 坏行与非_data_行被跳过_done之后不再读() = runBlocking {
        response = 200 to SSE_NOISE
        val deltas = mutableListOf<String>()

        val result = SseAgentTransport().stream(config(), JsonObject(emptyMap()), onDelta = { deltas += it }, onReasoningDelta = {})

        assertEquals("能用的那一片", result.content)
        assertEquals(listOf("能用的那一片"), deltas)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals("stop", result.finishReason)
    }

    private companion object {
        /** 编造的密钥：这一份永远不出网。 */
        const val FAKE_KEY = "sk-fake-sse-011"

        val SSE_FULL = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"先想想"},"finish_reason":null}]}""",
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
            """data: {"choices":[{"delta":{"content":"，世界","tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_grades","arguments":"{\"term\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"2025-2026-1\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """data: {"usage":{"total_tokens":123}}""",
            "data: [DONE]",
        ).joinToString("\n")

        val SSE_NOISE = listOf(
            "",
            ": 这是一条 SSE 注释",
            "event: message",
            "id: 7",
            "data: {这不是合法 JSON",
            """data: {"choices":[]}""",
            """data: {"choices":[{"delta":{"content":"能用的那一片"}}]}""",
            "data: [DONE]",
            // [DONE] 之后的东西一概不读（真服务端不会发，但坏掉的网关会）
            """data: {"choices":[{"delta":{"content":"不该出现"}}]}""",
        ).joinToString("\n")
    }
}
