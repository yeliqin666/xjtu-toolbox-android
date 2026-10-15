package com.xjtu.toolbox.feedback

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

    override suspend fun publicQa(): List<FeedbackPublicQa> = FeedbackApi.publicQa()
}
