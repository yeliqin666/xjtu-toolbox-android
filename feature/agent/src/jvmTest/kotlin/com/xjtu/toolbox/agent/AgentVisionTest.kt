package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 按模型 ID 判断认不认图（[AgentVision.supportsVision]）。 */
class AgentVisionTest {

    private fun openAi(model: String = "") = AgentConfig(provider = AgentConfig.PROVIDER_OPENAI, model = model)

    @Test
    fun `OpenAI default model accepts images`() {
        // 默认模型认图，相册按钮才会出现；以前只认到 gpt-5，换成 GPT-6 一代后按钮会消失
        assertTrue(AgentVision.supportsVision(openAi()))
    }

    @Test
    fun `OpenAI current mainline models accept images`() {
        listOf("gpt-4o", "gpt-4.1-mini", "gpt-5-mini", "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra", "gpt-6-luna", "o3", "o4-mini")
            .forEach { assertTrue(AgentVision.supportsVision(openAi(it)), it) }
    }

    @Test
    fun `OpenAI audio and realtime models do not`() {
        listOf("gpt-4o-audio-preview", "gpt-realtime-2.1", "gpt-3.5-turbo")
            .forEach { assertFalse(AgentVision.supportsVision(openAi(it)), it) }
    }

    @Test
    fun `DeepSeek default model accepts images`() {
        assertTrue(AgentVision.supportsVision(AgentConfig()))
        assertFalse(AgentVision.supportsVision(AgentConfig(model = "deepseek-v4-pro")))
    }
}