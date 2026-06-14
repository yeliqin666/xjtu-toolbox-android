package com.xjtu.toolbox.agent

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * 一个会话的元数据（用于抽屉列表）。
 * 参考 opencode：session 是带 id/title/时间戳的持久实体，标题由首条消息自动生成。
 */
data class AgentSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** true=标题已由用户改名或 AI 总结锁定，不再被首条消息自动覆盖。 */
    val locked: Boolean = false
)

/** 持久化用的精简消息——不含 widgets：富控件是每轮即时派生的展示物，不入库。 */
data class StoredMessage(
    val role: String,
    val content: String,
    val nav: List<List<String>> = emptyList()   // [[label, route], ...]
)

/** 一个会话的完整内容：UI 消息 + 供续聊的 LLM 历史（JsonArray 的字符串形式）。 */
data class StoredConversation(
    val messages: List<StoredMessage> = emptyList(),
    val llmHistory: String = "[]"
)

/**
 * Agent 多会话持久化存储。
 *
 * 存放于 `filesDir/agent_sessions/`（非 cache，避免被系统清理）：
 *   - `index.json`        —— 全部会话元数据（抽屉列表用，快速加载）
 *   - `convo_{id}.json`   —— 单个会话的消息与 LLM 历史
 *
 * 单机本地存储，无需 SQLite；每会话一个 JSON 文件的方案对标 opencode 早期实现，足够规范。
 */
class AgentSessionStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "agent_sessions").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val gson = Gson()

    /** 全部会话，按最近更新降序。 */
    fun list(): List<AgentSession> = readIndex().sortedByDescending { it.updatedAt }

    /** 新建空会话并登记。 */
    fun create(title: String = DEFAULT_TITLE): AgentSession {
        val now = System.currentTimeMillis()
        val session = AgentSession(UUID.randomUUID().toString(), title, now, now)
        writeIndex(readIndex() + session)
        writeConvo(session.id, StoredConversation())
        return session
    }

    /** @param lock 是否锁定标题（用户改名或 AI 总结时为 true）。 */
    fun rename(id: String, title: String, lock: Boolean = true) {
        val now = System.currentTimeMillis()
        writeIndex(readIndex().map {
            if (it.id == id) it.copy(title = title, updatedAt = now, locked = lock) else it
        })
    }

    fun delete(id: String) {
        writeIndex(readIndex().filterNot { it.id == id })
        convoFile(id).delete()
    }

    fun load(id: String): StoredConversation? {
        val f = convoFile(id)
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), StoredConversation::class.java) }.getOrNull()
    }

    /** 保存会话内容，并把 title/updatedAt 同步进 index（不存在则补登记）。保留已有 locked 标志。 */
    fun save(id: String, convo: StoredConversation, title: String) {
        writeConvo(id, convo)
        val now = System.currentTimeMillis()
        val idx = readIndex()
        val newIdx = if (idx.any { it.id == id })
            idx.map { if (it.id == id) it.copy(title = title, updatedAt = now) else it }
        else
            idx + AgentSession(id, title, now, now)
        writeIndex(newIdx)
    }

    /** 当前标题是否已锁定（用于 ViewModel 决定是否自动改标题）。 */
    fun isLocked(id: String): Boolean = readIndex().firstOrNull { it.id == id }?.locked == true

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun convoFile(id: String) = File(dir, "convo_$id.json")

    private fun readIndex(): List<AgentSession> =
        if (!indexFile.exists()) emptyList()
        else runCatching {
            gson.fromJson(indexFile.readText(), Array<AgentSession>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())

    private fun writeIndex(list: List<AgentSession>) {
        runCatching { indexFile.writeText(gson.toJson(list)) }
    }

    private fun writeConvo(id: String, convo: StoredConversation) {
        runCatching { convoFile(id).writeText(gson.toJson(convo)) }
    }

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}
