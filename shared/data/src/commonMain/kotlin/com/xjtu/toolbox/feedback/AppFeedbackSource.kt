package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** `:core` 的 [FeedbackSource] 在 `:data` 里的实现 —— 包住 [FeedbackApi]（飞书开放平台多维表格）。 */
object AppFeedbackSource : FeedbackSource {

    override val isConfigured: Boolean
        get() = FeedbackApi.isConfigured

    override suspend fun submit(
        ticket: String,
        category: String,
        content: String,
        contact: String,
        anonId: String,
        version: String?,
    ) = FeedbackApi.submit(ticket, category, content, contact, anonId, version)

    override suspend fun replies(tickets: List<String>): Map<String, FeedbackReply> =
        FeedbackApi.replies(tickets)

    /**
     * 读 Action 每 4 天从飞书生成一次的 feedback-qa.json（announce 分支，Gitee / GitHub），不让每台手机都去扫回复表。
     * 两边都拿不到（比如文件还没生成）才直接查飞书。
     */
    override suspend fun publicQa(): List<FeedbackPublicQa> {
        val ua = FeedbackApi.config?.versionLabel.orEmpty()
        return BulletinApi.fetchFile(QA_FILE, ua)?.let(::parseQa) ?: FeedbackApi.publicQa()
    }

    private fun parseQa(json: String): List<FeedbackPublicQa>? = runCatching {
        AppJson.parseToJsonElement(json).jsonObject["items"]!!.jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (s("q").isBlank() || s("a").isBlank()) null else FeedbackPublicQa(s("q"), s("a"), s("c"), s("b"))
        }
    }.getOrNull()

    private const val QA_FILE = "feedback-qa.json"

    override fun privateTerms(): List<Pair<String, String>> =
        runCatching { AccountStore().list() }.getOrDefault(emptyList()).flatMap { a ->
            listOfNotNull("账号" to a.accountId, a.nickname?.let { "姓名" to it })
        }
}
