package com.xjtu.toolbox.agent

import kotlinx.serialization.Serializable

/**
 * 一个会话的元数据（用于抽屉列表）。
 * 会话是带 id/title/时间戳的持久实体，标题由首条消息自动生成。
 */
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

/**
 * 多会话落盘 —— 屁岱这一屏与 ViewModel 认的**那一格缝**。
 *
 * ## 为什么是缝
 *
 * 会话要落成一堆 JSON 文件（`index.json` + 每会话一个 `convo_{id}.json`），而**文件 API 不是
 * 多平台的**（`:core` 的 commonMain 里没有 `java.io.File`，也没有 okio/kotlinx-io）。
 * 这份存储因此在 `:data`（`AgentSessionStore`，Android 与桌面同一份），
 * 这里只留它对外的那几个动作 —— 与 `ScheduleHost.cache`（交一个 `ScheduleCacheStore`）
 * 是同一条路子：**屏与 ViewModel 不碰文件，只碰这个接口**。
 *
 * ## 语义
 *
 * 方法的语义与 `:data` 的 `AgentSessionStore` **逐个相同**（那边 `override` 的就是这几个）：
 * [create] 会登记进 index 并落一份空会话、[save] 会顺带刷新标题与时间、[isLocked] 决定
 * 「首轮结束后要不要自动改标题」。目录名、文件名、JSON 字段一个字符未动。
 */
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

    companion object {
        /**
         * 什么都不存：`:core` 自己的测试与「不落盘」的临时端用它。
         * 列表永远是空的 ⇒ 屏上第一次进来只有一个新会话（纯内存那份）。
         */
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
