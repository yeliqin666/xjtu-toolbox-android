package com.xjtu.toolbox.notification

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.agent.AgentInsightEngine
import com.xjtu.toolbox.agent.AndroidAgentHost
import com.xjtu.toolbox.agent.InsightOutcome
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.auth.AppLoginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 推送前给新通知打标签（#343）。默认关，要用户自己开，因为每批新通知都会调一次模型。
 *
 * 通知比标签要紧：没开、没配模型、超时、出错，一律返回空，照常推送，只是不带标签。
 */
internal object NoticeAiTagger {
    private const val TAG = "NoticeAiTagger"
    private const val TIMEOUT_MS = 20_000L

    private val SPEC = InsightSpec(
        id = "notice_tags",
        instruction = NoticeAiTags.INSTRUCTION,
        ttlMs = 0,
    )

    suspend fun tag(context: Context, notices: List<Notification>): Map<String, String> {
        if (notices.isEmpty() || !NoticeWatchStore.aiTagsEnabled(context)) return emptyMap()
        return try {
            AccountContext.restoreIfNeeded()
            val engine = AgentInsightEngine(AndroidAgentHost(context, AppLoginState()))
            val outcome = withTimeoutOrNull(TIMEOUT_MS) {
                engine.run(SPEC, NoticeAiTags.input(notices), force = true)
            }
            (outcome as? InsightOutcome.Ok)?.let { NoticeAiTags.parse(it.json, notices) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "打标签失败，照常推送：${e.message}")
            emptyMap()
        }
    }
}
