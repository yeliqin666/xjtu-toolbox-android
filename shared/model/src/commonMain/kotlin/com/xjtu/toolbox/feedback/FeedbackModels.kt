package com.xjtu.toolbox.feedback

/** 开发者对一条工单的回复。[by]：回复人（GitHub 用户名），旧回复没有这项时为空。 */
data class FeedbackReply(val text: String, val time: String, val by: String = "")

/** 所有人都能看的问答（开发者勾选「公开」的那批，见 `FeedbackApi.publicQa`）。[by] 同 [FeedbackReply.by]。 */
data class FeedbackPublicQa(
    val question: String,
    val answer: String,
    val category: String = "",
    val by: String = "",
)
