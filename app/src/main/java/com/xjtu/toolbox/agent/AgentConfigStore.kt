package com.xjtu.toolbox.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.xjtu.toolbox.account.AccountContext

data class AgentConfig(
    val provider: String = PROVIDER_DEEPSEEK,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    // 8 而非 4：全部工具都是只读查询，没有副作用风险，而"搜一次 + 读两个链接"
    // 或"课表 + 空教室 + 图书馆"这类再正常不过的连环问，4 次直接打满。
    val maxToolCalls: Int = 8,
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    val disabledCaps: Set<String> = emptySet(),
    val searchEngine: String = SEARCH_AUTO,
    val responseStyle: String = STYLE_FRIENDLY,
    val thinkingEnabled: Boolean = true,
    val reasoningEffort: String = REASONING_AUTO,
    val showReasoning: Boolean = true
) {
    val effectiveName: String get() = sanitizeAgentTitle(assistantName, DEFAULT_ASSISTANT_NAME)

    val effectiveBaseUrl: String
        get() = when {
            provider == PROVIDER_CUSTOM && baseUrl.isNotBlank() -> baseUrl.trimEnd('/')
            provider == PROVIDER_OPENAI -> "https://api.openai.com/v1"
            else -> "https://api.deepseek.com"
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
        const val SEARCH_AUTO = "auto"
        const val SEARCH_BING = "bing"
        const val SEARCH_SOGOU = "sogou"
        const val SEARCH_WECHAT = "wechat"
        const val SEARCH_DDG = "duckduckgo"
        const val SEARCH_JINA = "jina"
        const val SEARCH_SO360 = "so360"
        const val SEARCH_BRAVE = "brave"
        const val STYLE_FRIENDLY = "friendly"
        const val STYLE_PROFESSIONAL = "professional"

        val PROVIDERS = listOf(PROVIDER_DEEPSEEK, PROVIDER_OPENAI, PROVIDER_CUSTOM)
        val REASONING_EFFORTS = listOf(REASONING_AUTO, REASONING_HIGH, REASONING_MAX)
        val SEARCH_ENGINES = listOf(SEARCH_AUTO, SEARCH_SO360, SEARCH_DDG, SEARCH_WECHAT, SEARCH_SOGOU)
        val RESPONSE_STYLES = listOf(STYLE_FRIENDLY, STYLE_PROFESSIONAL)

        fun providerLabel(p: String) = when (p) {
            PROVIDER_DEEPSEEK -> "DeepSeek（推荐）"
            PROVIDER_OPENAI -> "OpenAI"
            PROVIDER_CUSTOM -> "自定义"
            else -> p
        }

        fun searchEngineLabel(engine: String) = when (engine) {
            SEARCH_AUTO -> "自动（推荐）"
            SEARCH_SO360 -> "360 搜索"
            SEARCH_SOGOU -> "搜狗网页"
            SEARCH_WECHAT -> "搜狗微信"
            SEARCH_DDG -> "DuckDuckGo"
            SEARCH_JINA -> "Jina"
            SEARCH_BRAVE -> "Brave"
            SEARCH_BING -> "Bing"
            else -> "自动"
        }

        fun responseStyleLabel(style: String) = when (style) {
            STYLE_PROFESSIONAL -> "专业"
            else -> "亲切"
        }
    }
}

fun sanitizeAgentTitle(raw: String, fallback: String = AgentConfig.DEFAULT_ASSISTANT_NAME): String {
    val normalized = raw
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { fallback }
    return if (normalized.length <= 12) normalized else normalized.take(12) + "…"
}

class AgentConfigStore(context: Context) {
    private val appContext = context.applicationContext

/**
 * EncryptedSharedPreferences 按账号缓存。
 *
 * **为什么需要缓存**：
 * - `EncryptedSharedPreferences.create()` 每次都做 keystore 密钥派生（一次 ~50–200ms），
 *   在 `ConfigPanel` 每次按键都触发 `save()` 的场景下会让 UI 卡顿数秒。
 * - 用 `lazy` 一次性建好后，按账号缓存；切账号时新建。
 *
 * **为什么不用 `lazy {}` 全局**：
 * - 全局 lazy 会导致切账号后还读到旧账号的 prefs，违反账号隔离。
 *   所以必须按 `safeSuffix()` 分别缓存。
 */
private val securePrefsCache = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
private val prefsCache = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()

private val securePrefs: SharedPreferences
    get() = securePrefsCache.getOrPut(AccountContext.safeSuffix()) {
        try {
            EncryptedSharedPreferences.create(
                "agent_config_secure${AccountContext.safeSuffix()}",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences("agent_config_fallback${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)
        }
    }

private val prefs: SharedPreferences
    get() = prefsCache.getOrPut(AccountContext.safeSuffix()) {
        appContext.getSharedPreferences("agent_config${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)
    }

    fun load(): AgentConfig = AgentConfig(
        provider = prefs.getString("provider", AgentConfig.PROVIDER_DEEPSEEK) ?: AgentConfig.PROVIDER_DEEPSEEK,
        apiKey = securePrefs.getString("api_key", "") ?: "",
        model = prefs.getString("model", "") ?: "",
        baseUrl = prefs.getString("base_url", "") ?: "",
        maxToolCalls = prefs.getInt("max_tool_calls", 8),
        assistantName = sanitizeAgentTitle(
            prefs.getString("assistant_name", AgentConfig.DEFAULT_ASSISTANT_NAME)
                ?: AgentConfig.DEFAULT_ASSISTANT_NAME
        ),
        disabledCaps = prefs.getString("disabled_caps", "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
        searchEngine = prefs.getString("search_engine", AgentConfig.SEARCH_AUTO)
            ?.let { raw ->
                when (raw) {
                    AgentConfig.SEARCH_BING, AgentConfig.SEARCH_JINA, AgentConfig.SEARCH_BRAVE ->
                        AgentConfig.SEARCH_AUTO
                    in AgentConfig.SEARCH_ENGINES -> raw
                    else -> AgentConfig.SEARCH_AUTO
                }
            }
            ?: AgentConfig.SEARCH_AUTO,
        responseStyle = prefs.getString("response_style", AgentConfig.STYLE_FRIENDLY)
            ?.takeIf { it in AgentConfig.RESPONSE_STYLES }
            ?: AgentConfig.STYLE_FRIENDLY,
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
            .putString("assistant_name", sanitizeAgentTitle(config.assistantName))
            .putString("disabled_caps", config.disabledCaps.sorted().joinToString(","))
            .putString("search_engine", config.searchEngine)
            .putString("response_style", config.responseStyle)
            .putBoolean("thinking_enabled", config.thinkingEnabled)
            .putString("reasoning_effort", config.reasoningEffort)
            .putBoolean("show_reasoning", config.showReasoning)
            .apply()
        securePrefs.edit()
            .putString("api_key", config.apiKey)
            .apply()
    }
}
