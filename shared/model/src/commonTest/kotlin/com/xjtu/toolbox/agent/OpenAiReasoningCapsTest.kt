package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OpenAI 系模型的最低推理档位表（[OpenAiReasoningCaps]）。 */
class OpenAiReasoningCapsTest {

    private fun floor(model: String) = OpenAiReasoningCaps.floorOf(model)

    @Test
    fun gpt62Sol与Astra不认none_最低是low() {
        assertEquals("low", floor("gpt-6.2-sol")?.wire)
        assertEquals("low", floor("gpt-6.2-astra")?.wire)
        assertEquals("low", floor("gpt-6.2-sol-2026-09-30")?.wire, "日期快照同档")
        assertEquals("low", floor("openai/gpt-6.2-sol")?.wire, "中转的 openai/ 前缀要去掉")
        assertEquals("low", floor("GPT-6.2-Sol")?.wire)
        assertEquals("low", floor("gpt-6.1-sol")?.wire)
        assertEquals("low", floor("gpt-6-astra")?.wire)
    }

    @Test
    fun luna与terra和gpt5点x认none() {
        listOf("gpt-6-luna", "gpt-6.1-terra", "gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-5.5")
            .forEach { assertEquals("none", floor(it)?.wire, it) }
    }

    @Test
    fun gpt5初代最低是minimal() {
        listOf("gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5-mini-2025-08-07")
            .forEach { assertEquals("minimal", floor(it)?.wire, it) }
    }

    @Test
    fun o系列最低low_非推理模型不发() {
        listOf("o3", "o4-mini", "o1").forEach { assertEquals("low", floor(it)?.wire, it) }
        listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini").forEach {
            assertEquals(OpenAiReasoningCaps.Floor.NOT_REASONING, floor(it), it)
            assertNull(floor(it)!!.wire)
        }
    }

    @Test
    fun 未知型号返回null_交给降级重试() {
        listOf("deepseek-flash", "qwen3-max", "gpt-6.3-nova", "gpt-5.1-codex", "o1-mini", "my-model")
            .forEach { assertNull(floor(it), it) }
    }

    @Test
    fun DeepSeek服务商不查表() {
        assertNull(OpenAiReasoningCaps.floorOf(AgentConfig(model = "gpt-6.2-sol")))
        assertEquals(
            OpenAiReasoningCaps.Floor.LOW,
            OpenAiReasoningCaps.floorOf(AgentConfig(provider = AgentConfig.PROVIDER_OPENAI)),
            "OpenAI 默认模型 gpt-6.2-sol",
        )
    }
}