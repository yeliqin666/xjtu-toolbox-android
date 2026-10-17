package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.feedback.FeedbackApi

/** 把反馈后端的凭据交给 `:data` 的 [FeedbackApi]。 */
internal object DesktopFeedback {

    /** 幂等：重复调只是把同一份值再写一次。 */
    fun install() {
        FeedbackApi.config = FeedbackApi.Config(
            appId = DesktopBuildConfig.FEEDBACK_APP_ID,
            appSecret = DesktopBuildConfig.FEEDBACK_APP_SECRET,
            baseToken = DesktopBuildConfig.FEEDBACK_BASE_TOKEN,
            submitTable = DesktopBuildConfig.FEEDBACK_TABLE_SUBMIT,
            replyTable = DesktopBuildConfig.FEEDBACK_TABLE_REPLY,
            versionLabel = "${DesktopBuildConfig.VERSION_NAME} (desktop)",
            deviceLabel = "${osName()} ${System.getProperty("os.arch").orEmpty()}".trim(),
            osLabel = "${osName()} ${System.getProperty("os.version").orEmpty()}" +
                " / Java ${System.getProperty("java.version").orEmpty()}",
        )
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty()
}
