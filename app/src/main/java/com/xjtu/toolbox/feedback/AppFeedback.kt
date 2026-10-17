package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.BuildConfig

/** 把反馈后端的凭据与三个展示字段交给 `:data` 的 [FeedbackApi]。 */
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
