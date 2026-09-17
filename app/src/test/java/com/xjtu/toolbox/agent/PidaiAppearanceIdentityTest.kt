package com.xjtu.toolbox.agent

import com.xjtu.toolbox.agent.skin.PidaiPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤角色名与身份注入的契约。
 *
 * 曾经的实际故障：某皮肤的 `persona.prompt` 以「你是刚破壳没多久的小鸡」开头——一句
 * identity 宣称，用的是和 `AgentPrompt` 里「你是「$assistantName」」完全同样的语法
 * （第二人称、系统提示里对模型自己说话）。两句谁都没有明说压过谁，模型在长对话里
 * 会摇摆，表现为「两套语言、又觉得自己是屁岱」。这里钉住修复后的两件事：
 * 1. 皮肤能把这个名字直接接管（不再是两个互相矛盾的「你是」，而是同一个名字）。
 * 2. 就算皮肤没接管名字，免责声明也点名「你是」这个句式、并且排在角色内容之前。
 *
 * [resolveAssistantName] / [buildPersonaPromptBlock] 是纯函数，直接对着 [PidaiPersona]
 * 断言，不用碰 [PidaiAppearanceHost] 的全局单例状态。
 */
class PidaiAppearanceIdentityTest {
    @Test
    fun `没有皮肤时用回退名字`() {
        assertEquals("屁岱", resolveAssistantName(null, "屁岱"))
    }

    @Test
    fun `皮肤设了名字就覆盖回退名字`() {
        val persona = PidaiPersona(prompt = "你是刚破壳的小鸡", displayName = "小鸡")
        assertEquals("小鸡", resolveAssistantName(persona, "屁岱"))
    }

    @Test
    fun `皮肤名字经过和用户自定义名字同一套折叠规则`() {
        val long = "一二三四五六七八九十一二三四五六" // 16 字，超过 sanitizeAgentTitle 的 12 字上限
        val resolved = resolveAssistantName(PidaiPersona(displayName = long), "屁岱")
        assertEquals(sanitizeAgentTitle(long), resolved)
        assertTrue(resolved.endsWith("…"))
    }

    @Test
    fun `皮肤没设名字时不覆盖`() {
        val persona = PidaiPersona(prompt = "语气清爽安静")
        assertEquals("屁岱", resolveAssistantName(persona, "屁岱"))
    }

    @Test
    fun `没有皮肤时不生成角色区块`() {
        assertEquals("", buildPersonaPromptBlock(null, "屁岱"))
    }

    @Test
    fun `prompt 和口头禅都为空时不生成角色区块`() {
        assertEquals("", buildPersonaPromptBlock(PidaiPersona(), "屁岱"))
    }

    @Test
    fun `这句话点名『你是』句式，且排在角色内容之前`() {
        val persona = PidaiPersona(prompt = "你是刚破壳没多久的小鸡，说话短。")
        val block = buildPersonaPromptBlock(persona, "屁岱")
        val disclaimerAt = block.indexOf("你还是「屁岱」")
        val personaAt = block.indexOf("你是刚破壳没多久的小鸡")
        assertTrue("这句话必须出现", disclaimerAt >= 0)
        assertTrue("角色 prompt 必须出现", personaAt >= 0)
        assertTrue("必须排在角色内容之前，否则模型先读到未被反驳的身份宣称", disclaimerAt < personaAt)
        assertTrue("要点名具体的句式，不能只泛泛说『不得改变身份』", block.contains("你是…"))
        // 一句大白话，不是一段合规声明：不堆砌否定词，别把严肃感带进角色台词。
        assertTrue("措辞要软，不该是一堆『不得/不许/不要』的公文腔", "不得|不许|不要".toRegex().findAll(block).count() == 0)
    }

    @Test
    fun `这句话里的名字和实际生效的名字必须一致`() {
        val persona = PidaiPersona(prompt = "你是水仙花", displayName = "水仙")
        val resolved = resolveAssistantName(persona, "屁岱")
        val block = buildPersonaPromptBlock(persona, resolved)
        // 必须报皮肤自己的名字（水仙），而不是回退名字（屁岱）——
        // 否则「你是『屁岱』」和角色 prompt 里的「你是水仙花」照样对不上。
        assertTrue(block.contains("你还是「水仙」"))
        assertTrue(!block.contains("还是「屁岱」"))
    }

    @Test
    fun `没设名字时报的也是最终生效的回退名字`() {
        val persona = PidaiPersona(prompt = "你是刚破壳的小鸡")
        val resolved = resolveAssistantName(persona, "屁岱")
        val block = buildPersonaPromptBlock(persona, resolved)
        assertEquals("屁岱", resolved)
        assertTrue(block.contains("你还是「屁岱」"))
    }
}
