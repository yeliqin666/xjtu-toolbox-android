package com.xjtu.toolbox.agent

import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.notification.Notification

/**
 * 「转给小岱」的提示词：[text] 进聊天气泡，[snapshot] 只发给模型。
 * 快照里是通知标题、网页正文这类外部内容，一律标成数据（[ToolReply.EXTERNAL_DATA]）。
 */
data class AgentForward(val text: String, val snapshot: String) {
    companion object {
        /** 网页正文最多带这么多字，够看懂一页，又不至于把上下文撑爆。 */
        const val MAX_PAGE_CHARS = 6000
        private const val MAX_LMS_CHARS = 1500
        private const val NOTICE_ASK = "说清要点、跟用户有没有关系、要不要做什么。要看全文就读链接，读不到就如实说只看到了标题。"
        private val HTML_TAG = Regex("<[^>]+>")
        private val BLANKS = Regex("""[ \t ]+""")
        private val BLANK_LINES = Regex("""\n\s*\n+""")

        private fun data(body: String) = "${body.trim()}\n${ToolReply.EXTERNAL_DATA}"

        private fun compact(text: String) = text.replace(BLANKS, " ").replace(BLANK_LINES, "\n").trim()

        fun notice(n: Notification) = AgentForward(
            text = "帮我看看这条通知：${n.title}",
            snapshot = data(buildString {
                appendLine("通知标题：${n.title}")
                appendLine("来源：${n.source.displayName}")
                appendLine("日期：${n.date}")
                if (n.tags.isNotEmpty()) appendLine("分类：${n.tags.joinToString("、")}")
                appendLine("链接：${n.link}")
                append(NOTICE_ASK)
            }),
        )

        /** 通知栏一次推来好几条时用。 */
        fun notices(list: List<Notification>): AgentForward = list.singleOrNull()?.let(::notice) ?: AgentForward(
            text = "帮我看看这 ${list.size} 条新通知，哪些跟我有关？",
            snapshot = data(list.joinToString("\n") { "${it.source.displayName} ${it.date} ${it.title} ${it.link}" }),
        )

        /** 消息收纳里的通知。链接在 id 里（`notice:<url>`，见 `OwnInbox.notice`）。 */
        fun inboxNotice(item: InboxItem) = AgentForward(
            text = "帮我看看这条通知：${item.title}",
            snapshot = data(buildString {
                appendLine("通知标题：${item.title}")
                appendLine("来源：${item.source}")
                item.id.removePrefix("notice:").takeIf { it.startsWith("http") }?.let { appendLine("链接：$it") }
                append(NOTICE_ASK)
            }),
        )

        fun lmsActivity(course: LmsCourseSummary, activity: LmsActivity, typeName: String) = AgentForward(
            text = "帮我看看${course.name}的${typeName}：${activity.title}",
            snapshot = data(buildString {
                appendLine("课程：${course.name}（id ${course.id}）")
                appendLine("活动：${activity.title}（id ${activity.id}，$typeName）")
                activity.endTime?.let { appendLine("截止：$it") }
                activity.description?.replace(HTML_TAG, " ")?.let(::compact)?.takeIf { it.isNotEmpty() }
                    ?.let { appendLine("说明：${it.take(MAX_LMS_CHARS)}") }
                append("说清要做什么、什么时候截止。要看附件和完整内容用 get_lms_activity。")
            }),
        )

        /**
         * 内置浏览器当前页。正文是页面上已经显示的文字（WebView 里读的），所以要登录的页面、
         * WebVPN 页面也能看，模型不必自己去开链接。
         */
        fun webPage(title: String, url: String, pageText: String): AgentForward {
            val body = compact(pageText)
            return AgentForward(
                text = "帮我看看这个网页：${title.ifBlank { url }}",
                snapshot = data(buildString {
                    appendLine("网页标题：$title")
                    appendLine("链接：$url")
                    if (body.isEmpty()) {
                        append("没读到页面文字。试着读链接；它可能要登录，读不到就如实说。")
                    } else {
                        appendLine("页面文字${if (body.length > MAX_PAGE_CHARS) "（太长，只带了开头）" else ""}：")
                        append(body.take(MAX_PAGE_CHARS))
                    }
                }),
            )
        }
    }
}
