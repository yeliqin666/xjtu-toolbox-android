package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRowsTest {
    private val user = ChatMessage("user", "查询", timestamp = 1)
    private val thought = ChatMessage("assistant", "", reasoningContent = "先查询", timestamp = 2)
    private val tool = ChatMessage("tool_event", "已查询", timestamp = 3)

    private fun assistants(messages: List<ChatMessage>, loading: Boolean = true) =
        groupAgentRows(messages, showReasoning = true, loading).filterIsInstance<AgentRow.Assistant>()

    @Test
    fun waitingReasoningAndToolContinuationKeepOneStableRow() {
        val waiting = assistants(listOf(user)).single()
        val before = assistants(listOf(user, thought, tool, ChatMessage("assistant", "", timestamp = 4))).single()
        val after = assistants(listOf(user, thought, tool, ChatMessage("assistant", "", reasoningContent = "继续分析", timestamp = 4))).single()
        assertEquals(waiting.key, before.key)
        assertEquals(before.key, after.key)
        assertEquals("先查询\n\n继续分析", after.msg.reasoningContent)
        assertEquals(listOf(tool), after.tools)
    }

    @Test
    fun proseSeparatesLaterReasoningAndKeepsToolsInTheirOwnSegment() {
        val answer = ChatMessage("assistant", "第一段回答", timestamp = 4)
        val nextTool = ChatMessage("tool_event", "补充查询", timestamp = 5)
        val pending = assistants(listOf(user, thought, tool, answer, nextTool))
        val continued = assistants(listOf(user, thought, tool, answer, nextTool,
            ChatMessage("assistant", "", reasoningContent = "补充分析", timestamp = 6)))
        assertEquals(2, continued.size)
        assertEquals(pending.map { it.key }, continued.map { it.key })
        assertEquals("第一段回答", continued[0].msg.content)
        assertEquals("先查询", continued[0].msg.reasoningContent)
        assertEquals(listOf(tool), continued[0].tools)
        assertEquals("补充分析", continued[1].msg.reasoningContent)
        assertEquals(listOf(nextTool), continued[1].tools)
    }
}
