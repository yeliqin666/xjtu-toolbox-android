package com.xjtu.toolbox.web

import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.StoredWidget
import com.xjtu.toolbox.util.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/** 「SSE 帧 → 屏状态」的逐条用例。 */
class WebAgentSessionTest {

    private fun json(text: String): JsonObject = AppJson.parseToJsonElement(text) as JsonObject

    private fun frames(vararg frames: AgentFrame): AgentStreamState {
        val state = AgentStreamState()
        state.beginTurn("帮我看下课表")
        frames.forEach { state.apply(it) }
        return state
    }

    @Test
    fun `一轮正常结束：正文按 50ms 合流落气泡、工具行走 core 那份文案、done 收尾带 nav 与卡片`() {
        val state = frames(
            AgentFrame.Delta("你"),
            AgentFrame.Delta("好"),
        )
        // 还没到合流节拍（50ms）之前：分片**不进**气泡 —— 这正是防 O(n²) 的那一步
        assertEquals("", state.messages.last().content)

        state.flush()
        assertEquals("你好", state.messages.last().content)

        state.apply(AgentFrame.ToolCall("get_schedule"))
        state.apply(AgentFrame.ToolResult("get_schedule", success = true, error = null))
        // 工具行文案与 Android 同一份表（`agentToolLabel` / `agentCompleteToolLabel`）
        assertEquals("已查询课表", state.messages.last().content)
        assertFalse(state.messages.last().isToolCall)
        assertNull(state.messages.last().toolError)

        // 工具之后的正文要**新开**一个气泡（照 AgentViewModel 的 onToolCall 换气泡那一段）
        state.apply(AgentFrame.Delta("明天两节课"))
        state.flush()
        assertEquals(4, state.messages.size)
        assertEquals("明天两节课", state.messages.last().content)

        state.apply(AgentFrame.Widgets(listOf(StoredWidget("ScheduleWidget", "{}"))))
        state.apply(AgentFrame.Nav(listOf("查看课表" to "schedule")))
        state.apply(AgentFrame.Done("明天两节课", "s1", "课表", false, 900L))
        state.finishTurn()
        val last = state.messages.last()
        assertEquals(listOf("查看课表" to "schedule"), last.navSuggestions)
        assertEquals(1, last.widgets.size)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `工具失败：气泡保留执行中文案、标红的原因落在 toolError 上`() {
        val state = frames(AgentFrame.ToolCall("find_faculty"))
        assertEquals("查询教师…", state.messages.last().content)
        assertTrue(state.messages.last().isToolCall)

        state.apply(AgentFrame.ToolResult("find_faculty", success = false, error = "站点会话不在"))
        assertFalse(state.messages.last().isToolCall)
        assertEquals("查询教师…", state.messages.last().content)
        assertEquals("站点会话不在", state.messages.last().toolError)
    }

    @Test
    fun `思维链走同一个气泡的 reasoningContent（受屏的 showReasoning 控制）`() {
        val state = frames(AgentFrame.Reasoning("让我想"), AgentFrame.Delta("答案"))
        state.flush()
        assertEquals("让我想", state.messages.last().reasoningContent)
        assertEquals("答案", state.messages.last().content)
    }

    @Test
    fun `上下文耗尽只在「未耗尽 → 耗尽」那一次弹窗（serve 判、这一端只管弹一次）`() {
        val state = frames(AgentFrame.Usage(799_999L))
        assertFalse(state.contextExhausted)
        assertFalse(state.consumeContextExhaustedTrigger())

        state.apply(AgentFrame.Usage(800_001L))
        assertTrue(state.contextExhausted)
        assertTrue(state.consumeContextExhaustedTrigger())
        // 取走即清零：屏弹的是「刚刚那一次」，不是「真则一直弹」
        assertFalse(state.consumeContextExhaustedTrigger())
        // 再来一条同样的 usage：已经在耗尽态 ⇒ 不再触发
        state.apply(AgentFrame.Usage(900_000L))
        assertFalse(state.contextExhaustedJustTriggered)
    }

    @Test
    fun `error 两种 kind 的文案与 AgentViewModel 的两个 catch 分支逐字同源`() {
        val expired = frames(AgentFrame.Error("session_expired", "登录已失效，请返回并重新进入对应功能页面完成认证后再试。"))
        assertEquals("登录已失效，请返回并重新进入对应功能页面完成认证后再试。", expired.messages.last().content)
        // 登录失效那一档**不**落 errorMessage（与 ViewModel 同一条：那一句已经在气泡里了）
        assertNull(expired.errorMessage)
        assertFalse(expired.isLoading)

        val llm = frames(AgentFrame.Error("llm", "服务商 500"))
        assertEquals("出错了：服务商 500", llm.messages.last().content)
        assertEquals("服务商 500", llm.errorMessage)
    }

    @Test
    fun `被停止 / 客户端断开：工具行标「已中断」、空正文写「（已停止）」、不说它出错`() {
        val state = frames(AgentFrame.ToolCall("get_schedule"))
        state.stopped()
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("已中断", state.messages.last().toolError)
        // 工具行之后**没有**再补一条「（已停止）」气泡：这一轮一个字都没生成（与 ViewModel 的取消路径同形）
        assertEquals(2, state.messages.size)
    }

    @Test
    fun `被停止时已经流出来的正文保留，空的才写「（已停止）」`() {
        val state = frames(AgentFrame.Delta("答了一半"))
        state.flush()
        state.stopped()
        assertEquals("答了一半", state.messages.last().content)

        val empty = AgentStreamState()
        empty.beginTurn("问")
        empty.apply(AgentFrame.Delta(""))
        empty.stopped()
        assertEquals("（已停止）", empty.messages.last().content)
    }

    @Test
    fun `MFA 那条气泡：只有站名（红线：不写手机号），结束时收尾；Ping 与认不出的帧一个字都不写`() {
        val state = frames(AgentFrame.Mfa(true, "教务系统"))
        assertEquals("需要登录验证：等待「教务系统」短信验证码…", state.messages.last().content)
        assertTrue(state.messages.last().isToolCall)

        val sizeBefore = state.messages.size
        state.apply(AgentFrame.Ping)
        state.apply(AgentFrame.Unknown("future_event", "{\"x\":1}"))
        assertEquals(sizeBefore, state.messages.size)

        state.apply(AgentFrame.Mfa(false, null))
        assertFalse(state.messages.last().isToolCall)
    }

    @Test
    fun `切到一条会话：落盘消息映射回气泡（nav / 卡片 / 思考 / 工具失败），耗尽态接上但**不**弹窗`() {
        val state = AgentStreamState()
        state.load(
            json(
                """
                {"messages":[
                  {"role":"user","content":"帮我看下课表","timestamp":1700000000000},
                  {"role":"tool_event","content":"已查询课表","timestamp":1700000000001,"toolError":null},
                  {"role":"assistant","content":"明天两节课","reasoningContent":"想一下",
                   "nav":[["查看课表","schedule"]],"widgets":[{"type":"ScheduleWidget","json":"{}"}],
                   "timestamp":1700000000002},
                  {"role":"tool_event","content":"查询教师…","timestamp":1700000000003,"toolError":"站点会话不在"}
                ],
                "lastTotalTokens":900,"contextExhausted":true}
                """.trimIndent(),
            ),
        )
        assertEquals(4, state.messages.size)
        assertEquals(1700000000000L, state.messages[0].timestamp)
        assertEquals("明天两节课", state.messages[2].content)
        assertEquals("想一下", state.messages[2].reasoningContent)
        assertEquals(listOf("查看课表" to "schedule"), state.messages[2].navSuggestions)
        assertEquals(1, state.messages[2].widgets.size)
        // 落盘里的工具事件都是**收尾过**的：不画转圈
        assertFalse(state.messages[1].isToolCall)
        assertEquals("站点会话不在", state.messages[3].toolError)
        // 耗尽态接上，但「刚刚变真」那一次的一次性标记必须是 false（不然进来就弹窗）
        assertTrue(state.contextExhausted)
        assertFalse(state.consumeContextExhaustedTrigger())
        assertFalse(state.isLoading)
    }

    @Test
    fun `这条会话不在了（conversation 为 null）：屏清空、不抛`() {
        val state = AgentStreamState()
        state.beginTurn("问")
        state.load(null)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertFalse(state.contextExhausted)
    }

    @Test
    fun `传输层失败的两句话形：服务端的 409 信封照读它的 message；断线报原因；abort 不说它出错`() {
        // 开门阶段的失败是**信封**（契约 §5.11）：读服务端写好的中文短句
        assertEquals(
            "上一轮还在生成，先点停止或等它结束",
            agentStreamFailureMessage(
                """{"kind":"http","status":409,"body":"{\"code\":409,\"message\":\"上一轮还在生成，先点停止或等它结束\"}"}""",
            ),
        )
        // 信封都读不出来：报 HTTP 码
        assertEquals("HTTP 502", agentStreamFailureMessage("""{"kind":"http","status":502,"body":"<html>"}"""))
        // 网络断：把原因原样带出来
        assertEquals("Failed to fetch", agentStreamFailureMessage("""{"kind":"network","message":"Failed to fetch"}"""))
        // 我方主动取消（切会话 / 新建 / 点停止）：**不是错误** ⇒ null，调用方走「（已停止）」
        assertNull(agentStreamFailureMessage("""{"kind":"abort","message":"The user aborted a request."}"""))
        assertTrue(agentStreamFailureMessage("boom").orEmpty().startsWith("读取回复失败："))
    }

    @Test
    fun `GET 配置那一格：apiKey 一个字都不读（红线），keyConfigured 才是「配好了没」`() {
        val view = configViewOf(
            json(
                """
                {"provider":"deepseek","model":"deepseek-flash","effectiveModel":"deepseek-flash",
                 "baseUrl":"","assistantName":"屁岱","searchEngine":"auto","thinkingEnabled":true,
                 "reasoningEffort":"minimal","showReasoning":true,"keyConfigured":true,
                 "apiKey":"sk-fake-agent-key-should-never-be-read",
                 "toolAllowlist":["calculate","get_schedule"],"honored":[],"canEditKey":true}
                """.trimIndent(),
            ),
        )
        assertTrue(view.config.keyConfigured)
        assertTrue(view.config.isConfigured)
        assertEquals("", view.config.apiKey)
        // `minimal` 是 DeepSeek 的别名：归一到本地那一档（与 `:core` 的 `normalizeReasoningEffort` 同源）
        assertEquals(AgentConfig.REASONING_LOW, view.config.reasoningEffort)
        assertEquals(listOf("calculate", "get_schedule"), view.toolAllowlist)
        assertTrue(view.canEditKey)
        assertFalse(AgentConfig().isConfigured)
    }

    @Test
    fun `第二轮不许收尾上一轮的工具行：beginTurn 会清掉上一轮的残留下标`() {
        // 上一轮断在工具执行中（真实的收尾是 stopped()，但断线时也可能什么都没收到）
        val state = frames(AgentFrame.ToolCall("get_schedule"))
        val staleIdx = state.messages.lastIndex
        val staleSize = state.messages.size

        state.beginTurn("再问一句")
        state.apply(AgentFrame.ToolResult("get_schedule", success = true, error = null))

        // 这一轮的结果只该写这一轮的气泡：上一轮那条仍然停在「还在跑」的形状上
        assertEquals(staleSize + 1, state.messages.size)
        assertTrue(state.messages[staleIdx].isToolCall)
        assertNull(state.messages[staleIdx].toolError)
    }
}
