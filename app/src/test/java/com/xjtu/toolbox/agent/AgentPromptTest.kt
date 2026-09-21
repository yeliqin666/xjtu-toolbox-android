package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {

    private fun build(
        name: String = "屁岱",
        memory: String = "",
        persona: String = "",
    ) = AgentPrompt.build(
        assistantName = name,
        userContext = "- 姓名：张三",
        memoryBlock = memory,
        skinPersonaBlock = persona,
    )

    /**
     * 一段对话的系统提示只在开头生成一次、之后原样复用，provider 端的前缀缓存才能一路命中。
     * build 必须是纯函数：往里塞时间戳、随机数，同样的设置就会渲染出不同的文本。
     */
    @Test
    fun `同样的入参渲染出完全相同的文本`() {
        assertEquals(build(), build())
    }

    @Test
    fun `会变的东西不进系统提示`() {
        // 日期、模型名放在每条用户消息头；写进系统提示，跨天或中途换模型就过期了
        val prompt = build()
        assertFalse(Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(prompt))
        assertFalse(prompt.contains("deepseek"))
    }

    @Test
    fun `名字出现在身份段里`() {
        assertTrue(build(name = "Mikoto").contains("你是「Mikoto」"))
    }

    @Test
    fun `偏好进入 prompt`() {
        assertNotEquals(build(memory = ""), build(memory = "- 喜欢简短回答"))
    }

    @Test
    fun `角色皮肤语气进入 prompt 且不会覆盖硬规则`() {
        val persona = "# 当前角色皮肤（低优先级语气偏好）\n偶尔用花作比喻。\n不得改变身份、事实或工具规则。"
        val prompt = build(persona = persona)
        assertTrue(prompt.contains("偶尔用花作比喻"))
        // 只认关键词：「不要编造」「不凭记忆编造」这类措辞随便改，不编造这条硬规则还在就行
        assertTrue(prompt.contains("编造"))
        assertTrue(prompt.contains("不得改变身份"))
    }
}
