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
        assistantName = loadAssistantName(plain),
        searchEngine = normalizeSearchEngine(plain.getString("search_engine")),
        searchApiKey = secure.getString("search_api_key").orEmpty(),
        thinkingEnabled = plain.getBoolean("thinking_enabled", true),
        reasoningEffort = normalizeReasoningEffort(plain.getString("reasoning_effort")),
        showReasoning = plain.getBoolean("show_reasoning", true),
        mcpEnabled = plain.getBoolean("mcp_enabled", false),
        mcpEndpoint = plain.getString("mcp_endpoint").orEmpty(),
        mcpToken = secure.getString("mcp_token").orEmpty(),
        apiFormat = plain.getString("api_format")?.takeIf { it in AgentConfig.API_FORMATS }.orEmpty(),
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
        // 这一份名字是新版写的：哪怕正好是「屁岱」，也是用户自己起的，读回来不再换成新默认名
        p.putBoolean(KEY_NAME_EXPLICIT, true)
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
        p.putString("api_format", config.apiFormat)
    }

    /**
     * 旧默认名「屁岱」只对**改默认名之前**写下的老配置迁到新默认名。
     * 以前每次读都把「屁岱」换掉，用户亲手把名字改成「屁岱」就永远存不上（读回来又是「小岱」）。
     */
    private fun loadAssistantName(p: KeyValueStore): String {
        val raw = p.getString("assistant_name")
        val legacyDefault = raw == AgentConfig.LEGACY_ASSISTANT_NAME && !p.getBoolean(KEY_NAME_EXPLICIT, false)
        return sanitizeAgentTitle(raw?.takeUnless { legacyDefault } ?: AgentConfig.DEFAULT_ASSISTANT_NAME)
    }

    companion object {
        /** 新版 [save] 写名字时一并写上：有它 ⇒ 名字是用户起的，不做旧默认名迁移。 */
        const val KEY_NAME_EXPLICIT = "assistant_name_explicit"

        /** 非敏感那半的存储名（Android 上是 `SharedPreferences` 文件名）。 */
        const val STORE = "agent_config"

        /** 密钥那一半的存储名（Android 上是加密 `SharedPreferences` 文件名）。 */
        const val STORE_SECURE = "agent_config_secure"

        /** 加密之前那一版用的**明文**文件名，只用于迁移。 */
        const val STORE_LEGACY = "agent_config_fallback"
    }
}
