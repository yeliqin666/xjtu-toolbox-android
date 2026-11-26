package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonObject

/**
 * 改对话标题（#484）：用户明确要求时照改；话题明显换了时主代理也可以自己改。
 *
 * 工具一直交给模型（推送会话除外），当前标题随每条用户消息的实时标签带上（[tag]）。
 * 用户手动改过名的会话，模型自发改名会被拒（[AgentSessionPersistence.canAiRetitle]）；
 * 用户在对话里明确要改则照改，并当作用户自己的选择，之后不再自动改。
 */
internal object AgentRetitle {
    const val TOOL = "rename_conversation"

    fun definition(): JsonObject = agentTool(TOOL,
        "改本对话标题。",
        agentParams(
            "title" to agentStrProp("新标题，8 字以内。"),
            "requested_by_user" to agentBoolProp("用户要求改的为 true，自己改的为 false。"),
            required = listOf("title"),
        ))

    /** 注进用户消息实时标签里的那一段。 */
    fun tag(title: String): String = " · 对话标题：$title"
}
