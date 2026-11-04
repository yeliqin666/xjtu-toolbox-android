package com.xjtu.toolbox.agent

internal fun isToolRole(role: String) = role == "tool_event"

internal sealed class AgentRow {
    abstract val key: String
    data class User(val msg: ChatMessage, override val key: String) : AgentRow()
    data class Assistant(val msg: ChatMessage, override val key: String, val tools: List<ChatMessage> = emptyList()) : AgentRow()
}

/** 只有正文才分段；思考与工具记录在同一段内更新，等待分片时也保留原来的行。 */
internal fun groupAgentRows(messages: List<ChatMessage>, showReasoning: Boolean, loading: Boolean = false): List<AgentRow> {
    val out = mutableListOf<AgentRow>()
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (message.role == "user") {
            out += AgentRow.User(message, "u-${message.timestamp}-$i")
            i++
            if (loading && i == messages.size) {
                out += AgentRow.Assistant(ChatMessage("assistant", "", timestamp = message.timestamp), "a-${message.timestamp}-$i-0")
            }
            continue
        }
        val start = i
        while (i < messages.size && messages[i].role != "user") i++
        val block = messages.subList(start, i)
        val turnStamp = messages.getOrNull(start - 1)?.timestamp ?: message.timestamp
        val rows = mutableListOf<AgentRow.Assistant>()
        val reasoning = mutableListOf<String>()
        val tools = mutableListOf<ChatMessage>()
        var assistant: ChatMessage? = null
        var segmentStarted = false
        fun emit(content: String) {
            val source = assistant ?: ChatMessage("assistant", "", timestamp = block.last().timestamp)
            rows += AgentRow.Assistant(
                source.copy(content = content, reasoningContent = if (showReasoning) reasoning.joinToString("\n\n") else "",
                    widgets = emptyList(), navSuggestions = emptyList()),
                "a-$turnStamp-$start-${rows.size}", tools.toList(),
            )
            reasoning.clear()
            tools.clear()
            assistant = null
            segmentStarted = false
        }
        for (entry in block) {
            segmentStarted = true
            if (isToolRole(entry.role)) tools += entry
            else {
                assistant = entry
                if (entry.reasoningContent.isNotBlank()) reasoning += entry.reasoningContent.trim()
                if (entry.content.isNotBlank()) emit(entry.content)
            }
        }
        val widgets = block.flatMap { it.widgets }
        val nav = block.flatMap { it.navSuggestions }.distinctBy { it.second }
        if (segmentStarted && (showReasoning && reasoning.isNotEmpty() || tools.isNotEmpty() || loading && i == messages.size)) emit("")
        if (rows.isEmpty() && (widgets.isNotEmpty() || nav.isNotEmpty())) emit("")
        if (rows.isNotEmpty()) {
            val last = rows.last()
            rows[rows.lastIndex] = last.copy(msg = last.msg.copy(widgets = widgets, navSuggestions = nav))
            out += rows
        }
    }
    return out
}
