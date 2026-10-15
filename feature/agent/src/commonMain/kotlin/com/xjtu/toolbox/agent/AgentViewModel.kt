package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.JsonArray
import com.xjtu.toolbox.nav.AppRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.isoDayNumber
import com.xjtu.toolbox.util.formatTwoDigits
import com.xjtu.toolbox.util.nowOnCampus
import kotlin.time.Clock

/**
 * 聊天消息 UI 模型。
 *
 * @param navSuggestions 本轮涉及的功能页跳转建议，List<Pair<显示文字, [AppRoute.id]>>。
 *   存字符串而不是路由对象：聊天记录要落盘，点按钮时再用 [com.xjtu.toolbox.nav.appRouteOf] 解析回来。
 */
data class ChatMessage(
    val role: String,          // "user" | "assistant" | "tool_event"
    val content: String,
    val isToolCall: Boolean = false,
    val navSuggestions: List<Pair<String, String>> = emptyList(),
    val widgets: List<AgentWidget> = emptyList(),
    val reasoningContent: String = "",
    /** 随这条消息发出的图片，存的是应用私有目录里的压缩件路径。见 [AgentVision]。 */
    val images: List<String> = emptyList(),
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    /**
     * 工具调用失败信息：非 null 时 UI 上对应气泡标红并显示原因。
     * 区分 [isToolCall] 的"执行中转圈"和"执行失败"——避免用户被空提示骗"还在跑"。
     */
    val toolError: String? = null,
)

/**
 * Agent 是否正在思考/生成——底栏屁岱的「思考」动画读这里（见 PidaiNavButton）。
 * 与 [AgentViewModel.isLoading] 由同一个 setter 同步；单独拎出来是因为 ViewModel
 * 挂在导航项上，而底栏常驻，需要全局可观察（同 ProactiveBubbleHost 的路子）。
 */
object AgentThinkingHost {
    var isThinking by mutableStateOf(false)
}

class AgentViewModel(private val host: AgentHost) : ViewModel(), AgentChatSession {
    companion object {
        /** 流式分片合并写入的间隔：约 3 帧，肉眼仍是连续输出，重组和解析次数降一两个数量级。 */
        private const val STREAM_FLUSH_MS = 50L
        /** 见 [AGENT_CONTEXT_EXHAUSTED_MESSAGE]：serve 侧的同一句文案就是它。 */
        const val CONTEXT_EXHAUSTED_MESSAGE = AGENT_CONTEXT_EXHAUSTED_MESSAGE
    }

    override val messages = mutableStateListOf<ChatMessage>()
    override var isLoading by mutableStateOf(false)
        private set

    /** 加载态的唯一写入口：UI 加载与底栏思考动画共用同一信号，不会各走各的。 */
    private fun applyLoading(loading: Boolean) {
        isLoading = loading
        AgentThinkingHost.isThinking = loading
    }
    override var errorMessage by mutableStateOf<String?>(null)
    /**
     * 上下文耗尽标记。变 true 时 UI 应主动弹窗提醒用户"请新开对话"，
     * 而不是悄悄把输入框禁用 + 文字提示里藏一句。
     */
    override var contextExhausted by mutableStateOf(false)
        private set
    private var lastTotalTokens: Long? = null

    /** contextExhausted 刚刚由 false 变 true 的一次性事件：UI 用 LaunchedEffect 弹窗。 */
    override var contextExhaustedJustTriggered by mutableStateOf(false)
        private set
    override fun consumeContextExhaustedTrigger(): Boolean {
        val v = contextExhaustedJustTriggered
        contextExhaustedJustTriggered = false
        return v
    }

    // ── 多会话状态 ──────────────────────────────────────────────────────
    override val sessions = mutableStateListOf<AgentSession>()
    override var currentSessionId by mutableStateOf<String?>(null)
        private set
    private var store: AgentSessionPersistence? = null

    // LLM 多轮历史（含 system prompt + 所有轮次），与 messages（UI 专用）独立维护。
    // 保持历史稳定以利用 provider 端 prefix cache：system prompt 只在首轮写入一次。
    private var llmHistory: MutableList<JsonElement> = mutableListOf()

    // AgentToolRegistry 保持在 ViewModel 级别，使 loginFailedAt 冷却状态跨消息保留
    private var tools: AgentTools? = null
    private var toolsSearchEngine: String? = null
    /** 工具注册表里的 DataCache 构造时绑定账号，换账号必须重建，否则工具读写的是旧账号缓存。 */
    private var toolsAccountId: String? = null

    /**
     * 上一次为补全用户画像联网时的登录态。登录态没变就不再重试，
     * 免得没登录的人每条消息都先干等一轮网络超时。见 [sendMessage]。
     */
    private var userContextProbe: String? = null

    // 当前生成任务，供"停止生成"取消
    private var currentJob: kotlinx.coroutines.Job? = null

    /** 停止正在进行的生成。 */
    override fun stop() { currentJob?.cancel() }

    override fun replaceLastUserAndSend(
        newText: String,
        config: AgentConfig,
    ) {
        val text = newText.trim()
        if (text.isBlank()) return
        // 改的是文字，图片跟着这条消息一起重发：用户点"改上一条"是想换个问法，
        // 不是想把图撤回——丢掉图会让新问题突然没了上下文。
        val keptImages = messages.lastOrNull { it.role == "user" }?.images.orEmpty()
        currentJob?.cancel()
        applyLoading(false)
        dropLastUserTurn()
        sendMessage(text, config, images = keptImages)
    }

    private fun dropLastUserTurn() {
        val uiIdx = messages.indexOfLast { it.role == "user" }
        if (uiIdx >= 0) {
            while (messages.size > uiIdx) messages.removeAt(messages.lastIndex)
        }
        val items = (0 until llmHistory.size).map { llmHistory[it].jsonObject }
        val lastUser = items.indexOfLast { it.get("role")?.stringValue == "user" }
        if (lastUser >= 0) {
            llmHistory = items.take(lastUser).toMutableList<JsonElement>()
        }
        persist()
    }

    private fun settleToolAt(index: Int, success: Boolean, err: String?) {
        if (index !in messages.indices) return
        val bubble = messages[index]
        if (bubble.role != "tool_event") return
        messages[index] = bubble.copy(
            isToolCall = false,
            content = if (success) agentCompleteToolLabel(bubble.content) else bubble.content,
            toolError = if (success) null else (err ?: bubble.toolError),
        )
    }

    /** 修复历史完整性：见 [agentSanitizeHistory]（两端同一份判据）。 */
    private fun sanitizeHistory() {
        llmHistory = agentSanitizeHistory(llmHistory)
    }

    // ── 会话管理 ────────────────────────────────────────────────────────

    /** 绑定持久化存储并加载会话列表（仅一次）。空则自动建首个会话。 */
    override fun bind(store: AgentSessionPersistence) {
        if (this.store != null) return
        this.store = store
        sessions.addAll(store.list())
        val first = sessions.firstOrNull()
        if (first != null) switchSession(first.id) else newSession()
    }

    override fun newSession() {
        val store = store ?: return
        currentJob?.cancel(); applyLoading(false)   // 取消进行中的生成，避免写入新会话造成错乱
        val s = store.create()
        currentSessionId = s.id
        messages.clear(); llmHistory = mutableListOf(); tools = null; errorMessage = null
        lastTotalTokens = null; contextExhausted = false; contextExhaustedJustTriggered = false
        refreshSessions()
    }

    override fun switchSession(id: String) {
        val store = store ?: return
        if (id == currentSessionId && messages.isNotEmpty() && !isLoading) return
        currentJob?.cancel(); applyLoading(false)   // 切换前停掉旧生成，避免两个会话内容串台
        val convo = store.load(id) ?: return
        currentSessionId = id
        messages.clear()
        convo.messages.forEach { m ->
            messages.add(ChatMessage(
                role = m.role,
                content = m.content,
                navSuggestions = m.nav.mapNotNull { if (it.size >= 2) it[0] to it[1] else null },
                widgets = m.widgets.orEmpty().mapNotNull { storedToWidget(it) },
                reasoningContent = m.reasoningContent.orEmpty(),
                // 图片文件可能已被清掉（清缓存、换账号），这里只过滤掉不存在的，
                // 气泡少显示一张图，比留个破图标好。
                images = m.images.orEmpty().filter { path -> host.imageFileExists(path) },
                timestamp = m.timestamp ?: Clock.System.now().toEpochMilliseconds(),
                isToolCall = false,
                toolError = m.toolError,
            ))
        }
        llmHistory = runCatching { AppJson.parseToJsonElement(convo.llmHistory).jsonArray.toMutableList() }.getOrDefault(mutableListOf())
        lastTotalTokens = convo.lastTotalTokens
        contextExhausted = convo.contextExhausted
        contextExhaustedJustTriggered = false
        // 落盘的 system prompt 原样沿用，缺了才在下一轮生成（见 sendMessage）
        tools = null; errorMessage = null
    }

    override fun deleteSession(id: String) {
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
                it.widgets.map { widget -> widget.toStored() },
                it.reasoningContent.takeIf { reasoning -> reasoning.isNotBlank() },
                it.timestamp,
                it.toolError,
                it.images.takeIf { images -> images.isNotEmpty() },
            )
        }
        val title = if (store.isLocked(id))
            sessions.firstOrNull { it.id == id }?.title ?: deriveTitle()
        else deriveTitle()
        store.save(id, StoredConversation(stored, JsonArray(llmHistory).toString(), lastTotalTokens, contextExhausted), title)
        refreshSessions()
    }

    /**
     * 注入每条 user 消息开头的实时标签：时间和当前模型。
     * 这两样一段对话里会变（跨天、中途换模型），所以不放进只生成一次的系统提示。
     */
    private fun nowTag(model: String): String {
        val now = nowOnCampus()
        val days = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val w = days.getOrElse(now.dayOfWeek.isoDayNumber) { "" }
        val time = "${now.date} ${formatTwoDigits(now.hour)}:${formatTwoDigits(now.minute)}"
        return "[现在：$time $w" + (if (model.isBlank()) "" else " · $model") + "]"
    }

    /** 首轮结束后用 AI 把对话总结成简短标题（仿 opencode）；用户已改名则不动。 */
    private fun maybeAutoTitle(config: AgentConfig) {
        val store = store ?: return
        val id = currentSessionId ?: return
        if (store.isLocked(id)) return
        if (messages.count { it.role == "user" } != 1) return
        val firstUser = messages.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
        if (firstUser.isBlank()) return
        // 取**最后一条**助手消息：当首轮触发了工具调用链路时，UI 里
        // 会留下若干 tool 段 + 结尾的 assistant 总结；标题应当基于**最终回复**来起。
        val firstAssistant = messages.lastOrNull { it.role == "assistant" }?.content?.trim().orEmpty()
        if (firstAssistant.isBlank()) return
        viewModelScope.launch {
            val title = host.generateTitle(config, firstUser, firstAssistant)?.takeIf { it.isNotBlank() }
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
            firstUser.isBlank() -> AGENT_SESSION_DEFAULT_TITLE
            else -> sanitizeAgentTitle(firstUser, AGENT_SESSION_DEFAULT_TITLE)
        }
    }

    override fun sendMessage(
        userText: String,
        config: AgentConfig,
        /** 不进聊天气泡，只进发给模型的 user 消息（默认值写在 [AgentChatSession] 那条接口上）。 */
        llmAnnex: String?,
        /** 随消息发送的图片路径，见 [AgentVision.attach]。 */
        images: List<String>,
    ) {
        // 只发图不打字是合理的（「这是什么」），所以有图时允许正文为空。
        if ((userText.isBlank() && images.isEmpty()) || isLoading) return
        if (contextExhausted) {
            errorMessage = CONTEXT_EXHAUSTED_MESSAGE
            return
        }
        if (currentSessionId == null) newSession()
        errorMessage = null
        messages.add(ChatMessage("user", userText, images = images))
        applyLoading(true)

        val turnSid = currentSessionId   // 本轮所属会话；切走后不再写当前 messages，避免串台
        var streamIdx = -1   // 流式回答气泡的下标，首个 delta 到达时创建

        // 流式分片先攒着，按 STREAM_FLUSH_MS 合并写进 messages。以前每个分片都
        // `content + frag` 整串拼接一次、触发一次重组、MarkdownText 再把全文重新解析一遍，
        // 长回复在快模型上是 O(n²)，气泡明显掉帧。全部回调都在主线程，无需加锁。
        val pendingContent = StringBuilder()
        val pendingReasoning = StringBuilder()
        var flushJob: kotlinx.coroutines.Job? = null
        fun flushStream() {
            flushJob?.cancel()
            flushJob = null
            if (pendingContent.isEmpty() && pendingReasoning.isEmpty()) return
            if (currentSessionId == turnSid && streamIdx in messages.indices) {
                val m = messages[streamIdx]
                messages[streamIdx] = m.copy(
                    content = m.content + pendingContent,
                    reasoningContent = m.reasoningContent + pendingReasoning,
                )
            }
            pendingContent.setLength(0)
            pendingReasoning.setLength(0)
        }
        fun scheduleFlush() {
            if (flushJob != null) return
            flushJob = viewModelScope.launch {
                kotlinx.coroutines.delay(STREAM_FLUSH_MS)
                flushJob = null   // 先置空，flushStream 里的 cancel 不会取消到自己
                flushStream()
            }
        }
        fun ensureStreamBubble() {
            if (streamIdx < 0) {
                messages.add(ChatMessage("assistant", ""))
                streamIdx = messages.lastIndex
            }
        }
        val toolBubbleIndices = mutableListOf<Int>()
        var turnUserMsg: JsonElement? = null
        /** 这一轮没答完：把它在 LLM 历史里留下的提问和没配对的工具调用整段摘掉，免得下一轮出现连续两条 user。 */
        fun discardUnfinishedTurn() {
            val start = llmHistory.indexOfLast { it === turnUserMsg }
            if (start >= 0) llmHistory = llmHistory.take(start).toMutableList()
        }
        currentJob = viewModelScope.launch {
            try {
                // 首次调用时初始化，此后复用（loginFailedAt 冷却状态得以保留）
                val accountNow = com.xjtu.toolbox.account.AccountContext.activeAccountId
                if (toolsSearchEngine != config.searchEngine || toolsAccountId != accountNow) {
                    tools = null
                    toolsSearchEngine = config.searchEngine
                    toolsAccountId = accountNow
                }
                // 工具层与传输层都是**端装配**（见 `:core` 的 [AgentHost]）：`:app` 交取数那些
                // `*Api` + Android 能力，桌面交它能取到的那部分。没接的端 `honored` 为空，
                // 那几档就不会画给模型（见 `AgentCapability`）。
                val registry = tools ?: host.tools(config.searchEngine).also { tools = it }
                registry.drainWidgets()   // 丢弃上一轮残留，确保本轮控件干净
                val runner = AgentRunner(registry, host.transport())

                // 系统提示**一段对话只生成一次**：对话里还没有它（新对话，或老数据缺了）才生成，
                // 之后原样复用。名字、偏好、画像中途变了，都从下一个新对话起生效——
                // 中途改写等于篡改上下文：前几轮按旧设定答的，前后人设对不上，前缀缓存也整段作废。
                // 会变的时间和模型走每条消息头（nowTag），不在这里。
                val hasSystem = llmHistory.size > 0 && runCatching {
                    llmHistory[0].jsonObject.get("role")?.stringValue == "system"
                }.getOrDefault(false)
                if (!hasSystem) {
                    // 联网补齐姓名/学院只在登录态变过之后再试：没登录时每个新对话都去撞一次网络，
                    // 只会让第一条消息先干等一轮超时。
                    val loginKey = host.loginKey
                    val allowProfileNetwork = userContextProbe != loginKey
                    if (allowProfileNetwork) userContextProbe = loginKey
                    val systemPrompt = AgentPrompt.build(
                        assistantName = config.effectiveName,
                        userContext = registry.userContext(allowNetwork = allowProfileNetwork),
                        memoryBlock = registry.memoryBlock(),
                    )
                    // system 必须待在第 0 位：整段历史是 provider 端 prefix cache 的比对前缀
                    val rebuilt = mutableListOf<JsonElement>()
                    rebuilt.add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    (0 until llmHistory.size)
                        .map { llmHistory[it] }
                        .filterNot { el ->
                            runCatching { el.jsonObject.get("role")?.stringValue == "system" }
                                .getOrDefault(false)
                        }
                        .forEach { rebuilt.add(it) }
                    llmHistory = rebuilt
                }

                // 每条 user 消息携带实时时间，杜绝"今天/明天"按会话起始日的陈旧判断。
                // 提醒快照只给模型看：对准「点的是哪件事」，不当完整事实，也不进气泡。
                val llmUser = buildString {
                    append(nowTag(config.effectiveModel))
                    append('\n')
                    if (!llmAnnex.isNullOrBlank()) {
                        append("[本地提醒快照]\n")
                        append("用户从 App 提醒点进来。快照只说明点的是哪件事，不是完整数据。")
                        append("缺的字段不要编；要完整、最新或清单仍调工具。\n")
                        append(llmAnnex.trim())
                        append("\n\n")
                    }
                    append(userText.ifBlank { "（见图）" })
                }
                val userMsg = buildJsonObject {
                    put("role", "user")
                    // 无图时这里仍是纯字符串，历史结构和以前完全一致。
                    put("content", AgentVision.userContent(llmUser, images, host))
                }
                turnUserMsg = userMsg
                llmHistory.add(userMsg)
                sanitizeHistory()   // 自愈：清掉上一次中断留下的 tool_calls 残体
                // 带图轮数攒多了才裁一次，见 AgentVision.pruneOldImages
                AgentVision.pruneOldImages(llmHistory)

                val calledTools = mutableListOf<String>()

                // MFA 提示：监控 SessionManager 的 activeMfaRequest，弹窗期间在对话列表追加一条
                // "需要登录验证" tool_event，让用户知道为什么对话卡住——不是 AI 在慢，是在校验。
                // 协程在 run() 挂起期间仍存活，MFA 完成后我们再把这个事件标 isToolCall=false 让它收尾。
                var mfaBubbleIdx = -1
                var mfaJob: kotlinx.coroutines.Job? = null
                val mfaFlow = host.mfaRequests
                if (mfaFlow != null) {
                    mfaJob = launch {
                        mfaFlow.collect { req ->
                            if (req != null && mfaBubbleIdx < 0 && currentSessionId == turnSid) {
                                messages.add(
                                    ChatMessage(
                                        role = "tool_event",
                                        content = "需要登录验证：等待「${req.siteName}」短信验证码…",
                                        isToolCall = true,
                                    )
                                )
                                mfaBubbleIdx = messages.lastIndex
                            } else if (req == null && mfaBubbleIdx >= 0) {
                                // MFA 结束（无论成功或取消）：把气泡关掉
                                if (mfaBubbleIdx in messages.indices) {
                                    messages[mfaBubbleIdx] = messages[mfaBubbleIdx].copy(isToolCall = false)
                                }
                                mfaBubbleIdx = -1
                            }
                        }
                    }
                }

                val reply = runner.run(
                    messages = llmHistory,
                    config = config,
                    onDelta = { frag ->
                        if (currentSessionId == turnSid) {   // 已切走则不再写，防串台
                            ensureStreamBubble()
                            pendingContent.append(frag)
                            scheduleFlush()
                        }
                    },
                    onReasoningDelta = { frag ->
                        if (currentSessionId == turnSid) {
                            ensureStreamBubble()
                            pendingReasoning.append(frag)
                            scheduleFlush()
                        }
                    },
                    onToolCall = { name ->
                        calledTools.add(name)
                        // 本轮模型可能先输出思考/短回复，再请求工具；工具执行后下一次模型回复应新开气泡。
                        // 换气泡前先把攒着的分片落到旧气泡上。
                        flushStream()
                        streamIdx = -1
                        val label = agentToolLabel(name)
                        if (currentSessionId == turnSid) {
                            messages.add(ChatMessage("tool_event", label, isToolCall = true))
                            toolBubbleIndices.add(messages.lastIndex)
                        }
                    },
                    onToolResult = { _, success, errMsg ->
                        if (currentSessionId == turnSid && toolBubbleIndices.isNotEmpty()) {
                            val lastIdx = toolBubbleIndices.last()
                            settleToolAt(lastIdx, success, errMsg ?: "工具调用失败")
                            toolBubbleIndices.remove(lastIdx)
                        }
                    },
                    onUsage = { totalTokens ->
                        lastTotalTokens = totalTokens
                        if (totalTokens >= AGENT_CONTEXT_TOKEN_LIMIT) {
                            val wasExhausted = contextExhausted
                            contextExhausted = true
                            // 只在本次会话中由「未耗尽 → 耗尽」转变时弹窗，避免每次回复都触发
                            if (!wasExhausted) {
                                contextExhaustedJustTriggered = true
                            }
                        }
                    }
                )

                toolBubbleIndices.forEach { settleToolAt(it, true, null) }
                toolBubbleIndices.clear()
                mfaJob?.cancel()
                mfaJob = null
                // MFA 还挂起时退出 run 的极端情况下，标记 mfaBubble 完成避免残留加载图标
                if (mfaBubbleIdx >= 0 && mfaBubbleIdx in messages.indices) {
                    messages[mfaBubbleIdx] = messages[mfaBubbleIdx].copy(isToolCall = false)
                }

                // 存的是 [AppRoute.id]：聊天记录要落盘，点按钮时再用 appRouteOf 解析回来
                val navSuggestions = agentNavSuggestions(calledTools)

                val widgets = registry.drainWidgets()
                flushStream()
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
                flushStream()
                discardUnfinishedTurn()
                if (currentSessionId == turnSid) {
                    toolBubbleIndices.forEach { settleToolAt(it, false, "已中断") }
                    toolBubbleIndices.clear()
                    if (streamIdx >= 0)
                        messages[streamIdx] = messages[streamIdx].copy(
                            content = messages[streamIdx].content.ifBlank { "（已停止）" }
                        )
                }
                throw e
            } catch (e: Exception) {
                // 「登录态失效」是 `:core` 的标记接口（见 `error/SessionExpiredFailure`）——
                // `catch` 抓不了接口，所以先抓 `Exception` 再判，与评教/成绩/校园卡/加餐券同一条缝。
                if (e is com.xjtu.toolbox.error.SessionExpiredFailure) {
                    discardUnfinishedTurn()
                    if (currentSessionId == turnSid)
                        messages.add(ChatMessage("assistant", "登录已失效，请返回并重新进入对应功能页面完成认证后再试。"))
                } else {
                    // 兜底文案：异常 message 可能为 null（如某些 IO/解析异常），避免直接显示「出错了：null」
                    val detail = e.message?.takeIf { it.isNotBlank() }
                        ?: e::class.simpleName?.let { "请求异常（$it）" }
                        ?: "未知错误"
                    discardUnfinishedTurn()
                    if (currentSessionId == turnSid) {
                        errorMessage = detail
                        messages.add(ChatMessage("assistant", "出错了：$detail"))
                    }
                }
            } finally {
                flushStream()   // 出错路径上已收到的部分也要落到气泡里再持久化
                if (currentSessionId == turnSid) {
                    applyLoading(false)
                    persist(turnSid)   // 仅当仍在本会话才落盘，避免把新会话内容写到旧 id
                }
            }
        }
    }

}
