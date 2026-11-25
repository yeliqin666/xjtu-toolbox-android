package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `propose_post`：把用户想反馈、求助或闲聊的内容整理成一张社区帖子预览卡。
 *
 * 模型调用时 [AgentRunner] 只出卡、不执行；用户在卡上点「发布」才经宿主工具层按同名工具真正发出，
 * 所以模型自己永远发不了帖。哪一端能发（`:app` 有社区登录）就由那一端在工具表里列出这份定义。
 */
object AgentPostCard {
    const val TOOL = "propose_post"
    const val DEFAULT_CATEGORY = "求助与反馈"

    /** 普通用户能发的板块（公告分类 Announcements 只有管理员能发，不列）。 */
    val CATEGORIES = listOf(DEFAULT_CATEGORY, "功能建议", "校园闲聊")

    private const val MAX_TITLE = 60
    private const val MAX_BODY = 4000

    fun definition(): JsonObject = agentTool(TOOL,
        "把用户要发到 App 社区（GitHub Discussions）的反馈、建议或求助整理成帖子预览卡，用户核对后点「发布」才用他的社区账号发出。" +
            "返回后卡片已展示，正文不必复述，提醒他检查后点发布即可。",
        agentParams(
            "title" to agentStrProp("标题，一句话说清问题或建议，不超过 30 字。"),
            "body" to agentStrProp("正文，Markdown。Bug 写清现象、复现步骤、期望；建议写清场景与想要的效果。只写用户说过或工具查到的事实。"),
            "category" to agentEnumProp("板块。缺省：$DEFAULT_CATEGORY。", *CATEGORIES.toTypedArray()),
            "caption" to agentStrProp("卡片上方一句说明，可空。"),
            required = listOf("title", "body"),
        ))

    /** 参数不合规时抛出，错误原样回给模型重试。 */
    fun parse(args: String): PostWidget {
        val root = AppJson.parseToJsonElement(args.ifBlank { "{}" }) as? JsonObject ?: error("参数须为对象")
        val title = root.argStr("title")?.trim()?.takeIf { it.isNotEmpty() } ?: error("title 不能为空")
        val body = root.argStr("body")?.trim()?.takeIf { it.isNotEmpty() } ?: error("body 不能为空")
        val category = root.argStr("category")?.trim()?.takeIf { it in CATEGORIES } ?: DEFAULT_CATEGORY
        return PostWidget(title.take(MAX_TITLE), body.take(MAX_BODY), category, root.argStr("caption")?.trim().orEmpty().take(80))
    }

    fun receipt(widget: PostWidget): String = ToolReply.CARD_SHOWN +
        "\ncard_display: community_post; category=${widget.category}; user_taps_to_publish; not_posted_yet"

    /** 卡片上点「发布」时交给工具层的参数（用户可能改过标题、正文、板块）。 */
    fun publishArgs(title: String, body: String, category: String): String = buildJsonObject {
        put("title", title)
        put("body", body)
        put("category", category)
    }.toString()
}
