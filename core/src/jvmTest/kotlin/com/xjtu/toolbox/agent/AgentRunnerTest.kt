package com.xjtu.toolbox.agent

import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 调度循环（[AgentRunner]）—— 它从 `:app` 搬进 `:core` 时，把两处接线收成了缝：
 * 「发一次请求」是 [AgentTransport]、「工具表」是 [AgentTools]。
 *
 * 这一份测试**就是那两格缝的验收**：循环在完全不认识 okhttp、也不认识任何 `*Api` 的前提下
 * 跑完整（假传输给结果、假工具表返回文本），所以桌面端那个 `AgentHost` / 真实传输才有得接；
 * 而循环自己的行为（请求体、续写、熔断、限流分组、工具结果的原子提交、结果截断）逐条钉在这儿，
 * 搬动它时不会漂。
 *
 * 密钥全是编造的，从不出网。
 */
class AgentRunnerTest {

    // ── 假的缝 ────────────────────────────────────────────────────────────────

    /** 假的传输：每个用例给一串「这一轮服务端回什么」，请求体原样记下来供断言。 */
    private class FakeTransport(private val rounds: List<AgentStreamResult>) : AgentTransport {
        val bodies = mutableListOf<JsonObject>()
        val deltas = mutableListOf<String>()
        val reasoningDeltas = mutableListOf<String>()

        override suspend fun stream(
            config: AgentConfig,
            reqBody: JsonObject,
            onDelta: suspend (String) -> Unit,
            onReasoningDelta: suspend (String) -> Unit,
        ): AgentStreamResult {
            bodies += reqBody
            val round = rounds[bodies.size - 1]
            // 真传输层是边收边回调的（一个字一个字上屏），这里也照那个形状来
            if (round.reasoningContent.isNotEmpty()) {
                reasoningDeltas += round.reasoningContent
                onReasoningDelta(round.reasoningContent)
            }
            if (round.content.isNotEmpty()) {
                deltas += round.content
                onDelta(round.content)
            }
            return round
        }
    }

    /** 假的工具表：`sites` 决定限流分组（空 = 纯本地工具，不等），[answer] 是执行结果。 */
    private class FakeTools(
        private val sites: Map<String, String> = emptyMap(),
        private val answer: (String, String) -> String,
    ) : AgentTools {
        override val toolDefinitions: String = """[{"type":"function","function":{"name":"get_grades"}}]"""
        val calls = mutableListOf<Pair<String, String>>()

        override fun rateLimitKeyOf(toolName: String): String? = sites[toolName]

        override suspend fun execute(name: String, argsJson: String): String {
            calls += name to argsJson
            return answer(name, argsJson)
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private fun reply(
        content: String,
        toolCalls: List<AgentToolCall> = emptyList(),
        finish: String = "stop",
        tokens: Long? = null,
        reasoning: String = "",
    ) = AgentStreamResult(content, reasoning, toolCalls, finish, tokens)

    private fun call(id: String, name: String, args: String = "{}") = AgentToolCall(id, name, args)

    private fun messages(vararg extra: JsonElement) =
        mutableListOf<JsonElement>(buildJsonObject { put("role", "user"); put("content", "我成绩多少") }, *extra)

    private fun JsonElement.role() = jsonObject["role"]!!.jsonPrimitive.content

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonElement.text() = jsonPrimitive.content

    // ── 用例 ──────────────────────────────────────────────────────────────────

    @Test
    fun 纯文本一轮只有两条消息() = runBlocking {
        val transport = FakeTransport(listOf(reply("你好", tokens = 12)))
        val messages = messages()
        val usage = mutableListOf<Long>()
        val out = AgentRunner(FakeTools { _, _ -> "不该被调用" }, transport)
            .run(messages, AgentConfig(apiKey = "sk-fake-runner-001"), onUsage = { usage += it })

        assertEquals("你好", out)
        assertEquals(listOf("user", "assistant"), messages.map { it.role() })
        assertEquals("你好", messages[1].jsonObject.text("content"))
        assertEquals(listOf(12L), usage)
        // 增量是当场回调的（上屏口径），顺序 = 服务端下发顺序
        assertEquals(listOf("你好"), transport.deltas)
        // 这一轮的请求体：模型、工具清单、流式开关
        assertEquals("deepseek-flash", transport.bodies[0].text("model"))
        assertTrue(transport.bodies[0]["tools"] != null)
        assertEquals("auto", transport.bodies[0].text("tool_choice"))
    }

    @Test
    fun 工具一轮把调用与结果成对写回历史() = runBlocking {
        val transport = FakeTransport(
            listOf(
                reply("", listOf(call("call_1", "get_grades", """{"term":"2025-2026-1"}""")), finish = "tool_calls"),
                reply("高等数学 90"),
            )
        )
        val tools = FakeTools { name, args -> "高等数学 90（$name $args）" }
        val messages = messages()
        val toolCalls = mutableListOf<String>()
        val toolResults = mutableListOf<Triple<String, Boolean, String?>>()

        val out = AgentRunner(tools, transport).run(
            messages, AgentConfig(),
            onToolCall = { toolCalls += it },
            onToolResult = { name, ok, msg -> toolResults += Triple(name, ok, msg) },
        )

        assertEquals("高等数学 90", out)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), messages.map { it.role() })
        val assistantCall = messages[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", assistantCall.text("id"))
        assertEquals("get_grades", assistantCall["function"]!!.jsonObject.text("name"))
        assertEquals("""{"term":"2025-2026-1"}""", assistantCall["function"]!!.jsonObject.text("arguments"))
        val toolMsg = messages[2].jsonObject
        assertEquals("call_1", toolMsg.text("tool_call_id"))
        assertEquals("""高等数学 90（get_grades {"term":"2025-2026-1"}）""", toolMsg.text("content"))
        assertEquals(listOf("get_grades"), toolCalls)
        assertEquals(listOf(Triple<String, Boolean, String?>("get_grades", true, null)), toolResults)
        // 第二轮照旧带工具（保险丝没烧）
        assertTrue(transport.bodies[1]["tools"] != null)
    }

    @Test
    fun 工具失败记成文本而不是中断() = runBlocking {
        val transport = FakeTransport(
            listOf(
                reply("", listOf(call("c1", "get_grades")), finish = "tool_calls"),
                reply("那我换个说法"),
            )
        )
        val messages = messages()
        var failure: String? = null
        AgentRunner(FakeTools { _, _ -> throw IllegalStateException("站点 502") }, transport).run(
            messages, AgentConfig(),
            onToolResult = { _, ok, msg -> if (!ok) failure = msg },
        )
        assertEquals("站点 502", failure)
        assertTrue(messages[2].jsonObject.text("content").startsWith("工具调用出错："))
    }

    @Test
    fun 超长工具结果按行截并写明省略了几行() = runBlocking {
        val long = (1..400).joinToString("\n") { "第 $it 行：" + "x".repeat(40) }
        val transport = FakeTransport(
            listOf(reply("", listOf(call("c1", "get_grades")), finish = "tool_calls"), reply("看完了"))
        )
        val messages = messages()
        AgentRunner(FakeTools { _, _ -> long }, transport).run(messages, AgentConfig())

        val content = messages[2].jsonObject.text("content")
        assertEquals(4000, AgentRunner.toolResultCap("get_grades"))
        assertTrue(content.length <= 4000, "截断后就该在预算内（实测 ${content.length} 字）")
        assertTrue(content.contains("已省略中间"))
        assertTrue(content.startsWith("第 1 行"))
        // 尾巴取的是最后几条完整的行 —— 末行就是第 400 行
        assertEquals("第 400 行：" + "x".repeat(40), content.substringAfterLast('\n'))
    }

    @Test
    fun 保险丝烧掉后不再下发工具且末条结果带上提示() = runBlocking {
        val rounds = List(AgentRunner.TOOL_CALL_FUSE + 1) { i ->
            if (i < AgentRunner.TOOL_CALL_FUSE) reply("", listOf(call("c$i", "get_grades")), finish = "tool_calls")
            else reply("算了，直接答")
        }
        val transport = FakeTransport(rounds)
        val messages = messages()
        var executions = 0

        val out = AgentRunner(FakeTools { _, _ -> executions++; "结果" }, transport).run(messages, AgentConfig())

        assertEquals("算了，直接答", out)
        assertEquals(AgentRunner.TOOL_CALL_FUSE, executions)
        assertEquals(AgentRunner.TOOL_CALL_FUSE + 1, transport.bodies.size)
        assertNull(transport.bodies[AgentRunner.TOOL_CALL_FUSE]["tools"], "保险丝烧了那一轮不许再给工具")
        assertTrue(transport.bodies[AgentRunner.TOOL_CALL_FUSE - 1]["tools"] != null)
        val toolMsgs = messages.filter { it.role() == "tool" }
        assertEquals(AgentRunner.TOOL_CALL_FUSE, toolMsgs.size)
        assertTrue(
            toolMsgs.last().jsonObject.text("content").endsWith("（工具次数已用尽）"),
            "熔断那一刻要告诉模型，否则它会接着调同一个工具"
        )
        assertFalse(toolMsgs.first().jsonObject.text("content").contains("已用尽"), "平时一个字都不提次数")
    }

    @Test
    fun 限流按站点而不是全局一刀切() = runBlocking {
        // 同一轮的两次调用：同一个站点要隔开，不同站点一秒都不等（连环问打的是不同系统）
        val sameSite = mutableListOf<Long>()
        val same = FakeTools(sites = mapOf("get_grades" to "grades")) { _, _ ->
            sameSite += System.currentTimeMillis(); "ok"
        }
        AgentRunner(same, FakeTransport(
            listOf(
                reply("", listOf(call("c1", "get_grades"), call("c2", "get_grades")), finish = "tool_calls"),
                reply("好"),
            )
        )).run(messages(), AgentConfig())
        assertEquals(2, sameSite.size)
        assertTrue(
            sameSite[1] - sameSite[0] >= 700,
            "同一站点两次调用之间要隔开（实测 ${sameSite[1] - sameSite[0]}ms）"
        )

        val mixed = mutableListOf<Long>()
        val twoSites = FakeTools(sites = mapOf("get_grades" to "grades", "get_schedule" to "schedule")) { _, _ ->
            mixed += System.currentTimeMillis(); "ok"
        }
        AgentRunner(twoSites, FakeTransport(
            listOf(
                reply("", listOf(call("c1", "get_grades"), call("c2", "get_schedule")), finish = "tool_calls"),
                reply("好"),
            )
        )).run(messages(), AgentConfig())
        assertEquals(2, mixed.size)
        assertTrue(mixed[1] - mixed[0] < 700, "不同站点之间不该等（实测 ${mixed[1] - mixed[0]}ms）")

        // 纯本地工具（rateLimitKeyOf = null）同样不等
        val local = mutableListOf<Long>()
        val localTools = FakeTools { _, _ -> local += System.currentTimeMillis(); "ok" }
        AgentRunner(localTools, FakeTransport(
            listOf(
                reply("", listOf(call("c1", "calculate"), call("c2", "calculate")), finish = "tool_calls"),
                reply("好"),
            )
        )).run(messages(), AgentConfig())
        assertTrue(local[1] - local[0] < 700)
    }

    @Test
    fun 会话失效直接上抛且不落进历史() = runBlocking {
        class FakeExpired : RuntimeException("登录态已失效"), SessionExpiredFailure

        val transport = FakeTransport(listOf(reply("", listOf(call("c1", "get_grades")), finish = "tool_calls")))
        val messages = messages()
        assertFailsWith<FakeExpired> {
            AgentRunner(FakeTools { _, _ -> throw FakeExpired() }, transport).run(messages, AgentConfig())
        }
        // 「有 tool_calls 却没 tool 回应」的残体会让后续请求永久报 must be followed by tool messages
        assertEquals(listOf("user"), messages.map { it.role() })
    }

    @Test
    fun 取消不是工具失败也原样上抛() = runBlocking {
        val transport = FakeTransport(listOf(reply("", listOf(call("c1", "get_grades")), finish = "tool_calls")))
        val messages = messages()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            AgentRunner(FakeTools { _, _ -> throw kotlinx.coroutines.CancellationException("用户点了停止") }, transport)
                .run(messages, AgentConfig())
        }
        assertEquals(listOf("user"), messages.map { it.role() })
    }

    @Test
    fun 思考开关与强度按原口径下发() = runBlocking {
        val off = FakeTransport(listOf(reply("好")))
        AgentRunner(FakeTools { _, _ -> "" }, off)
            .run(messages(), AgentConfig(thinkingEnabled = false, model = "deepseek-flash"))
        val body = off.bodies[0]
        assertEquals("disabled", body["thinking"]!!.jsonObject.text("type"))
        assertEquals("none", body.text("reasoning_effort"))
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content == "true")

        // 关思考时强度就是 none；开了思考又选了具体档位才下发强度
        val high = FakeTransport(listOf(reply("好")))
        AgentRunner(FakeTools { _, _ -> "" }, high)
            .run(messages(), AgentConfig(thinkingEnabled = true, reasoningEffort = AgentConfig.REASONING_HIGH))
        assertEquals("enabled", high.bodies[0]["thinking"]!!.jsonObject.text("type"))
        assertEquals("high", high.bodies[0].text("reasoning_effort"))

        // 自动档：不发强度（服务端默认 high），thinking 照发
        val auto = FakeTransport(listOf(reply("好")))
        AgentRunner(FakeTools { _, _ -> "" }, auto).run(messages(), AgentConfig())
        assertNull(auto.bodies[0]["reasoning_effort"])
        assertEquals("enabled", auto.bodies[0]["thinking"]!!.jsonObject.text("type"))

        // 自定义端点：这两个字段都是 DeepSeek 专有的，一个字都不发
        val custom = FakeTransport(listOf(reply("好")))
        AgentRunner(FakeTools { _, _ -> "" }, custom).run(
            messages(),
            AgentConfig(provider = AgentConfig.PROVIDER_CUSTOM, baseUrl = "https://example.invalid/v1", apiKey = "sk-fake-runner-003"),
        )
        assertNull(custom.bodies[0]["thinking"])
        assertNull(custom.bodies[0]["stream_options"])
        assertNull(custom.bodies[0]["reasoning_effort"])
    }

    @Test
    fun 只有openai官方端点才剥掉历史里的思维链() = runBlocking {
        val history = {
            messages(buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("reasoning_content", "想过了")
            })
        }

        val deepseek = FakeTransport(listOf(reply("好")))
        AgentRunner(FakeTools { _, _ -> "" }, deepseek).run(history(), AgentConfig())
        assertTrue(
            deepseek.bodies[0]["messages"].toString().contains("reasoning_content"),
            "DeepSeek 系带 tools 时要求历史思维链完整回传，删了会 400"
        )

        val local = history()
        val openai = FakeTransport(listOf(reply("好")))
        AgentRunner(FakeTools { _, _ -> "" }, openai)
            .run(local, AgentConfig(provider = AgentConfig.PROVIDER_OPENAI, apiKey = "sk-fake-runner-004"))
        assertFalse(openai.bodies[0]["messages"].toString().contains("reasoning_content"))
        // 只改发出去的那一份，本地历史一个字不动
        assertEquals("想过了", local[1].jsonObject.text("reasoning_content"))
    }

    @Test
    fun 空正文的几种结束原因各给一句话() = runBlocking {
        suspend fun runWith(finish: String, content: String = "") =
            AgentRunner(FakeTools { _, _ -> "" }, FakeTransport(listOf(reply(content, finish = finish))))
                .run(messages(), AgentConfig())

        assertTrue(runWith("length").startsWith("回复被截断"))
        assertEquals("生成被服务端中断，请重试。", runWith("aborted"))
        assertEquals("回复被内容过滤拦截。", runWith("content_filter"))
        assertEquals("模型推理资源暂时不足，请稍后重试。", runWith("insufficient_system_resource"))

        // 有正文就不是错误：正文照回，只加一句「可能不完整」
        val partial = runWith("aborted", "半句话")
        assertTrue(partial.startsWith("半句话"))
        assertTrue(partial.endsWith("（生成被服务端中断，回答可能不完整）"))
    }

    @Test
    fun 有正文却被长度截断时续写一轮() = runBlocking {
        val transport = FakeTransport(listOf(reply("前半句", finish = "length"), reply("后半句")))
        val messages = messages()
        val out = AgentRunner(FakeTools { _, _ -> "" }, transport).run(messages, AgentConfig())

        assertEquals("前半句后半句", out)
        assertEquals(listOf("user", "assistant", "user", "assistant"), messages.map { it.role() })
        assertTrue(messages[2].jsonObject.text("content").contains("长度被截断"))
        // 半截话先入历史（否则续写那一轮会把前半句丢掉），续写的提问跟在它后面
        assertTrue(messages[1].jsonObject.text("content").contains("前半句"))
        assertTrue(messages[2].jsonObject.text("content").contains("接着写"))
    }
}
