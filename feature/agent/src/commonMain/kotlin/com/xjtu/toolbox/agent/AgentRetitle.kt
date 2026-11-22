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
        "改本对话的标题。用户明确要求改标题时调用，requested_by_user 传 true；" +
            "或者最近连续几轮的话题已和当前标题明显不同（换了一件事，而不是同一话题的追问或细化）时自己调用，传 false，" +
            "每次换话题最多调一次，话题没变不要调。自己改的调用后正文不必提及。",
        agentParams(
            "title" to agentStrProp("新标题：不超过 8 个字的名词短语，写主题，不转述提问，不加标点；用户指定了就用用户的原话。"),
            "requested_by_user" to agentBoolProp("用户这条消息里明确要求改标题时为 true。"),
            required = listOf("title"),
        ))

    /** 注进用户消息实时标签里的那一段。 */
    fun tag(title: String): String = " · 对话标题：$title"
}
