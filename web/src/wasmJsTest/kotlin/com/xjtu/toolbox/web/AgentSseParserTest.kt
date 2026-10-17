package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `AgentSseParser` 的逐条用例（在真浏览器里跑）。 */
class AgentSseParserTest {

    private fun frames(chunks: List<String>): List<AgentFrame> {
        val parser = AgentSseParser()
        return chunks.flatMap { parser.push(it) }
    }

    private fun frames(chunk: String): List<AgentFrame> = frames(listOf(chunk))

    @Test
    fun `一条完整帧就是 event 加一行 data 加一个空行`() {
        val out = frames("event: delta\ndata: {\"text\":\"你好\"}\n\n")
        assertEquals(1, out.size)
        assertEquals(AgentFrame.Delta("你好"), out.single())
    }

    @Test
    fun `半帧留在缓冲里：下一块补上才吐出来`() {
        val parser = AgentSseParser()
        // 服务端一次 flush 出去的字节可能被浏览器切成任意两半（fetch 的 reader 就是这么给的）
        assertEquals(emptyList(), parser.push("event: delta\ndata: {\"text\":\"你"))
        assertEquals(emptyList(), parser.push("好\"}"))
        assertEquals(listOf(AgentFrame.Delta("你好")), parser.push("\n\n"))
    }

    @Test
    fun `一个字节块里黏着三帧：逐帧吐出来`() {
        val out = frames(
            "event: reasoning\ndata: {\"text\":\"想\"}\n\n" +
                "event: delta\ndata: {\"text\":\"正文\"}\n\n" +
                "event: usage\ndata: {\"totalTokens\":12345}\n\n",
        )
        assertEquals(
            listOf(AgentFrame.Reasoning("想"), AgentFrame.Delta("正文"), AgentFrame.Usage(12345L)),
            out,
        )
    }

    @Test
    fun `保活注释帧只给一个 Ping（它是 SSE 规范的注释行，不是内容）`() {
        assertEquals(listOf(AgentFrame.Ping), frames(": ping\n\n"))
    }

    @Test
    fun `注释与数据在同一个块里时：数据说话，Ping 不额外产生`() {
        val out = frames(": ping\nevent: delta\ndata: {\"text\":\"a\"}\n\n: ping\n\n")
        assertEquals(listOf(AgentFrame.Delta("a"), AgentFrame.Ping), out)
    }

    @Test
    fun `CRLF 与单独 CR 都当行分隔（反代/中间层可能改写）`() {
        assertEquals(listOf(AgentFrame.Delta("a")), frames("event: delta\r\ndata: {\"text\":\"a\"}\r\n\r\n"))
        assertEquals(listOf(AgentFrame.Delta("b")), frames("event: delta\rdata: {\"text\":\"b\"}\r\r"))
    }

    @Test
    fun `data 跨多行时按换行拼起来（规范允许；服务端今天只写一行）`() {
        assertEquals(listOf(AgentFrame.Delta("ab")), frames("event: delta\ndata: {\"text\":\ndata: \"ab\"}\n\n"))
    }

    @Test
    fun `认不出的字段与空块一律忽略`() {
        assertEquals(emptyList(), frames("\n\n"))
        assertEquals(emptyList(), frames("id: 7\nretry: 3000\n\n"))
        // 认得的事件名 + 认不出的字段：字段被忽略，帧照解
        assertEquals(listOf(AgentFrame.Delta("x")), frames("event: delta\nid: 7\ndata: {\"text\":\"x\"}\n\n"))
    }

    @Test
    fun `认不出来的事件名与载荷不是 JSON 都不猜：如实记成 Unknown`() {
        assertEquals(listOf(AgentFrame.Unknown("whatever", "{\"a\":1}")), frames("event: whatever\ndata: {\"a\":1}\n\n"))
        assertEquals(listOf(AgentFrame.Unknown("delta", "{oops")), frames("event: delta\ndata: {oops\n\n"))
        // 没有 `event:` 行（SSE 规范里默认事件名是 message）：也不当它是 delta
        assertEquals(listOf(AgentFrame.Unknown(null, "{\"text\":\"a\"}")), frames("data: {\"text\":\"a\"}\n\n"))
    }

    @Test
    fun `十一类帧的形状一次钉全（与 :server 的 agentSseFrame 对着写）`() {
        val out = frames(
            "event: delta\ndata: {\"text\":\"正文\"}\n\n" +
                "event: reasoning\ndata: {\"text\":\"想\"}\n\n" +
                "event: tool_call\ndata: {\"name\":\"get_schedule\"}\n\n" +
                "event: tool_result\ndata: {\"name\":\"get_schedule\",\"success\":true,\"error\":null}\n\n" +
                "event: tool_result\ndata: {\"name\":\"find_faculty\",\"success\":false,\"error\":\"站点会话不在\"}\n\n" +
                "event: usage\ndata: {\"totalTokens\":800001}\n\n" +
                "event: mfa\ndata: {\"pending\":true,\"siteName\":\"教务系统\"}\n\n" +
                "event: mfa\ndata: {\"pending\":false,\"siteName\":null}\n\n" +
                "event: widget\ndata: {\"widgets\":[{\"type\":\"schedule\",\"json\":\"{}\"}]}\n\n" +
                "event: nav\ndata: {\"routes\":[{\"label\":\"查看课表\",\"route\":\"schedule\"}]}\n\n" +
                "event: done\ndata: {\"content\":\"答完\",\"sessionId\":\"s1\",\"title\":\"标题\",\"contextExhausted\":false,\"totalTokens\":900}\n\n" +
                // `done` 没带 totalTokens（`explicitNulls = false` ⇒ 那一格不存在）：读成 null，不是 0
                "event: done\ndata: {\"content\":\"\",\"sessionId\":\"s1\",\"title\":\"\",\"contextExhausted\":true}\n\n" +
                "event: error\ndata: {\"kind\":\"llm\",\"message\":\"服务商 500\"}\n\n" +
                ": ping\n\n",
        )
        assertEquals(
            listOf(
                AgentFrame.Delta("正文"),
                AgentFrame.Reasoning("想"),
                AgentFrame.ToolCall("get_schedule"),
                AgentFrame.ToolResult("get_schedule", true, null),
                AgentFrame.ToolResult("find_faculty", false, "站点会话不在"),
                AgentFrame.Usage(800_001L),
                AgentFrame.Mfa(true, "教务系统"),
                AgentFrame.Mfa(false, null),
                AgentFrame.Widgets(listOf(com.xjtu.toolbox.agent.StoredWidget("schedule", "{}"))),
                AgentFrame.Nav(listOf("查看课表" to "schedule")),
                AgentFrame.Done("答完", "s1", "标题", false, 900L),
                AgentFrame.Done("", "s1", "", true, null),
                AgentFrame.Error("llm", "服务商 500"),
                AgentFrame.Ping,
            ),
            out,
        )
    }

    @Test
    fun `半帧不会被当成帧：不完整的 JSON 一直留在缓冲里`() {
        val parser = AgentSseParser()
        assertTrue(parser.push("event: delta\ndata: {\"text\":\"没完").isEmpty())
        assertTrue(parser.push("\"}").isEmpty())
        assertEquals(listOf(AgentFrame.Delta("没完")), parser.push("\n\n"))
        // 吐完之后缓冲是空的：同样的两块再来一遍还是同一帧（不残留、不重复）
        assertTrue(parser.push("event: delta\ndata: {\"text\":\"没完").isEmpty())
        assertEquals(listOf(AgentFrame.Delta("没完")), parser.push("\"}\n\n"))
    }
}
