package com.xjtu.toolbox.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.AppLoginState
import com.xjtu.toolbox.util.DataCache
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 聊天消息 UI 模型。
 *
 * @param navSuggestions 本轮涉及的功能页跳转建议，List<Pair<displayLabel, routeKey>>。
 *   routeKey 与 Routes 常量对应（schedule / empty_room / attendance / …）。
 */
data class ChatMessage(
    val role: String,          // "user" | "assistant" | "tool_event"
    val content: String,
    val isToolCall: Boolean = false,
    val navSuggestions: List<Pair<String, String>> = emptyList(),
    val widgets: List<AgentWidget> = emptyList()
)

class AgentViewModel : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    // ── 多会话状态 ──────────────────────────────────────────────────────
    val sessions = mutableStateListOf<AgentSession>()
    var currentSessionId by mutableStateOf<String?>(null)
        private set
    private var store: AgentSessionStore? = null

    // LLM 多轮历史（含 system prompt + 所有轮次），与 messages（UI 专用）独立维护。
    // 保持历史稳定以利用 provider 端 prefix cache：system prompt 只在首轮写入一次。
    private var llmHistory = JsonArray()
    private var systemPromptAdded = false

    // AgentToolRegistry 保持在 ViewModel 级别，使 loginFailedAt 冷却状态跨消息保留
    private var tools: AgentToolRegistry? = null

    // ── 会话管理 ────────────────────────────────────────────────────────

    /** 绑定持久化存储并加载会话列表（仅一次）。空则自动建首个会话。 */
    fun bind(store: AgentSessionStore) {
        if (this.store != null) return
        this.store = store
        sessions.addAll(store.list())
        val first = sessions.firstOrNull()
        if (first != null) switchSession(first.id) else newSession()
    }

    fun newSession() {
        val store = store ?: return
        if (isLoading) return
        val s = store.create()
        currentSessionId = s.id
        messages.clear(); llmHistory = JsonArray(); systemPromptAdded = false; tools = null; errorMessage = null
        refreshSessions()
    }

    fun switchSession(id: String) {
        val store = store ?: return
        if (isLoading || id == currentSessionId && messages.isNotEmpty()) {
            currentSessionId = id; return
        }
        val convo = store.load(id) ?: return
        currentSessionId = id
        messages.clear()
        convo.messages.forEach { m ->
            messages.add(ChatMessage(
                role = m.role,
                content = m.content,
                navSuggestions = m.nav.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }
            ))
        }
        llmHistory = runCatching { JsonParser.parseString(convo.llmHistory).asJsonArray }.getOrDefault(JsonArray())
        systemPromptAdded = llmHistory.any {
            runCatching { it.asJsonObject.get("role")?.asString == "system" }.getOrDefault(false)
        }
        tools = null; errorMessage = null
    }

    fun renameSession(id: String, title: String) {
        store?.rename(id, title.trim().ifBlank { AgentSessionStore.DEFAULT_TITLE })
        refreshSessions()
    }

    fun deleteSession(id: String) {
        val store = store ?: return
        store.delete(id)
        refreshSessions()
        if (currentSessionId == id) {
            val next = sessions.firstOrNull()
            if (next != null) switchSession(next.id) else newSession()
        }
    }

    private fun refreshSessions() {
        val store = store ?: return
        sessions.clear(); sessions.addAll(store.list())
    }

    /** 把当前会话写盘（过滤掉临时的 tool_event 进度气泡）。 */
    private fun persist() {
        val store = store ?: return
        val id = currentSessionId ?: return
        val stored = messages
            .filter { it.role != "tool_event" }
            .map { StoredMessage(it.role, it.content, it.navSuggestions.map { p -> listOf(p.first, p.second) }) }
        store.save(id, StoredConversation(stored, llmHistory.toString()), deriveTitle())
        refreshSessions()
    }

    /** 标题取首条用户消息（截断 18 字），无则默认。 */
    private fun deriveTitle(): String {
        val firstUser = messages.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
        return when {
            firstUser.isBlank() -> AgentSessionStore.DEFAULT_TITLE
            firstUser.length <= 18 -> firstUser
            else -> firstUser.take(18) + "…"
        }
    }

    fun sendMessage(
        userText: String,
        config: AgentConfig,
        loginState: AppLoginState,
        context: Context
    ) {
        if (userText.isBlank() || isLoading) return
        if (currentSessionId == null) newSession()
        errorMessage = null
        messages.add(ChatMessage("user", userText))
        isLoading = true

        viewModelScope.launch {
            try {
                // 首次调用时初始化，此后复用（loginFailedAt 冷却状态得以保留）
                val registry = tools ?: AgentToolRegistry(loginState, DataCache(context), context)
                    .also { tools = it }
                registry.drainWidgets()   // 丢弃上一轮残留，确保本轮控件干净
                val runner = AgentRunner(registry)

                // system prompt 只在会话首轮写入，后续请求复用同一历史前缀，
                // 让 DeepSeek/OpenAI 的 prefix cache 命中。
                if (!systemPromptAdded) {
                    llmHistory.add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", AgentPrompt.build(LocalDate.now(), registry.userIdentity()))
                    })
                    systemPromptAdded = true
                }

                llmHistory.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userText)
                })

                var toolBubbleIndex = -1
                val calledTools = mutableListOf<String>()

                val reply = runner.run(
                    messages = llmHistory,
                    config = config,
                    onToolCall = { name ->
                        calledTools.add(name)
                        val label = when (name) {
                            "get_current_time"      -> "获取当前时间…"
                            "get_schedule"          -> "查询课表…"
                            "get_exam_schedule"     -> "查询考试安排…"
                            "get_empty_rooms"       -> "查询空教室…"
                            "get_attendance"        -> "查询考勤记录…"
                            "get_grades"            -> "查询成绩…"
                            "get_card_balance"      -> "查询校园卡余额…"
                            "get_card_transactions" -> "查询校园卡流水…"
                            "get_notifications"     -> "查询通知公告…"
                            "web_search"            -> "联网搜索…"
                            "web_fetch"             -> "阅读网页…"
                            "get_library_booking"   -> "查询图书馆预约…"
                            "get_library_seats"     -> "查询图书馆座位…"
                            else                    -> "调用工具 $name…"
                        }
                        if (toolBubbleIndex < 0) {
                            messages.add(ChatMessage("tool_event", label, isToolCall = true))
                            toolBubbleIndex = messages.lastIndex
                        } else {
                            val prev = messages[toolBubbleIndex]
                            messages[toolBubbleIndex] = prev.copy(content = prev.content + " → $label")
                        }
                    }
                )

                if (toolBubbleIndex >= 0) {
                    messages[toolBubbleIndex] = messages[toolBubbleIndex].copy(isToolCall = false)
                }

                // 考勤路由根据实际登录类型动态选择，避免研究生跳转到本科考勤页
                val attendanceRoute = if (loginState.postgraduateAttendanceLogin != null)
                    "postgraduate_attendance" else "attendance"

                val navSuggestions = calledTools.mapNotNull { toolName ->
                    when (toolName) {
                        "get_schedule", "get_exam_schedule" -> "查看课表"   to "schedule"
                        "get_empty_rooms"                   -> "空闲教室"   to "empty_room"
                        "get_attendance"                    -> "查看考勤"   to attendanceRoute
                        "get_grades"                        -> "成绩查询"   to "jwapp_score"
                        "get_card_balance", "get_card_transactions" -> "校园卡" to "campus_card"
                        "get_notifications"                 -> "通知公告"   to "notification"
                        "get_library_booking", "get_library_seats" -> "图书馆" to "library"
                        else                                -> null
                    }
                }.distinctBy { it.second }

                val widgets = registry.drainWidgets()
                messages.add(ChatMessage("assistant", reply, navSuggestions = navSuggestions, widgets = widgets))
            } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
                messages.add(ChatMessage("assistant", "登录已失效，请返回并重新进入对应功能页面完成认证后再试。"))
            } catch (e: Exception) {
                // 兜底文案：异常 message 可能为 null（如某些 IO/解析异常），避免直接显示「出错了：null」
                val detail = e.message?.takeIf { it.isNotBlank() }
                    ?: e::class.simpleName?.let { "请求异常（$it）" }
                    ?: "未知错误"
                errorMessage = detail
                messages.add(ChatMessage("assistant", "出错了：$detail"))
            } finally {
                isLoading = false
                persist()   // 落盘当前会话（用户提问 + 回复/错误都已在 messages 中）
            }
        }
    }

    /** 清空当前会话内容，但保留会话条目本身。 */
    fun clearMessages() {
        messages.clear()
        llmHistory = JsonArray()
        systemPromptAdded = false
        errorMessage = null
        // tools 保留（loginFailedAt 冷却状态有价值），不在 clearMessages 时重置
        persist()
    }
}
