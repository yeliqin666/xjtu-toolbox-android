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
    val maxToolCalls: Int = 4
) {
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
                else -> "deepseek-chat"
            }
        }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CUSTOM = "custom"

        val PROVIDERS = listOf(PROVIDER_DEEPSEEK, PROVIDER_OPENAI, PROVIDER_CUSTOM)

        fun providerLabel(p: String) = when (p) {
            PROVIDER_DEEPSEEK -> "DeepSeek（推荐）"
            PROVIDER_OPENAI -> "OpenAI"
            PROVIDER_CUSTOM -> "自定义"
            else -> p
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
        maxToolCalls = prefs.getInt("max_tool_calls", 4)
    )

    fun save(config: AgentConfig) {
        prefs.edit()
            .putString("provider", config.provider)
            .putString("model", config.model)
            .putString("base_url", config.baseUrl)
            .putInt("max_tool_calls", config.maxToolCalls)
            .apply()
        securePrefs.edit()
            .putString("api_key", config.apiKey)
            .apply()
    }
}
