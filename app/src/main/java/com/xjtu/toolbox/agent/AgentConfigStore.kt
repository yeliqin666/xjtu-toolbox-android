package com.xjtu.toolbox.agent

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.data.SecurePrefs

/**
 * 把存下来的思考强度收敛到本地支持的档位。
 *
 * DeepSeek 的别名（`minimal`→low、`medium`/`xhigh`→high）也认：用户可能在别处见过这些
 * 写法，或者以后服务端改口径。认不出的一律回落到「自动」——下发一个服务端不认的值，
 * 整个请求会 400，表现是屁岱一句话都不说。
 */
internal fun normalizeReasoningEffort(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "minimal", AgentConfig.REASONING_LOW -> AgentConfig.REASONING_LOW
    "medium", "xhigh", AgentConfig.REASONING_HIGH -> AgentConfig.REASONING_HIGH
    AgentConfig.REASONING_MAX -> AgentConfig.REASONING_MAX
    else -> AgentConfig.REASONING_AUTO
}

data class AgentConfig(
    val provider: String = PROVIDER_DEEPSEEK,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    val searchEngine: String = SEARCH_AUTO,
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
                // `deepseek-v4-flash` 是旧名，模型已退役，请求被转到 V4.1-Flash 计费。
                // 直接用现名，免得日后旧名真的下线。
                else -> "deepseek-flash"
            }
        }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "屁岱"
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CUSTOM = "custom"
        /** 不下发 `reasoning_effort`，由服务端按模型默认档跑。不是 DeepSeek 的合法取值。 */
        const val REASONING_AUTO = "auto"
        const val REASONING_LOW = "low"
        const val REASONING_HIGH = "high"
        const val REASONING_MAX = "max"
        const val SEARCH_AUTO = "auto"
        const val SEARCH_BAIDU = "baidu"
        const val SEARCH_WECHAT = "wechat"
        const val SEARCH_SO360 = "so360"
        const val SEARCH_WIKI = "wiki"

        /**
         * 已下线的搜索源。实测（国内网络 / 校园网，中文查询 + 连发）：
         * - jina：`s.jina.ai` 匿名访问返回 401，要 API Key，调用必然失败。
         * - brave：429 + 验证码页，拿不到结果。
         * - sogou：`/web` 直接 302 到 `sogou.com/antispider`，一条都取不到。
         * - duckduckgo（2026-09）：html / lite 两版都回 202 +「证明你是人类」挑战页，
         *   国内每次白等两秒多，一条结果都没有。
         * - bing（2026-09）：不带会话时中文查询被截成首字（「西交 创新港 校车」搜的是「西」），
         *   RSS 和网页版都一样，结果与查询基本无关。
         *
         * 留着它们只会白占一轮超时。保留名字是为了老配置和模型点名时能识别并迁回自动。
         */
        val RETIRED_SEARCH_ENGINES = setOf("jina", "brave", "sogou", "duckduckgo", "ddg", "bing")

        val PROVIDERS = listOf(PROVIDER_DEEPSEEK, PROVIDER_OPENAI, PROVIDER_CUSTOM)
        /**
         * DeepSeek 新版思考参数族是 `none / low / high / max`（`minimal`→low，
         * `medium`、`xhigh`→high）。这里少了 `low`，用户只能在「高」和「最大」之间选，
         * 想省钱省时间没有档位；`none` 不进列表，它等价于关掉思考开关。
         */
        val REASONING_EFFORTS = listOf(REASONING_AUTO, REASONING_LOW, REASONING_HIGH, REASONING_MAX)

        fun reasoningEffortLabel(effort: String) = when (effort) {
            REASONING_LOW -> "低"
            REASONING_HIGH -> "高"
            REASONING_MAX -> "最大"
            else -> "自动"
        }
        // 顺序即推荐度，实测中文相关性从高到低。自动 = 百度与 360 并发、合并去重。
        val SEARCH_ENGINES = listOf(
            SEARCH_AUTO, SEARCH_BAIDU, SEARCH_SO360, SEARCH_WECHAT, SEARCH_WIKI
        )

        fun providerLabel(p: String) = when (p) {
            PROVIDER_DEEPSEEK -> "DeepSeek（推荐）"
            PROVIDER_OPENAI -> "OpenAI"
            PROVIDER_CUSTOM -> "自定义"
            else -> p
        }

        fun searchEngineLabel(engine: String) = when (engine) {
            SEARCH_AUTO -> "自动"
            SEARCH_BAIDU -> "百度"
            SEARCH_SO360 -> "360 搜索"
            SEARCH_WECHAT -> "搜狗微信"
            SEARCH_WIKI -> "维基百科"
            else -> "自动"
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
        SecurePrefs.open(
            appContext,
            "agent_config_secure${AccountContext.safeSuffix()}",
            legacyFallbackName = "agent_config_fallback${AccountContext.safeSuffix()}",
        )
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
        assistantName = sanitizeAgentTitle(
            prefs.getString("assistant_name", AgentConfig.DEFAULT_ASSISTANT_NAME)
                ?: AgentConfig.DEFAULT_ASSISTANT_NAME
        ),
        searchEngine = prefs.getString("search_engine", AgentConfig.SEARCH_AUTO)
            ?.let { raw ->
                when {
                    // 老配置里存着已下线的源，静默迁回自动，别让用户卡在一个必然失败的引擎上。
                    raw in AgentConfig.RETIRED_SEARCH_ENGINES -> AgentConfig.SEARCH_AUTO
                    raw in AgentConfig.SEARCH_ENGINES -> raw
                    else -> AgentConfig.SEARCH_AUTO
                }
            }
            ?: AgentConfig.SEARCH_AUTO,
        thinkingEnabled = prefs.getBoolean("thinking_enabled", true),
        reasoningEffort = normalizeReasoningEffort(
            prefs.getString("reasoning_effort", AgentConfig.REASONING_AUTO)
        ),
        showReasoning = prefs.getBoolean("show_reasoning", true)
    )

    fun save(config: AgentConfig) {
        prefs.edit()
            .putString("provider", config.provider)
            .putString("model", config.model)
            .putString("base_url", config.baseUrl)
            // 工具次数上限、能力开关都已取消，清掉老配置里的残留键
            .remove("max_tool_calls")
            .remove("disabled_caps")
            .putString("assistant_name", sanitizeAgentTitle(config.assistantName))
            .putString("search_engine", config.searchEngine)
            // 回复风格已取消（只剩亲切一种），清掉老配置里的残留键
            .remove("response_style")
            .putBoolean("thinking_enabled", config.thinkingEnabled)
            .putString("reasoning_effort", config.reasoningEffort)
            .putBoolean("show_reasoning", config.showReasoning)
            .apply()
        securePrefs.edit()
            .putString("api_key", config.apiKey)
            .apply()
    }
}
