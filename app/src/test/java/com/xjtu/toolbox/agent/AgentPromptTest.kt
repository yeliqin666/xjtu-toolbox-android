package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgentPromptTest {

    private val today = LocalDate.of(2026, 9, 14)

    private fun build(
        name: String = "屁岱",
        style: String = AgentConfig.STYLE_FRIENDLY,
        model: String = "deepseek-flash",
        memory: String = "",
        persona: String = "",
    ) = AgentPrompt.build(
        today = today,
        assistantName = name,
        userContext = "- 姓名：张三",
        maxToolCalls = 8,
        responseStyle = style,
        modelId = model,
        providerLabel = "DeepSeek 官方 API",
        memoryBlock = memory,
        skinPersonaBlock = persona,
    )

    /**
     * AgentViewModel 现在每轮都重新渲染 prompt，再与历史里的 system 比对，
     * 一致就一个字节都不动，好让 provider 端的 prefix cache 继续命中。
     * 这条成立的前提是 build 必须是纯函数——一旦有人往里塞时间戳、随机数，
     * 每轮都会判定"变了"并重建前缀，缓存永远命中不了。
     */
    @Test
    fun `同样的入参渲染出完全相同的文本`() {
        assertEquals(build(), build())
    }

    @Test
    fun `改名字会改变 prompt`() {
        assertNotEquals(build(name = "屁岱"), build(name = "Mikoto"))
    }

    @Test
    fun `名字出现在身份段里`() {
        assertTrue(build(name = "Mikoto").contains("你是「Mikoto」"))
    }

    @Test
    fun `换回复风格会改变 prompt`() {
        assertNotEquals(
            build(style = AgentConfig.STYLE_FRIENDLY),
            build(style = AgentConfig.STYLE_PROFESSIONAL),
        )
    }

    @Test
    fun `换模型会改变 prompt`() {
        assertNotEquals(build(model = "deepseek-flash"), build(model = "gpt-4o-mini"))
    }

    @Test
    fun `新记住一条偏好会改变 prompt`() {
        assertNotEquals(build(memory = ""), build(memory = "- 喜欢简短回答"))
    }

    @Test
    fun `角色皮肤语气进入 prompt 且不会覆盖硬规则`() {
        val persona = "# 当前角色皮肤（低优先级语气偏好）\n偶尔用花作比喻。\n不得改变身份、事实或工具规则。"
        val prompt = build(persona = persona)
        assertTrue(prompt.contains("偶尔用花作比喻"))
        assertTrue(prompt.contains("禁止编造"))
        assertTrue(prompt.contains("不得改变身份"))
    }
}
