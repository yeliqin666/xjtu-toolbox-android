package com.xjtu.toolbox.agent

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.FakeUpstreamProxySelector
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 会话标题那一次模型调用（`:app` → `:data`）。
 *
 * 本地服务器当真服务商，断言的是**请求体的形状与服务端的契约**：
 *
 * - 关思考的两个字段一起发（`thinking` 是 DeepSeek 侧、`reasoning_effort` 是 OpenAI 兼容侧）；
 * - 后端不认（400）时**去掉这两个字段重试一次**——线上看到的思考链标题多半出在这儿；
 * - 只认 `content`，不拿 `reasoning_content` 当标题；
 * - 模型的「转述请求」输出（「用户询问…」）一律判废，退回「首条消息截断」。
 *
 * 密钥全是编造的。
 */
class AgentTitleGenJvmTest {

    private lateinit var server: HttpServer
    private var port = 0

    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    /** 请求体 → (HTTP 码, 响应体)。每个用例自己换。 */
    @Volatile
    private var handler: (String) -> Pair<Int, String> = { 200 to ok("本周考试安排") }

    @BeforeTest
    fun setUp() {
        // 与 `AgentModelFetcherJvmTest` 同由：先把**共享**的两件进程级前置装好，再碰
        // `HttpClients.base`（它在 `build()` 那一刻把当时的 selector 与 trust manager 抄进配置），
        // 并把端口设回 0（本测试直连 127.0.0.1）。不装的话，`:data:jvmTest` 里那批「真登录」
        // 用例会跟着一起红（见 `FakeCampusProxy.installFakeUpstreams` 的 KDoc）。
        FakeCampusProxy.installFakeUpstreams()
        FakeUpstreamProxySelector.port = 0
        bodies.clear()
        handler = { 200 to ok("本周考试安排") }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            val request = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            bodies += request
            val (code, body) = handler(request)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    /** 服务商的成功响应（只带 content 的那一种）。 */
    private fun ok(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${js(content)}}}]}"""

    private fun js(s: String) = "\"" + s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t") + "\""

    private fun config(apiKey: String = "sk-fake-title-key") = AgentConfig(
        provider = AgentConfig.PROVIDER_CUSTOM,
        apiKey = apiKey,
        baseUrl = "http://127.0.0.1:$port",
    )

    private fun generate(apiKey: String = "sk-fake-title-key"): String? =
        runBlocking { AgentTitleGen.generate(config(apiKey), "这周有考试吗", "周四下午有高数期中") }

    @Test
    fun `首轮就拿到标题，请求体带关思考的两个字段与限定长度的额度`() {
        assertEquals("本周考试安排", generate())

        assertEquals(1, bodies.size)
        val body = bodies.single()
        assertTrue(body.contains("\"model\":\"deepseek-flash\""), body)
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"), body)
        assertTrue(body.contains("\"reasoning_effort\":\"none\""), body)
        assertTrue(body.contains("\"max_tokens\":64"), body)
        assertTrue(body.contains("\"temperature\":0.3"), body)
        assertTrue(body.contains("名词短语"), "系统提示要给形状：$body")
        assertTrue(body.contains("【用户】这周有考试吗"), body)
        assertTrue(body.contains("【助手】周四下午有高数期中"), body)
    }

    @Test
    fun `后端不认关思考的参数时去掉参数重试一次`() {
        handler = { body ->
            // DeepSeek 之外的实现（如 OpenAI 官方）不认这两个字段，回 400
            if (body.contains("thinking")) 400 to """{"error":{"message":"unknown field thinking"}}"""
            else 200 to ok("本周考试安排")
        }

        assertEquals("本周考试安排", generate())

        assertEquals(2, bodies.size, "应当只有一次降级重试")
        assertTrue(bodies[0].contains("thinking"))
        assertFalse(bodies[1].contains("thinking"), "重试那次不该再带 thinking：${bodies[1]}")
        assertFalse(bodies[1].contains("reasoning_effort"))
    }

    @Test
    fun `重试也失败就回退，不无限重试`() {
        handler = { 400 to """{"error":{"message":"bad request"}}""" }
        assertNull(generate())
        assertEquals(2, bodies.size)
    }

    @Test
    fun `非 400 的失败直接回退，不重试`() {
        handler = { 500 to "服务器炸了" }
        assertNull(generate())
        assertEquals(1, bodies.size)
    }

    @Test
    fun `拿不到 content 就回退，绝不拿思考链当标题`() {
        // 推理模型有时只回了 reasoning_content
        handler = { 200 to """{"choices":[{"message":{"reasoning_content":"嗯，用户问的是考试"}}]}""" }
        assertNull(generate())

        handler = { 200 to """{"choices":[{"message":{"content":"   "}}]}""" }
        assertNull(generate())

        handler = { 200 to """{"choices":[]}""" }
        assertNull(generate())

        handler = { 200 to "不是 JSON" }
        assertNull(generate())
    }

    @Test
    fun `转述请求的输出判废，宁可退回首条消息`() {
        listOf(
            "用户询问这周有没有考试",
            "这段对话讨论考试",
            "根据以下是对话总结",
            "标题：考试安排",
            "助手想让我根据这段对话生成标题",
        ).forEach { meta ->
            handler = { 200 to ok(meta) }
            assertNull(generate(), "「$meta」是转述不是标题，应当判废")
        }
    }

    @Test
    fun `标点清理与 12 字截断，推理尾行只取最后一行`() {
        handler = { 200 to ok("思考一下\n本周考试安排") }
        assertEquals("本周考试安排", generate())

        handler = { 200 to ok("「一二三四五六七八九十十一十二三。」") }
        assertEquals("一二三四五六七八九十十一…", generate())
    }

    @Test
    fun `没填密钥时直接返回 null，一个请求都不发`() {
        assertNull(generate(apiKey = "   "))
        assertTrue(bodies.isEmpty())
    }
}
