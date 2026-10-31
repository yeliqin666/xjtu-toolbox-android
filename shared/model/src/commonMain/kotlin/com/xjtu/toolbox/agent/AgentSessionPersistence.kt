package com.xjtu.toolbox.agent

import kotlinx.serialization.Serializable

/** 一个会话的元数据（用于抽屉列表）。 */
@Serializable
data class AgentSession(
    val id: String = "",
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** true=标题已由用户改名或 AI 总结锁定，不再被首条消息自动覆盖。 */
    val locked: Boolean = false
)

@Serializable
data class StoredWidget(val type: String = "", val json: String = "")

/** 持久化用的精简消息。 */
@Serializable
data class StoredMessage(
    val role: String = "assistant",
    val content: String = "",
    val nav: List<List<String>> = emptyList(),   // [[label, route], ...]
    val widgets: List<StoredWidget>? = emptyList(),
    val reasoningContent: String? = null,
    val timestamp: Long? = null,
    val toolError: String? = null,
    /** 图片附件的本地路径。老会话没有这个字段，反序列化得到 null。 */
    val images: List<String>? = null,
)

/** 一个会话的完整内容：UI 消息 + 供续聊的 LLM 历史（JsonArray 的字符串形式）。 */
@Serializable
data class StoredConversation(
    val messages: List<StoredMessage> = emptyList(),
    val llmHistory: String = "[]",
    val lastTotalTokens: Long? = null,
    val contextExhausted: Boolean = false
)

/** 新对话的默认标题（`AgentSessionStore.DEFAULT_TITLE` 与 `sanitizeAgentTitle` 的那份兜底同源）。 */
const val AGENT_SESSION_DEFAULT_TITLE = "新对话"

/** 多会话落盘 —— 屁岱这一屏与 ViewModel 认的那一格缝。 */
interface AgentSessionPersistence {

    /** 全部会话，按最近更新降序。 */
    fun list(): List<AgentSession>

    /** 新建空会话并登记。 */
    fun create(title: String = AGENT_SESSION_DEFAULT_TITLE): AgentSession

    /** @param lock 是否锁定标题（用户改名或 AI 总结时为 true）。 */
    fun rename(id: String, title: String, lock: Boolean = true)

    fun delete(id: String)

    fun load(id: String): StoredConversation?

    /** 保存会话内容，并把 title/updatedAt 同步进 index（不存在则补登记）。保留已有 locked 标志。 */
    fun save(id: String, convo: StoredConversation, title: String)

    /** 当前标题是否已锁定（用于 ViewModel 决定是否自动改标题）。 */
    fun isLocked(id: String): Boolean

    /** 读-改-写一个会话（后台追加推送与前台写盘合并用）。默认实现不加锁，落盘实现应在同一把锁里完成。 */
    fun update(id: String, title: String, lockTitle: Boolean = false, transform: (StoredConversation) -> StoredConversation) {
        save(id, transform(load(id) ?: StoredConversation()), title)
        if (lockTitle && !isLocked(id)) rename(id, title, lock = true)
    }

    companion object {
        /** 什么都不存：`:core` 自己的测试与「不落盘」的临时端用它。 */
        val None: AgentSessionPersistence = object : AgentSessionPersistence {
            override fun list(): List<AgentSession> = emptyList()
            override fun create(title: String): AgentSession =
                AgentSession(id = "", title = sanitizeAgentTitle(title, AGENT_SESSION_DEFAULT_TITLE))
            override fun rename(id: String, title: String, lock: Boolean) {}
            override fun delete(id: String) {}
            override fun load(id: String): StoredConversation? = null
            override fun save(id: String, convo: StoredConversation, title: String) {}
            override fun isLocked(id: String): Boolean = false
        }
    }
}
