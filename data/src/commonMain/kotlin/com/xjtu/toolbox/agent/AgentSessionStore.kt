package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.dataFilesDir
import com.xjtu.toolbox.util.AppJson
import java.io.File
import java.util.UUID

/**
 * Agent 多会话持久化存储。
 *
 * 存放于 `filesDir/agent_sessions<账号后缀>/`（非 cache，避免被系统清理）：
 *   - `index.json`        —— 全部会话元数据（抽屉列表用，快速加载）
 *   - `convo_{id}.json`   —— 单个会话的消息与 LLM 历史
 *
 * 单机本地存储，无需 SQLite；每会话一个 JSON 文件的方案对标 opencode 早期实现，足够规范。
 *
 * 根目录走 [dataFilesDir] 缝（可由宿主另传）；目录名、文件名、JSON 字段不能改，否则老会话读不回来。
 */
class AgentSessionStore(
    /** 数据根目录（**每次调用重新解析**，默认取本端的 `dataFilesDir()`）。 */
    private val rootDirOf: () -> File = { dataFilesDir() },
) : AgentSessionPersistence {
    /** 当前账号命名空间下的会话目录，每次调用动态解析以响应账号切换。 */
    private val dir: File
        get() = File(rootDirOf(), "agent_sessions" + AccountContext.safeSuffix()).apply { mkdirs() }
    private val indexFile: File
        get() = File(dir, "index.json")

    /** 全部会话，按最近更新降序。 */
    override fun list(): List<AgentSession> = readIndex().sortedByDescending { it.updatedAt }

    /** 新建空会话并登记。 */
    override fun create(title: String): AgentSession {
        val now = System.currentTimeMillis()
        val session = AgentSession(UUID.randomUUID().toString(), sanitizeAgentTitle(title, DEFAULT_TITLE), now, now)
        writeIndex(readIndex() + session)
        writeConvo(session.id, StoredConversation())
        return session
    }

    /** @param lock 是否锁定标题（用户改名或 AI 总结时为 true）。 */
    override fun rename(id: String, title: String, lock: Boolean) {
        val now = System.currentTimeMillis()
        writeIndex(readIndex().map {
            if (it.id == id) it.copy(title = sanitizeAgentTitle(title, DEFAULT_TITLE), updatedAt = now, locked = lock) else it
        })
    }

    override fun delete(id: String) {
        writeIndex(readIndex().filterNot { it.id == id })
        convoFile(id).delete()
    }

    override fun load(id: String): StoredConversation? {
        val f = convoFile(id)
        if (!f.exists()) return null
        return runCatching { AppJson.decodeFromString<StoredConversation>(f.readText()) }.getOrNull()
    }

    /** 保存会话内容，并把 title/updatedAt 同步进 index（不存在则补登记）。保留已有 locked 标志。 */
    override fun save(id: String, convo: StoredConversation, title: String) {
        writeConvo(id, convo)
        val now = System.currentTimeMillis()
        val idx = readIndex()
        val newIdx = if (idx.any { it.id == id })
            idx.map { if (it.id == id) it.copy(title = sanitizeAgentTitle(title, DEFAULT_TITLE), updatedAt = now) else it }
        else
            idx + AgentSession(id, sanitizeAgentTitle(title, DEFAULT_TITLE), now, now)
        writeIndex(newIdx)
    }

    /** 当前标题是否已锁定（用于 ViewModel 决定是否自动改标题）。 */
    override fun isLocked(id: String): Boolean = readIndex().firstOrNull { it.id == id }?.locked == true

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun convoFile(id: String) = File(dir, "convo_$id.json")

    private fun readIndex(): List<AgentSession> =
        if (!indexFile.exists()) emptyList()
        else runCatching { AppJson.decodeFromString<List<AgentSession>>(indexFile.readText()) }.getOrDefault(emptyList())

    private fun writeIndex(list: List<AgentSession>) {
        runCatching { indexFile.writeText(AppJson.encodeToString(list)) }
    }

    private fun writeConvo(id: String, convo: StoredConversation) {
        runCatching { convoFile(id).writeText(AppJson.encodeToString(convo)) }
    }

    companion object {
        const val DEFAULT_TITLE = AGENT_SESSION_DEFAULT_TITLE
    }
}
