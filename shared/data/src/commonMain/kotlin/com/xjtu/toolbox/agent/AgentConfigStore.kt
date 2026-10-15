package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore

/** 屁岱配置的存取（模型 [AgentConfig] 与那几个 `normalize*` 判据在 `:core`）。 */
class AgentConfigStore(
    /** 非敏感那半的存储。 */
    private val plainStoreOf: (String) -> KeyValueStore = { keyValueStore(it) },
    /** 密钥那一半的存储。 */
    private val secureStoreOf: (String) -> KeyValueStore = { suffix ->
        secureKeyValueStore(STORE_SECURE + suffix, legacyFallbackName = STORE_LEGACY + suffix)
    },
) {

    private val plain: KeyValueStore
        get() = plainStoreOf(STORE + AccountContext.safeSuffix())

    private val secure: KeyValueStore
        get() = secureStoreOf(AccountContext.safeSuffix())

    fun load(): AgentConfig = AgentConfig(
        provider = plain.getString("provider") ?: AgentConfig.PROVIDER_DEEPSEEK,
        apiKey = secure.getString("api_key") ?: "",
        model = plain.getString("model") ?: "",
        baseUrl = plain.getString("base_url") ?: "",
        assistantName = sanitizeAgentTitle(
            plain.getString("assistant_name") ?: AgentConfig.DEFAULT_ASSISTANT_NAME
        ),
        searchEngine = plain.getString("search_engine")?.let { raw ->
            when {
                // 老配置里存着已下线的源，静默迁回自动，别让用户卡在一个必然失败的引擎上。
                raw in AgentConfig.RETIRED_SEARCH_ENGINES -> AgentConfig.SEARCH_AUTO
                raw in AgentConfig.SEARCH_ENGINES -> raw
                else -> AgentConfig.SEARCH_AUTO
            }
        } ?: AgentConfig.SEARCH_AUTO,
        thinkingEnabled = plain.getBoolean("thinking_enabled", true),
        reasoningEffort = normalizeReasoningEffort(plain.getString("reasoning_effort")),
        showReasoning = plain.getBoolean("show_reasoning", true)
    )

    fun save(config: AgentConfig) {
        val p = plain
        p.putString("provider", config.provider)
        p.putString("model", config.model)
        p.putString("base_url", config.baseUrl)
        // 工具次数上限、能力开关都已取消，清掉老配置里的残留键
        p.remove("max_tool_calls")
        p.remove("disabled_caps")
        p.putString("assistant_name", sanitizeAgentTitle(config.assistantName))
        p.putString("search_engine", config.searchEngine)
        // 回复风格已取消（只剩亲切一种），清掉老配置里的残留键
        p.remove("response_style")
        p.putBoolean("thinking_enabled", config.thinkingEnabled)
        p.putString("reasoning_effort", config.reasoningEffort)
        p.putBoolean("show_reasoning", config.showReasoning)
        secure.putString("api_key", config.apiKey)
    }

    companion object {
        /** 非敏感那半的存储名（Android 上是 `SharedPreferences` 文件名）。 */
        const val STORE = "agent_config"

        /** 密钥那一半的存储名（Android 上是加密 `SharedPreferences` 文件名）。 */
        const val STORE_SECURE = "agent_config_secure"

        /** 加密之前那一版用的**明文**文件名，只用于迁移。 */
        const val STORE_LEGACY = "agent_config_fallback"
    }
}
