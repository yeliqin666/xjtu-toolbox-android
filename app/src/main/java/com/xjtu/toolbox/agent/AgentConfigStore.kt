package com.xjtu.toolbox.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class AgentConfig(
    val provider: String = PROVIDER_DEEPSEEK,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val maxToolCalls: Int = 4,
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    val disabledCaps: Set<String> = emptySet(),
    val searchEngine: String = SEARCH_BING,
    val thinkingEnabled: Boolean = true,
    val reasoningEffort: String = REASONING_AUTO,
    val showReasoning: Boolean = true
) {
    val effectiveName: String get() = assistantName.trim().ifBlank { DEFAULT_ASSISTANT_NAME }

    val effectiveBaseUrl: String
        get() = when {
            provider == PROVIDER_CUSTOM && baseUrl.isNotBlank() -> baseUrl.trimEnd('/')
            provider == PROVIDER_OPENAI -> "https://api.openai.com/v1"
            else -> "https://api.deepseek.com/v1"
        }

    val effectiveModel: String
        get() = model.ifBlank {
            when (provider) {
                PROVIDER_OPENAI -> "gpt-4o-mini"
                else -> "deepseek-v4-flash"
            }
        }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "屁岱"
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CUSTOM = "custom"
        const val REASONING_AUTO = "auto"
        const val REASONING_HIGH = "high"
        const val REASONING_MAX = "max"
        const val SEARCH_BING = "bing"
        const val SEARCH_SOGOU = "sogou"
        const val SEARCH_WECHAT = "wechat"

        val PROVIDERS = listOf(PROVIDER_DEEPSEEK, PROVIDER_OPENAI, PROVIDER_CUSTOM)
        val REASONING_EFFORTS = listOf(REASONING_AUTO, REASONING_HIGH, REASONING_MAX)
        val SEARCH_ENGINES = listOf(SEARCH_BING, SEARCH_SOGOU, SEARCH_WECHAT)

        fun providerLabel(p: String) = when (p) {
            PROVIDER_DEEPSEEK -> "DeepSeek（推荐）"
            PROVIDER_OPENAI -> "OpenAI"
            PROVIDER_CUSTOM -> "自定义"
            else -> p
        }

        fun searchEngineLabel(engine: String) = when (engine) {
            SEARCH_SOGOU -> "搜狗网页"
            SEARCH_WECHAT -> "搜狗微信"
            else -> "Bing"
        }
    }
}

class AgentConfigStore(context: Context) {
    private val appContext = context.applicationContext

    // API Key 用 EncryptedSharedPreferences 存储；失败则降级为普通 SharedPreferences（不明文持久化于代码层面已尽力）
    private val securePrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                "agent_config_secure",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences("agent_config_fallback", Context.MODE_PRIVATE)
        }
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("agent_config", Context.MODE_PRIVATE)

    fun load(): AgentConfig = AgentConfig(
        provider = prefs.getString("provider", AgentConfig.PROVIDER_DEEPSEEK) ?: AgentConfig.PROVIDER_DEEPSEEK,
        apiKey = securePrefs.getString("api_key", "") ?: "",
        model = prefs.getString("model", "") ?: "",
        baseUrl = prefs.getString("base_url", "") ?: "",
        maxToolCalls = prefs.getInt("max_tool_calls", 4),
        assistantName = prefs.getString("assistant_name", AgentConfig.DEFAULT_ASSISTANT_NAME)
            ?: AgentConfig.DEFAULT_ASSISTANT_NAME,
        disabledCaps = prefs.getString("disabled_caps", "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
        searchEngine = prefs.getString("search_engine", AgentConfig.SEARCH_BING)
            ?.takeIf { it in AgentConfig.SEARCH_ENGINES }
            ?: AgentConfig.SEARCH_BING,
        thinkingEnabled = prefs.getBoolean("thinking_enabled", true),
        reasoningEffort = prefs.getString("reasoning_effort", AgentConfig.REASONING_AUTO)
            ?.takeIf { it in AgentConfig.REASONING_EFFORTS }
            ?: AgentConfig.REASONING_AUTO,
        showReasoning = prefs.getBoolean("show_reasoning", true)
    )

    fun save(config: AgentConfig) {
        prefs.edit()
            .putString("provider", config.provider)
            .putString("model", config.model)
            .putString("base_url", config.baseUrl)
            .putInt("max_tool_calls", config.maxToolCalls)
            .putString("assistant_name", config.assistantName)
            .putString("disabled_caps", config.disabledCaps.sorted().joinToString(","))
            .putString("search_engine", config.searchEngine)
            .putBoolean("thinking_enabled", config.thinkingEnabled)
            .putString("reasoning_effort", config.reasoningEffort)
            .putBoolean("show_reasoning", config.showReasoning)
            .apply()
        securePrefs.edit()
            .putString("api_key", config.apiKey)
            .apply()
    }
}
