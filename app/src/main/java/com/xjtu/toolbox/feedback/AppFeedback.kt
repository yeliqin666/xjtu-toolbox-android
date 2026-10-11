package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.BuildConfig

/**
 * 把**反馈后端的凭据与那三个展示字段**交给搬进 `:data` 的 [FeedbackApi]。
 *
 * 搬迁前这些值直接写在 `FeedbackApi` 里读 `BuildConfig` / `android.os.Build`；`:data`（JVM +
 * Android 共用）不认识这两样，所以改由宿主在启动时注入（见 `FeedbackApi.Config` 的 KDoc）。
 * 拼串的口径与搬迁前**逐字相同**：
 *
 * | 字段 | 搬迁前 | 现在 |
 * |---|---|---|
 * | `版本` | `"${VERSION_NAME} (${VERSION_CODE})"` + preview 补 `" [preview]"` | 这里拼好整串传进去 |
 * | `机型` | `"${Build.MANUFACTURER} ${Build.MODEL}"` | 同上 |
 * | `系统` | `"Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"` | 同上 |
 *
 * 凭据本身来自 `feedback.properties`（已 gitignore）或环境变量，由 `app/build.gradle.kts`
 * 生成成 `BuildConfig` 字段 —— 一个字符都不经这里，本文件只做搬运。没配时那五个字段都是空串，
 * [FeedbackApi.isConfigured] 为 false ⇒ 反馈页整块表单隐藏、只剩 GitHub 外链（与搬迁前同一条口径）。
 *
 * ⚠️ 必须在 `CrashReporter.uploadPending`（启动后在后台线程跑）之前调 —— 它在
 * `XjtuApp.onCreate` 里调，早于任何上传。
 */
object AppFeedback {

    /** 幂等：重复调只是把同一份值再写一次。 */
    fun install() {
        FeedbackApi.config = FeedbackApi.Config(
            appId = BuildConfig.FEEDBACK_APP_ID,
            appSecret = BuildConfig.FEEDBACK_APP_SECRET,
            baseToken = BuildConfig.FEEDBACK_BASE_TOKEN,
            submitTable = BuildConfig.FEEDBACK_TABLE_SUBMIT,
            replyTable = BuildConfig.FEEDBACK_TABLE_REPLY,
            versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                if (BuildConfig.IS_PREVIEW) " [preview]" else "",
            deviceLabel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            osLabel = "Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}",
        )
    }
}
