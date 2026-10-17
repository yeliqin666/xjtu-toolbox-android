package com.xjtu.toolbox.feedback

/** 开发者对一条工单的回复。 */
data class FeedbackReply(val text: String, val time: String)

/** 所有人都能看的问答（开发者勾选「公开」并脱敏后填的那批，见 `FeedbackApi.publicQa`）。 */
data class FeedbackPublicQa(
    val question: String,
    val answer: String,
    val category: String = "",
)
