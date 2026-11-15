package com.xjtu.toolbox.feedback

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.Context
import com.xjtu.toolbox.BuildConfig

/** 把反馈后端的凭据与展示字段交给 `:data` 的 [FeedbackApi]。 */
object AppFeedback {

    /** 幂等：重复调只是把同一份值再写一次。 */
    fun install(context: Context) {
        val app = context.applicationContext
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
            diagnostics = { startupSummary(app) },
        )
    }

    /** 近几次冷启动「拉起进程 → 首帧」的耗时，用户说「打开慢」时有据可查。 */
    private fun startupSummary(context: Context): String {
        val starts = context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessStartReasons(10).orEmpty()
        val cold = starts.filter { it.startType == ApplicationStartInfo.START_TYPE_COLD }.mapNotNull { info ->
            val t = info.startupTimestamps
            val launch = t[ApplicationStartInfo.START_TIMESTAMP_LAUNCH] ?: return@mapNotNull null
            val frame = t[ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME] ?: return@mapNotNull null
            (frame - launch) / 1_000_000
        }.filter { it > 0 }
        if (cold.isEmpty()) return ""
        return "冷启动 ${cold.size} 次中位 ${cold.sorted()[cold.size / 2]}ms，最慢 ${cold.max()}ms"
    }
}
