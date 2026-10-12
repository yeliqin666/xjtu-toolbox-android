package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 屁岱配置的**模型与口径**（`AgentConfig` 那一份，存取在 `:data` 的 `AgentConfigStore`）。
 *
 * 这些判据以前散在 `:app` 的 `AgentConfigStore.kt` 里、只能靠 Android 单测覆盖；搬进 `:core`
 * 之后三端共用同一份默认值与标签 —— 桌面端的配置页显示的就是这里算出来的字。
 */
class AgentConfigTest {

    @Test
    fun `思考强度的别名与垃圾值都收敛到本地档位`() {
        // DeepSeek 的别名也认（用户可能在别处见过这些写法）
        assertEquals(AgentConfig.REASONING_LOW, normalizeReasoningEffort("minimal"))
        assertEquals(AgentConfig.REASONING_LOW, normalizeReasoningEffort("low"))
        assertEquals(AgentConfig.REASONING_HIGH, normalizeReasoningEffort("medium"))
        assertEquals(AgentConfig.REASONING_HIGH, normalizeReasoningEffort("xhigh"))
        assertEquals(AgentConfig.REASONING_HIGH, normalizeReasoningEffort("HIGH"))
        assertEquals(AgentConfig.REASONING_MAX, normalizeReasoningEffort(" max "))
        // 认不出、缺值、空串一律回落到「自动」——下发服务端不认的值会让整个请求 400
        assertEquals(AgentConfig.REASONING_AUTO, normalizeReasoningEffort("highest"))
        assertEquals(AgentConfig.REASONING_AUTO, normalizeReasoningEffort(""))
        assertEquals(AgentConfig.REASONING_AUTO, normalizeReasoningEffort(null))
    }

    @Test
    fun `baseUrl 按服务商取，自定义那条去掉尾斜杠`() {
        assertEquals("https://api.deepseek.com", AgentConfig(provider = AgentConfig.PROVIDER_DEEPSEEK).effectiveBaseUrl)
        assertEquals("https://api.openai.com/v1", AgentConfig(provider = AgentConfig.PROVIDER_OPENAI).effectiveBaseUrl)
        assertEquals(
            "https://example.invalid/v1",
            AgentConfig(provider = AgentConfig.PROVIDER_CUSTOM, baseUrl = "https://example.invalid/v1//").effectiveBaseUrl,
        )
        // 自定义但没填地址 ⇒ 退回 DeepSeek 的默认（与搬迁前一致：不填就是官方）
        assertEquals("https://api.deepseek.com", AgentConfig(provider = AgentConfig.PROVIDER_CUSTOM).effectiveBaseUrl)
    }

    @Test
    fun `模型名留空时按服务商取默认，填了就用手填的`() {
        assertEquals("deepseek-flash", AgentConfig().effectiveModel)
        assertEquals("gpt-4o-mini", AgentConfig(provider = AgentConfig.PROVIDER_OPENAI).effectiveModel)
        assertEquals("my-model", AgentConfig(model = "my-model").effectiveModel)
    }

    @Test
    fun `配了密钥才算配置完成`() {
        assertFalse(AgentConfig().isConfigured)
        assertFalse(AgentConfig(apiKey = "   ").isConfigured)
        assertTrue(AgentConfig(apiKey = "sk-假的-测试值").isConfigured)
    }

    @Test
    fun `助手名折叠空白、超 12 字截断、空则回落默认`() {
        assertEquals("屁岱", sanitizeAgentTitle(""))
        assertEquals("屁岱", sanitizeAgentTitle("   "))
        assertEquals("小 屁 岱", sanitizeAgentTitle("小 \n 屁\t岱"))
        // 12 字（一/二/…/十/十/一）不截断，13 字开始截
        assertEquals("一二三四五六七八九十十一", sanitizeAgentTitle("一二三四五六七八九十十一"))
        assertEquals("一二三四五六七八九十十一…", sanitizeAgentTitle("一二三四五六七八九十十一十二"))
        // effectiveName 走的就是这条判据
        assertEquals("屁岱", AgentConfig(assistantName = "").effectiveName)
    }

    @Test
    fun `标签表与档位清单与搬迁前一致`() {
        assertEquals(listOf("deepseek", "openai", "custom"), AgentConfig.PROVIDERS)
        assertEquals(listOf("auto", "low", "high", "max"), AgentConfig.REASONING_EFFORTS)
        assertEquals(listOf("auto", "baidu", "so360", "wechat", "wiki"), AgentConfig.SEARCH_ENGINES)
        assertEquals("DeepSeek（推荐）", AgentConfig.providerLabel(AgentConfig.PROVIDER_DEEPSEEK))
        assertEquals("OpenAI", AgentConfig.providerLabel(AgentConfig.PROVIDER_OPENAI))
        assertEquals("自定义", AgentConfig.providerLabel(AgentConfig.PROVIDER_CUSTOM))
        assertEquals("自动", AgentConfig.reasoningEffortLabel(AgentConfig.REASONING_AUTO))
        assertEquals("低", AgentConfig.reasoningEffortLabel(AgentConfig.REASONING_LOW))
        assertEquals("最大", AgentConfig.reasoningEffortLabel(AgentConfig.REASONING_MAX))
        assertEquals("维基百科", AgentConfig.searchEngineLabel(AgentConfig.SEARCH_WIKI))
        // 认不出的服务商名原样显示、认不出的搜索源按自动显示
        assertEquals("别的服务商", AgentConfig.providerLabel("别的服务商"))
        assertEquals("自动", AgentConfig.searchEngineLabel("sogou"))
    }

    @Test
    fun `已下线的搜索源留在清单外，但名字仍然认得出`() {
        AgentConfig.RETIRED_SEARCH_ENGINES.forEach {
            assertFalse(it in AgentConfig.SEARCH_ENGINES, "$it 不该出现在可选清单里")
        }
        assertTrue("bing" in AgentConfig.RETIRED_SEARCH_ENGINES)
    }
}
