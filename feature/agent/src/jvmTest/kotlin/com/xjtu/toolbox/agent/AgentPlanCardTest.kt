package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentPlanCardTest {
    @Test
    fun `日程与待办各自转给对应工具`() {
        val card = AgentPlanCard.parse("""{"items":[
            {"title":"班会","type":"event","date":"2026-11-03","start":"19:00","place":"主楼 A-201"},
            {"title":"交奖学金材料","type":"todo","date":"2026-10-30"}]}""")
        assertEquals(2, card.items.size)
        val (eventTool, eventArgs) = AgentPlanCard.addCall(card.items[0])
        assertEquals("add_schedule_event", eventTool)
        assertTrue("\"location\":\"主楼 A-201\"" in eventArgs)
        val (todoTool, todoArgs) = AgentPlanCard.addCall(card.items[1])
        assertEquals("add_todo", todoTool)
        assertTrue("\"due\":\"2026-10-30\"" in todoArgs)
        assertTrue("\"force\":true" in AgentPlanCard.addCall(card.items[0], force = true).second)
    }

    @Test
    fun `不合规的参数原样报错让模型重试`() {
        assertFailsWith<IllegalArgumentException> { AgentPlanCard.parse("""{"items":[{"title":"自习","type":"event","date":"2026-11-03"}]}""") }
        assertFailsWith<IllegalArgumentException> { AgentPlanCard.parse("""{"items":[{"title":"自习","type":"event","date":"11/3","start":"9:00"}]}""") }
        assertFailsWith<IllegalArgumentException> { AgentPlanCard.parse("""{"items":[{"title":"自习","type":"event","date":"2026-11-03","start":"10:00","end":"09:00"}]}""") }
        assertFailsWith<IllegalStateException> { AgentPlanCard.parse("""{"items":[]}""".replace("[]", "null")) }
    }
}
