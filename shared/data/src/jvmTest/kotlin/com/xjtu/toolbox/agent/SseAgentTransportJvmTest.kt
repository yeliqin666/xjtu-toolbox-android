package com.xjtu.toolbox.agent

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.FakeUpstreamProxySelector
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
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

/** 流式传输（[SseAgentTransport]）。 */
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
    private val queuedResponses = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val requestCount = AtomicInteger()

    @BeforeTest
    fun setUp() {
        FakeCampusProxy.installFakeUpstreams()
        FakeUpstreamProxySelector.port = 0
        Dispatchers.setMain(UnconfinedTestDispatcher())
        port = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val handler = com.sun.net.httpserver.HttpHandler { exchange ->
            path.set(exchange.requestURI.path)
            authHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            acceptHeader.set(exchange.requestHeaders.getFirst("Accept"))
            contentTypeHeader.set(exchange.requestHeaders.getFirst("Content-Type"))
            postedBody.set(String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8))
            requestCount.incrementAndGet()
            val (code, body) = queuedResponses.poll() ?: response
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/chat/completions", handler)
        server.createContext("/v1/responses", handler)
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

    private fun responsesConfig() = config().copy(apiFormat = AgentConfig.API_RESPONSES)

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
        assertEquals("API Key 无效，请到小岱设置里检查。", msg)

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

    @Test
    fun unfinishedToolFragmentsReconnectWithoutExecutingPartialCalls() = runBlocking {
        queuedResponses += 200 to """data: {"choices":[{"delta":{"reasoning_content":"","tool_calls":[{"index":0,"id":"partial","function":{"name":"get_grades","arguments":"{"}}]}}]}"""
        response = 200 to SSE_FULL
        val result = SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        assertEquals(2, requestCount.get())
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls.single().id)
    }

    @Test
    fun truncatedAnswerDoesNotBecomeSuccessfulOrReplayVisibleText() = runBlocking {
        response = 200 to """data: {"choices":[{"delta":{"content":"partial"}}]}"""
        val deltas = mutableListOf<String>()
        assertFailsWith<java.io.IOException> {
            SseAgentTransport().stream(config(), JsonObject(emptyMap()), { deltas += it }, {})
        }
        assertEquals(listOf("partial"), deltas)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun chat路径也认delta_reasoning() = runBlocking {
        response = 200 to listOf(
            """data: {"choices":[{"delta":{"reasoning":"中转的思考"}}]}""",
            """data: {"choices":[{"delta":{"content":"答"},"finish_reason":"stop"}]}""",
            "data: [DONE]",
        ).joinToString("\n")
        val result = SseAgentTransport().stream(config(), JsonObject(emptyMap()), {}, {})
        assertEquals("中转的思考", result.reasoningContent)
        assertEquals("答", result.content)
    }

    @Test
    fun responses_聚合正文_推理摘要_分片工具调用与用量() = runBlocking {
        response = 200 to SSE_RESPONSES_FULL
        val deltas = mutableListOf<String>()
        val reasoningDeltas = mutableListOf<String>()

        val result = SseAgentTransport().stream(
            responsesConfig(), JsonObject(emptyMap()),
            onDelta = { deltas += it },
            onReasoningDelta = { reasoningDeltas += it },
        )

        assertEquals("/v1/responses", path.get())
        assertEquals("你好，世界", result.content)
        // 换了一段摘要（summary_index 变了）中间补空行
        assertEquals("先想想\n\n再查成绩", result.reasoningContent)
        assertEquals(listOf("先想", "想", "\n\n再查成绩"), reasoningDeltas)
        assertEquals(listOf("你好", "，世界"), deltas)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls[0].id)
        assertEquals("get_grades", result.toolCalls[0].name)
        assertEquals("""{"term":"2025-2026-1"}""", result.toolCalls[0].arguments)
        assertEquals("tool_calls", result.finishReason)
        assertEquals(123L, result.totalTokens)
    }

    @Test
    fun responses_被截断记成length() = runBlocking {
        response = 200 to listOf(
            """data: {"type":"response.output_text.delta","output_index":0,"delta":"写到一半"}""",
            """data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"total_tokens":9}}}""",
        ).joinToString("\n")
        val result = SseAgentTransport().stream(responsesConfig(), JsonObject(emptyMap()), {}, {})
        assertEquals("length", result.finishReason)
        assertEquals("写到一半", result.content)
        assertEquals(9L, result.totalTokens)
    }

    @Test
    fun responses_没等到结束事件就断了按中断处理() = runBlocking {
        response = 200 to """data: {"type":"response.output_text.delta","output_index":0,"delta":"partial"}"""
        val deltas = mutableListOf<String>()
        assertFailsWith<java.io.IOException> {
            SseAgentTransport().stream(responsesConfig(), JsonObject(emptyMap()), { deltas += it }, {})
        }
        assertEquals(listOf("partial"), deltas)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun responses_失败事件直接抛() = runBlocking {
        response = 200 to """data: {"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"boom"}}}"""
        val msg = assertFailsWith<RuntimeException> {
            SseAgentTransport().stream(responsesConfig(), JsonObject(emptyMap()), {}, {})
        }.message
        assertEquals("LLM 请求失败：boom", msg)
    }

    @Test
    fun responses_非推理模型不认reasoning时去掉重试() = runBlocking {
        queuedResponses += 400 to """{"error":{"message":"Unsupported parameter: 'reasoning.effort' is not supported with this model."}}"""
        response = 200 to SSE_RESPONSES_FULL
        val reqBody = kotlinx.serialization.json.buildJsonObject {
            put("model", kotlinx.serialization.json.JsonPrimitive("gpt-4.1"))
            put("reasoning", kotlinx.serialization.json.buildJsonObject {
                put("effort", kotlinx.serialization.json.JsonPrimitive("high"))
                put("summary", kotlinx.serialization.json.JsonPrimitive("auto"))
            })
        }
        val result = SseAgentTransport().stream(responsesConfig(), reqBody, {}, {})
        assertEquals(2, requestCount.get())
        assertTrue("reasoning" !in postedBody.get().orEmpty(), postedBody.get())
        assertEquals("你好，世界", result.content)
    }

    @Test
    fun responses_组织未验证时只去掉摘要() = runBlocking {
        queuedResponses += 400 to """{"error":{"message":"Your organization must be verified to generate reasoning summaries."}}"""
        response = 200 to SSE_RESPONSES_FULL
        val reqBody = kotlinx.serialization.json.buildJsonObject {
            put("reasoning", kotlinx.serialization.json.buildJsonObject {
                put("effort", kotlinx.serialization.json.JsonPrimitive("high"))
                put("summary", kotlinx.serialization.json.JsonPrimitive("auto"))
            })
        }
        SseAgentTransport().stream(responsesConfig(), reqBody, {}, {})
        assertEquals(2, requestCount.get())
        val sent = postedBody.get().orEmpty()
        assertTrue("\"effort\":\"high\"" in sent, sent)
        assertTrue("summary" !in sent, sent)
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

        val SSE_RESPONSES_FULL = listOf(
            "event: response.created",
            """data: {"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}""",
            """data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"先想"}""",
            """data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"想"}""",
            """data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":1,"delta":"再查成绩"}""",
            """data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"delta":"你好"}""",
            """data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"delta":"，世界"}""",
            """data: {"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_grades","arguments":""}}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":2,"delta":"{\"term\":"}""",
            // 有的实现分片里只带 item_id，不带 output_index
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"2025-2026-1\"}"}""",
            """data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"output_tokens":23,"total_tokens":123}}}""",
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
