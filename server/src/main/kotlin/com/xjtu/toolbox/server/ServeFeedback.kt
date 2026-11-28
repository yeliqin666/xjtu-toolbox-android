package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi

/** 把反馈后端地址交给 `:data` 的 [FeedbackApi]（serve 端那一份）。 */
internal object ServeFeedback {

    /**
     * 幂等：在 [Main] 里启动前调一次。
     * 未显式设 [ENV_BASE_URL] 时默认指到正式 Worker（[FeedbackApi.DEFAULT_BASE_URL]）；
     * 显式设成空串则视为禁用反馈功能（`FeedbackApi.config = null`）。
     */
    fun install(env: Map<String, String> = System.getenv()) {
        val raw = env[ENV_BASE_URL]
        val baseUrl = (raw ?: FeedbackApi.DEFAULT_BASE_URL).trim()
        FeedbackApi.config = if (baseUrl.isEmpty()) {
            null
        } else {
            FeedbackApi.Config(
                baseUrl = baseUrl,
                versionLabel = VERSION_LABEL,
                deviceLabel = "${osName()} ${System.getProperty("os.arch").orEmpty()}".trim(),
                osLabel = "${osName()} ${System.getProperty("os.version").orEmpty()}" +
                    " / Java ${System.getProperty("java.version").orEmpty()}",
            )
        }
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty()

    /** 「版本」列的值：serve 没有自己的版本号，写这一端的名字。 */
    private const val VERSION_LABEL = "serve"

    internal const val ENV_BASE_URL = "FEEDBACK_BASE_URL"
}
