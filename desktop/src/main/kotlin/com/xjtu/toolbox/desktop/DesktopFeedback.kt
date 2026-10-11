package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.feedback.FeedbackApi

/**
 * 把**反馈后端的凭据**交给搬进 `:data` 的 [FeedbackApi]（桌面侧那一份）。
 *
 * 与 `:app` 的 `AppFeedback.install()` 是同一件事、同一个来源：仓库根目录的
 * `feedback.properties`（已 gitignore）或环境变量，由 `desktop/build.gradle.kts` 读出来、
 * 生成成 [DesktopBuildConfig] 里的常量。桌面**不另存一份凭据** —— 那会把「谁能写这张表」
 * 变成两处要守的东西。
 *
 * ## 三个展示字段在桌面上怎么填（如实，不假装是手机）
 *
 * | 列 | Android | 桌面 |
 * |---|---|---|
 * | `版本` | `"5.1.1 (89)"` + preview 后缀 | `"5.1.1 (desktop)"`（桌面包的版本号就是这一个） |
 * | `机型` | `Build.MANUFACTURER + MODEL` | 宿主机 `os.name + os.arch`（Linux / Windows 如实报） |
 * | `系统` | `"Android 14 / API 34"` | `os.name + os.version + Java 版本` |
 *
 * 凭据为空串时 [FeedbackApi.isConfigured] 为 false：反馈页整块表单隐藏、只剩 GitHub 外链
 * （与没带上后端的 Android 包同一条口径）—— 桌面**不**因为"是桌面"就特殊照顾。
 */
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
