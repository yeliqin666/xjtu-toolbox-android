package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore

/**
 * 同一个功能页再叫小岱，接着这页上次那段对话：那段对话 1 小时内还动过才接（模型端的提示词缓存还热着，
 * 也还记得刚才聊的），否则开新的。按账号分开记。
 */
internal object AgentPageSessions {
    private val store by lazy { keyValueStore("agent_page_sessions") }
    private const val FRESH_MS = 60 * 60 * 1000L

    private fun key(page: String) = page + AccountContext.safeSuffix()

    /** [page] 上次那段对话的 id；没有、已删、或超过 1 小时没动过返回 null。 */
    fun recent(page: String, sessions: List<AgentSession>, now: Long): String? {
        val id = store.getString(key(page)) ?: return null
        return sessions.firstOrNull { it.id == id && now - it.updatedAt < FRESH_MS }?.id
    }

    fun remember(page: String, sessionId: String?) {
        if (sessionId != null) store.putString(key(page), sessionId)
    }
}
