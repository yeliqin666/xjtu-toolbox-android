package com.xjtu.toolbox.feedback

/**
 * `:core` 的 [FeedbackSource] 在 `:data` 里的实现 —— 包住 [FeedbackApi]（飞书开放平台
 * 多维表格）。Android 与桌面**用同一份**：反馈没有校园站点会话、也不收 `SiteSession`
 * （见 [FeedbackSource] 的 KDoc），所以这里连一行宿主能力都不需要 —— 两端只差"凭据从哪来"
 * （`AppFeedback.install()` / 桌面构建期注入的那份常量）。
 *
 * 为什么不是让屏直接用 [FeedbackApi]：屏在 `:core`，看不见 `:data`
 * （`FeedbackApi` 收发 okhttp、还用 `:data` 的 `HttpClients`）。
 */
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
