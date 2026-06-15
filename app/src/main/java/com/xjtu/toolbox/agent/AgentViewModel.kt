package com.xjtu.toolbox.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.Gson
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
    val widgets: List<AgentWidget> = emptyList(),
    val reasoningContent: String = ""
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
    private var toolsDisabledCaps: Set<String>? = null
    private var toolsSearchEngine: String? = null
    private val gson = Gson()

    // 当前生成任务，供"停止生成"取消
    private var currentJob: kotlinx.coroutines.Job? = null

    /** 停止正在进行的生成。 */
    fun stop() { currentJob?.cancel() }

    /** 直接截断过长历史：保留 system + 最近若干条，并去掉开头孤儿的 tool/带tool_calls的assistant。 */
    private fun truncateHistory() {
        val max = 50
        if (llmHistory.size() <= max) return
        val items = (0 until llmHistory.size()).map { llmHistory[it].asJsonObject }
        val system = items.firstOrNull { it.get("role")?.asString == "system" }
        val rest = items.filter { it !== system }
        val keep = rest.takeLast(max - (if (system != null) 1 else 0)).toMutableList()
        while (keep.isNotEmpty()) {
            val first = keep.first()
            val role = first.get("role")?.asString
            val hasToolCalls = first.has("tool_calls") && !first.get("tool_calls").isJsonNull
            if (role == "tool" || (role == "assistant" && hasToolCalls)) keep.removeAt(0) else break
        }
        llmHistory = JsonArray().apply {
            system?.let { add(it) }
            keep.forEach { add(it) }
        }
    }

    /**
     * 修复历史完整性：丢弃"带 tool_calls 却没有(完整) tool 回应"的 assistant 残体，以及孤儿 tool 消息。
     * 用于自愈此前因取消/掉线/后台中断而损坏的会话（否则会永久报 must be followed by tool messages）。
     */
    private fun sanitizeHistory() {
        val items = (0 until llmHistory.size()).map { llmHistory[it].asJsonObject }
        val out = ArrayList<JsonObject>()
        var i = 0
        while (i < items.size) {
            val m = items[i]
            val role = m.get("role")?.asString
            val tcs = if (m.has("tool_calls") && !m.get("tool_calls").isJsonNull) m.getAsJsonArray("tool_calls") else null
            when {
                role == "assistant" && tcs != null -> {
                    val ids = tcs.mapNotNull { it.asJsonObject.get("id")?.asString }.toSet()
                    val toolMsgs = ArrayList<JsonObject>()
                    var j = i + 1
                    while (j < items.size && items[j].get("role")?.asString == "tool") { toolMsgs.add(items[j]); j++ }
                    val covered = toolMsgs.mapNotNull { it.get("tool_call_id")?.asString }.toSet()
                    if (ids.isNotEmpty() && ids.all { it in covered }) {
                        out.add(m); toolMsgs.forEach { out.add(it) }
                    } // 否则：丢弃该 assistant 及其不完整 tool 回应
                    i = j
                }
                role == "tool" -> i++   // 孤儿 tool，丢弃
                else -> { out.add(m); i++ }
            }
        }
        if (out.size != items.size) llmHistory = JsonArray().apply { out.forEach { add(it) } }
    }

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
        currentJob?.cancel(); isLoading = false   // 取消进行中的生成，避免写入新会话造成错乱
        val s = store.create()
        currentSessionId = s.id
        messages.clear(); llmHistory = JsonArray(); systemPromptAdded = false; tools = null; errorMessage = null
        refreshSessions()
    }

    fun switchSession(id: String) {
        val store = store ?: return
        if (id == currentSessionId && messages.isNotEmpty() && !isLoading) return
        currentJob?.cancel(); isLoading = false   // 切换前停掉旧生成，避免两个会话内容串台
        val convo = store.load(id) ?: return
        currentSessionId = id
        messages.clear()
        convo.messages.forEach { m ->
            messages.add(ChatMessage(
                role = m.role,
                content = m.content,
                navSuggestions = m.nav.mapNotNull { if (it.size >= 2) it[0] to it[1] else null },
                widgets = m.widgets.orEmpty().mapNotNull { storedToWidget(it, gson) },
                reasoningContent = m.reasoningContent.orEmpty()
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

    /** 把当前会话写盘。保留 tool_event 工具调用记录（下次进入不丢）；标题已锁定时不自动覆盖。 */
    private fun persist(id: String? = currentSessionId) {
        val store = store ?: return
        id ?: return
        val stored = messages.map {
            StoredMessage(
                it.role,
                it.content,
                it.navSuggestions.map { p -> listOf(p.first, p.second) },
                it.widgets.map { widget -> widget.toStored(gson) }.filter { widget -> widget.type.isNotEmpty() },
                it.reasoningContent.takeIf { reasoning -> reasoning.isNotBlank() }
            )
        }
        val title = if (store.isLocked(id))
            sessions.firstOrNull { it.id == id }?.title ?: deriveTitle()
        else deriveTitle()
        store.save(id, StoredConversation(stored, llmHistory.toString()), title)
        refreshSessions()
    }

    /** 实时时间标签，注入每条 user 消息开头。 */
    private fun nowTag(): String {
        val now = java.time.LocalDateTime.now()
        val days = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val w = days.getOrElse(now.dayOfWeek.value) { "" }
        return "[现在：${now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} $w]"
    }

    /** 首轮结束后用 AI 把对话总结成简短标题（仿 opencode）；用户已改名则不动。 */
    private fun maybeAutoTitle(config: AgentConfig) {
        val store = store ?: return
        val id = currentSessionId ?: return
        if (store.isLocked(id)) return
        if (messages.count { it.role == "user" } != 1) return
        val firstUser = messages.firstOrNull { it.role == "user" }?.content ?: return
        val firstAssistant = messages.lastOrNull { it.role == "assistant" }?.content ?: return
        viewModelScope.launch {
            val title = AgentTitleGen.generate(config, firstUser, firstAssistant)?.takeIf { it.isNotBlank() }
                ?: return@launch
            if (!store.isLocked(id)) {           // 期间用户可能已手动改名
                store.rename(id, title, lock = true)
                refreshSessions()
            }
        }
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

        val turnSid = currentSessionId   // 本轮所属会话；切走后不再写当前 messages，避免串台
        var streamIdx = -1   // 流式回答气泡的下标，首个 delta 到达时创建
        currentJob = viewModelScope.launch {
            try {
                // 首次调用时初始化，此后复用（loginFailedAt 冷却状态得以保留）
                if (toolsDisabledCaps != config.disabledCaps || toolsSearchEngine != config.searchEngine) {
                    tools = null
                    toolsDisabledCaps = config.disabledCaps
                    toolsSearchEngine = config.searchEngine
                }
                val registry = tools ?: AgentToolRegistry(
                    loginState,
                    DataCache(context),
                    context,
                    config.disabledCaps,
                    config.searchEngine
                ).also { tools = it }
                registry.drainWidgets()   // 丢弃上一轮残留，确保本轮控件干净
                val runner = AgentRunner(registry)

                // system prompt 只在会话首轮写入，后续请求复用同一历史前缀，
                // 让 DeepSeek/OpenAI 的 prefix cache 命中。
                if (!systemPromptAdded) {
                    llmHistory.add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", AgentPrompt.build(
                            today = LocalDate.now(),
                            assistantName = config.effectiveName,
                            userContext = registry.userContext(),
                            maxToolCalls = config.maxToolCalls
                        ))
                    })
                    systemPromptAdded = true
                }

                // 每条 user 消息携带实时时间，杜绝"今天/明天"按会话起始日的陈旧判断
                llmHistory.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "${nowTag()}\n$userText")
                })
                sanitizeHistory()   // 自愈：清掉上一次中断留下的 tool_calls 残体
                truncateHistory()   // 过长则直接截断旧消息

                var toolBubbleIndex = -1
                val calledTools = mutableListOf<String>()

                val reply = runner.run(
                    messages = llmHistory,
                    config = config,
                    onDelta = { frag ->
                        if (currentSessionId == turnSid) {   // 已切走则不再写，防串台
                            if (streamIdx < 0) {
                                messages.add(ChatMessage("assistant", ""))
                                streamIdx = messages.lastIndex
                            }
                            messages[streamIdx] = messages[streamIdx].copy(
                                content = messages[streamIdx].content + frag
                            )
                        }
                    },
                    onReasoningDelta = { frag ->
                        if (currentSessionId == turnSid) {
                            if (streamIdx < 0) {
                                messages.add(ChatMessage("assistant", ""))
                                streamIdx = messages.lastIndex
                            }
                            messages[streamIdx] = messages[streamIdx].copy(
                                reasoningContent = messages[streamIdx].reasoningContent + frag
                            )
                        }
                    },
                    onToolCall = { name ->
                        calledTools.add(name)
                        val label = when (name) {
                            "get_current_time"      -> "获取当前时间…"
                            "get_schedule"          -> "查询课表…"
                            "get_exam_schedule"     -> "查询考试安排…"
                            "get_school_calendar"   -> "查询校历…"
                            "search_school_courses" -> "查询全校课程…"
                            "get_empty_rooms"       -> "查询空教室…"
                            "get_attendance"        -> "查询考勤记录…"
                            "get_grades"            -> "查询成绩…"
                            "get_card_balance"      -> "查询校园卡余额…"
                            "get_card_transactions" -> "查询校园卡流水…"
                            "get_notifications"     -> "查询通知公告…"
                            "search_yellow_page"    -> "查询校园黄页…"
                            "web_search"            -> "联网搜索…"
                            "web_fetch"             -> "阅读网页…"
                            "get_library_booking"   -> "查询图书馆预约…"
                            "get_library_seats"     -> "查询图书馆座位…"
                            "get_textbooks"         -> "搜索教材…"
                            "get_coupons"           -> "查询加餐券…"
                            "get_lms_courses"       -> "查询课程…"
                            "get_lms_activities"    -> "查询课程活动…"
                            "get_lms_assignments"   -> "汇总作业…"
                            "ask_jiaoxiaozhi"       -> "询问交晓智…"
                            "get_app_settings"      -> "读取设置…"
                            "set_app_setting"       -> "修改设置…"
                            "calculate"             -> "计算…"
                            "check_update"          -> "检查更新…"
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
                val attendanceRoute = if (loginState.sessionManager?.getSiteOrNull("pg_attendance")?.hasLogin == true)
                    "postgraduate_attendance" else "attendance"

                val navSuggestions = calledTools.mapNotNull { toolName ->
                    when (toolName) {
                        "get_schedule", "get_exam_schedule" -> "查看课表"   to "schedule"
                        "get_school_calendar"               -> "查看校历"   to "school_calendar"
                        "search_school_courses"             -> "全校课程"   to "school_course"
                        "get_empty_rooms"                   -> "空闲教室"   to "empty_room"
                        "get_attendance"                    -> "查看考勤"   to attendanceRoute
                        "get_grades"                        -> "成绩查询"   to "jwapp_score"
                        "get_card_balance", "get_card_transactions" -> "校园卡" to "campus_card"
                        "get_notifications"                 -> "通知公告"   to "notification"
                        "search_yellow_page"                -> "校园黄页"   to "yellow_page"
                        "get_library_booking", "get_library_seats" -> "图书馆" to "library"
                        "get_textbooks"                     -> "教材中心"   to "jiaocai"
                        "get_coupons"                       -> "加餐券"     to "coupon"
                        "get_lms_courses", "get_lms_activities", "get_lms_assignments" -> "思源学堂" to "lms"
                        "get_app_settings", "set_app_setting" -> "设置"     to "settings"
                        else                                -> null
                    }
                }.distinctBy { it.second }

                val widgets = registry.drainWidgets()
                if (currentSessionId == turnSid) {   // 仍在本会话才写 UI
                    if (streamIdx >= 0) {
                        messages[streamIdx] = messages[streamIdx].copy(
                            content = reply, navSuggestions = navSuggestions, widgets = widgets
                        )
                    } else {
                        messages.add(ChatMessage("assistant", reply, navSuggestions = navSuggestions, widgets = widgets))
                    }
                    maybeAutoTitle(config)   // 首轮结束后用 AI 总结一个会话标题（仿 opencode）
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了"停止"/切换会话：保留已生成的部分（仅当仍在本会话）
                if (currentSessionId == turnSid && streamIdx >= 0)
                    messages[streamIdx] = messages[streamIdx].copy(
                        content = messages[streamIdx].content.ifBlank { "（已停止）" }
                    )
                throw e
            } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
                if (currentSessionId == turnSid)
                    messages.add(ChatMessage("assistant", "登录已失效，请返回并重新进入对应功能页面完成认证后再试。"))
            } catch (e: Exception) {
                // 兜底文案：异常 message 可能为 null（如某些 IO/解析异常），避免直接显示「出错了：null」
                val detail = e.message?.takeIf { it.isNotBlank() }
                    ?: e::class.simpleName?.let { "请求异常（$it）" }
                    ?: "未知错误"
                if (currentSessionId == turnSid) {
                    errorMessage = detail
                    messages.add(ChatMessage("assistant", "出错了：$detail"))
                }
            } finally {
                if (currentSessionId == turnSid) {
                    isLoading = false
                    persist(turnSid)   // 仅当仍在本会话才落盘，避免把新会话内容写到旧 id
                }
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
