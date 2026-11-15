package com.xjtu.toolbox.agent

/**
 * 存下来的思考强度收敛到本地支持的档位。
 *
 * DeepSeek 的别名（`minimal`→low、`medium`/`xhigh`→high）也认：用户可能在别处见过这些
 * 写法，或者以后服务端改口径。认不出的一律回落到「自动」——下发一个服务端不认的值，
 * 整个请求会 400，表现是屁岱一句话都不说。
 */
fun normalizeReasoningEffort(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "minimal", AgentConfig.REASONING_LOW -> AgentConfig.REASONING_LOW
    "medium", "xhigh", AgentConfig.REASONING_HIGH -> AgentConfig.REASONING_HIGH
    AgentConfig.REASONING_MAX -> AgentConfig.REASONING_MAX
    else -> AgentConfig.REASONING_AUTO
}

/** 存下来的搜索源收敛到 [AgentConfig.SEARCH_ENGINES]；百度、360 等已下线的自建源一律迁到 DeepSeek。 */
fun normalizeSearchEngine(raw: String?): String =
    if (raw?.trim()?.lowercase() == AgentConfig.SEARCH_OFF) AgentConfig.SEARCH_OFF else AgentConfig.SEARCH_DEEPSEEK

/** 屁岱的配置：服务商、密钥、模型、助手名、联网搜索、思考开关。 */
data class AgentConfig(
    val provider: String = PROVIDER_DEEPSEEK,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    val searchEngine: String = SEARCH_DEEPSEEK,
    /** 服务商不是 DeepSeek 时，联网搜索单独用的 DeepSeek Key。 */
    val searchApiKey: String = "",
    val thinkingEnabled: Boolean = true,
    val reasoningEffort: String = REASONING_AUTO,
    val showReasoning: Boolean = true,
    /** 「这一端知道 key 配好了，但 key 不在我手上」这一格。 */
    val keyConfigured: Boolean = false,
    /** 同上，指 [searchApiKey]。 */
    val searchKeyConfigured: Boolean = false,
    val mcpEnabled: Boolean = false,
    val mcpEndpoint: String = "",
    val mcpToken: String = "",
) {
    val effectiveName: String get() = sanitizeAgentTitle(assistantName, DEFAULT_ASSISTANT_NAME)

    val effectiveBaseUrl: String
        get() = when {
            provider == PROVIDER_CUSTOM && baseUrl.isNotBlank() -> baseUrl.trimEnd('/')
            provider == PROVIDER_OPENAI -> "https://api.openai.com/v1"
            else -> DEEPSEEK_BASE_URL
        }

    /** 联网搜索、读网页用的 DeepSeek Key：聊天就走 DeepSeek 时复用它。空串 = 不给这两件工具。 */
    val webSearchKey: String
        get() = when {
            searchEngine == SEARCH_OFF -> ""
            provider == PROVIDER_DEEPSEEK -> apiKey
            else -> searchApiKey
        }.trim()

    val effectiveModel: String
        get() = model.ifBlank {
            when (provider) {
                PROVIDER_OPENAI -> "gpt-4o-mini"
                // `deepseek-v4-flash` 是旧名，模型已退役，请求被转到 V4.1-Flash 计费。
                // 直接用现名，免得日后旧名真的下线。
                else -> "deepseek-flash"
            }
        }

    val isConfigured: Boolean get() = apiKey.isNotBlank() || keyConfigured

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "小岱"
        /** 旧默认名：存着它的老用户等于没改过名，跟着换成新默认。 */
        const val LEGACY_ASSISTANT_NAME = "屁岱"
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CUSTOM = "custom"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEEPSEEK_KEYS_URL = "https://platform.deepseek.com/api_keys"
        /** 不下发 `reasoning_effort`，由服务端按模型默认档跑。不是 DeepSeek 的合法取值。 */
        const val REASONING_AUTO = "auto"
        const val REASONING_LOW = "low"
        const val REASONING_HIGH = "high"
        const val REASONING_MAX = "max"
        /** DeepSeek 官方 Search / Fetch API。 */
        const val SEARCH_DEEPSEEK = "deepseek"
        const val SEARCH_OFF = "off"
        val SEARCH_ENGINES = listOf(SEARCH_DEEPSEEK, SEARCH_OFF)

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
        fun providerLabel(p: String) = when (p) {
            PROVIDER_DEEPSEEK -> "DeepSeek（推荐）"
            PROVIDER_OPENAI -> "OpenAI"
            PROVIDER_CUSTOM -> "自定义"
            else -> p
        }

        fun searchEngineLabel(engine: String) = when (engine) {
            SEARCH_OFF -> "关闭"
            else -> "DeepSeek"
        }
    }
}

/** 会话标题的硬上限（字）：模型被要求写 8 字以内，留两字余量。助手名字仍按默认的 12 字。 */
const val AGENT_TITLE_MAX_CHARS = 10

/** 折叠空白、空则回落 [fallback]；超过 [maxChars] 截断加省略号。 */
fun sanitizeAgentTitle(raw: String, fallback: String = AgentConfig.DEFAULT_ASSISTANT_NAME, maxChars: Int = 12): String {
    val normalized = raw
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { fallback }
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "…"
}
