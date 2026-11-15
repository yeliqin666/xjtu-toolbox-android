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
            plain.getString("assistant_name")?.takeIf { it != AgentConfig.LEGACY_ASSISTANT_NAME }
                ?: AgentConfig.DEFAULT_ASSISTANT_NAME
        ),
        searchEngine = normalizeSearchEngine(plain.getString("search_engine")),
        searchApiKey = secure.getString("search_api_key").orEmpty(),
        thinkingEnabled = plain.getBoolean("thinking_enabled", true),
        reasoningEffort = normalizeReasoningEffort(plain.getString("reasoning_effort")),
        showReasoning = plain.getBoolean("show_reasoning", true),
        mcpEnabled = plain.getBoolean("mcp_enabled", false),
        mcpEndpoint = plain.getString("mcp_endpoint").orEmpty(),
        mcpToken = secure.getString("mcp_token").orEmpty(),
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
        secure.putString("search_api_key", config.searchApiKey)
        p.putBoolean("mcp_enabled", config.mcpEnabled)
        p.putString("mcp_endpoint", config.mcpEndpoint.trim())
        secure.putString("mcp_token", config.mcpToken.trim())
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
