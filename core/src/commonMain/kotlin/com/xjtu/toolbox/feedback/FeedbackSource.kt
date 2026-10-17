package com.xjtu.toolbox.feedback

/** 反馈的取数端口 —— `:core` 的反馈屏只认它，不认任何具体后端（现在是飞书多维表格，见 `:data` 的 `FeedbackApi` / `AppFeedbackSource`）。 */
interface FeedbackSource {

    /** 后端凭据是否配全。 */
    val isConfigured: Boolean

    /** 这一端能不能提交新反馈。 */
    val canSubmit: Boolean get() = true

    /** 提交一条反馈。 */
    suspend fun submit(
        ticket: String,
        category: String,
        content: String,
        contact: String,
        anonId: String,
        version: String? = null,
    )

    /** 拉取这些工单号对应的回复（一次最多认 20 个，见实现里的 `take(20)`）。 */
    suspend fun replies(tickets: List<String>): Map<String, FeedbackReply>

    /** 拉「大家在问」那一栏（没配凭据或字段还没建时返回空表，绝不退回整表拉取）。 */
    suspend fun publicQa(): List<FeedbackPublicQa>
}
