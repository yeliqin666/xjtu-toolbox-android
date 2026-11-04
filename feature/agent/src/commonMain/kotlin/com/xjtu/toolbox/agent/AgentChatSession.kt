package com.xjtu.toolbox.agent

import androidx.compose.runtime.snapshots.SnapshotStateList

/** 「一屏屁岱对话」这件事的成员面。 */
interface AgentChatSession {
    val externalToolRequest: ExternalToolRequest? get() = null
    fun answerExternalTool(allow: Boolean) {}

    /** 屏上的消息。`delta` / `reasoning` / `tool_call` / `tool_result` 都往这里写。 */
    val messages: SnapshotStateList<ChatMessage>

    /** 有没有一轮正在生成（底栏的思考动画与它同源）。 */
    val isLoading: Boolean

    /** 这一轮的失败文案（`null` = 没有）。与 `AgentViewModel` 同名同型。 */
    var errorMessage: String?

    /** 要填进输入框的话（外部深链带来的，只填不发），输入框取走后置回 `null`。 */
    var draft: String?

    /** 上下文是否已达上限（800k token）。 */
    val contextExhausted: Boolean

    /** [contextExhausted] 刚刚由 `false` 变 `true` 的一次性标记。 */
    val contextExhaustedJustTriggered: Boolean

    /** 取走 [contextExhaustedJustTriggered]（取走即清零），返回取走的值。 */
    fun consumeContextExhaustedTrigger(): Boolean

    /** 会话表（新的在前，按 `updatedAt` 降序）。 */
    val sessions: SnapshotStateList<AgentSession>

    /** 当前会话 id（`null` = 还没建）。 */
    val currentSessionId: String?

    /** 接上落盘并拉齐会话表与当前会话（屏进来时调一次）。 */
    fun bind(store: AgentSessionPersistence)

    /** 新开一个会话。 */
    fun newSession()

    /** 切到某个会话（实现方负责「切走就取消本轮、防串台」）。 */
    fun switchSession(id: String)

    /** 从落盘重读并切到某个会话（后台往里追加过消息时用）。正在生成时不打断，返回 false。 */
    fun reloadSession(id: String): Boolean {
        if (isLoading) return false
        switchSession(id)
        return currentSessionId == id
    }

    /** 删掉某个会话（幂等）。 */
    fun deleteSession(id: String)

    /** 给某个会话改名（标题随之锁定，不再被自动总结覆盖）。 */
    fun renameSession(id: String, title: String)

    /** 停止正在生成的那一轮。 */
    fun stop()

    /** 改掉最后一条用户消息并重发。 */
    fun replaceLastUserAndSend(newText: String, config: AgentConfig)

    /** 发一条用户消息。 */
    fun sendMessage(
        userText: String,
        config: AgentConfig,
        llmAnnex: String? = null,
        images: List<String> = emptyList(),
    )

    companion object {

        /** 上下文耗尽时那句文案。 */
        const val CONTEXT_EXHAUSTED_MESSAGE = AGENT_CONTEXT_EXHAUSTED_MESSAGE
    }
}

data class ExternalToolRequest(val endpoint: String, val tool: String, val arguments: String)
