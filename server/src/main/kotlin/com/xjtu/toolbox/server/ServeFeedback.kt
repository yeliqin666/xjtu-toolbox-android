package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi

/** 把反馈后端的凭据交给 `:data` 的 [FeedbackApi]（serve 端那一份）。 */
internal object ServeFeedback {

    /** 幂等：在 [Main] 里启动前调一次。 */
    fun install(env: Map<String, String> = System.getenv()) {
        fun cfg(name: String): String = env[name].orEmpty().trim()

        val appId = cfg(ENV_APP_ID)
        val appSecret = cfg(ENV_APP_SECRET)
        val baseToken = cfg(ENV_BASE_TOKEN)
        FeedbackApi.config = if (appId.isEmpty() || appSecret.isEmpty() || baseToken.isEmpty()) {
            // 三个必填缺一个 ⇒ 整条链不可用（`FeedbackApi.isConfigured` 的判据就是这三个非空）
            null
        } else {
            FeedbackApi.Config(
                appId = appId,
                appSecret = appSecret,
                baseToken = baseToken,
                submitTable = cfg(ENV_TABLE_SUBMIT),
                replyTable = cfg(ENV_TABLE_REPLY),
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

    internal const val ENV_APP_ID = "FEEDBACK_APP_ID"
    internal const val ENV_APP_SECRET = "FEEDBACK_APP_SECRET"
    internal const val ENV_BASE_TOKEN = "FEEDBACK_BASE_TOKEN"
    internal const val ENV_TABLE_SUBMIT = "FEEDBACK_TABLE_SUBMIT"
    internal const val ENV_TABLE_REPLY = "FEEDBACK_TABLE_REPLY"
}
