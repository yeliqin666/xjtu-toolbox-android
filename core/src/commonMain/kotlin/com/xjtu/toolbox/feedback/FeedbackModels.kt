package com.xjtu.toolbox.feedback

/**
 * 开发者对一条工单的回复。
 *
 * 搬迁前它是 `:app` 的 `FeedbackApi.Reply`（嵌在取数实现里的嵌套类型）；搬进共享内核时：
 *  - 取数实现（`FeedbackApi`）进 `:data`，而屏在 `:core` —— `:core` 看不见 `:data`，
 *    所以模型与取数端口一起留在 `:core`，实现方反过来用它们（与 `lms` 域的
 *    `LmsCourse` / `LmsSource` 同一个形状）；
 *  - 于是不再需要「嵌在 `FeedbackApi` 里」这一层：`Reply` → [FeedbackReply]、
 *    `PublicQa` → [FeedbackPublicQa]。字段口径一字未改。
 */
data class FeedbackReply(val text: String, val time: String)

/** 所有人都能看的问答（开发者勾选「公开」并脱敏后填的那批，见 `FeedbackApi.publicQa`）。 */
data class FeedbackPublicQa(
    val question: String,
    val answer: String,
    val category: String = "",
)
