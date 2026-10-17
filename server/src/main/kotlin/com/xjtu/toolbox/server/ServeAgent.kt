package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AGENT_CONTEXT_TOKEN_LIMIT
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentConfigStore
import com.xjtu.toolbox.agent.AgentModelFetcher
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentPrompt
import com.xjtu.toolbox.agent.AgentRunner
import com.xjtu.toolbox.agent.AgentSessionStore
import com.xjtu.toolbox.agent.AgentTitleGen
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.AgentTransport
import com.xjtu.toolbox.agent.SseAgentTransport
import com.xjtu.toolbox.agent.StoredConversation
import com.xjtu.toolbox.agent.StoredMessage
import com.xjtu.toolbox.agent.StoredWidget
import com.xjtu.toolbox.agent.agentSanitizeHistory
import com.xjtu.toolbox.agent.agentToolLabel
import com.xjtu.toolbox.agent.agentNavSuggestions
import com.xjtu.toolbox.agent.toStored
import com.xjtu.toolbox.agent.sanitizeAgentTitle
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.platform.protectedSecureKeyValueStore
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.formatTwoDigits
import com.xjtu.toolbox.util.nowInSystemZone
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.stringValue
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** 屁岱在 serve 侧的那一半：配置 / 会话 / 记忆三份落盘 + 整个调度循环 + SSE 事件流。 */
class ServeAgent(
    private val session: ServeSession,
    private val configStore: AgentConfigStore,
    private val store: AgentSessionStore,
    private val memory: AgentMemory,
    private val toolTable: (String) -> AgentTools,
    private val transport: () -> AgentTransport,
    /** 拉模型列表那一枪（`POST /api/agent/models`）。 */
    private val fetchModels: suspend (AgentConfig) -> List<String> = { AgentModelFetcher.fetch(it) },
    /** 首轮结束后那一枪「给这段对话起个标题」（`:data` 的 `AgentTitleGen`，与 Android / 桌面同一份）。 */
    private val generateTitle: suspend (AgentConfig, String, String) -> String? =
        { config, user, assistant -> AgentTitleGen.generate(config, user, assistant) },
    private val mfaSiteName: () -> String? = { session.activeMfaRequest?.siteName },
    private val pingIntervalMillis: Long = AGENT_SSE_PING_MS,
) {

    // ── 配置 / 记忆 / 会话（三条都是 HTTP 端点直接读写的）────────────────────

    /** 读配置（`GET /api/agent/config` 与每一轮循环都用它）。 */
    fun loadConfig(): AgentConfig = configStore.load()

    /** 存配置（`POST /api/agent/config`）。**整份覆盖**：调用方负责先把要保留的字段读出来。 */
    fun saveConfig(config: AgentConfig) = configStore.save(config)

    /** 拉模型列表（见 [fetchModels] 那一格缝）：成功回已去重升序的型号列表，失败抛。 */
    suspend fun models(config: AgentConfig): List<String> = fetchModels(config)

    /** 这一个部署有没有配 API Key。**唯一**允许投影出去的「key 相关」信息（bool，不是掩码）。 */
    fun keyConfigured(): Boolean = loadConfig().apiKey.isNotBlank()

    /** 记忆的全部条目（最近写入的在后）。`__order` 那个内部键**不投影**（见 `AgentMemory`）。 */
    fun memoryItems(): List<Pair<String, String>> = memory.all()

    /** 忘掉一条；返回 `AgentMemory.forget` 的原话（「没有记过「X」。」也照原样回）。 */
    fun forgetMemory(key: String): String = memory.forget(key)

    fun clearMemory() = memory.clear()

    fun sessions() = store.list().sortedByDescending { it.updatedAt }

    fun createSession(title: String?): com.xjtu.toolbox.agent.AgentSession =
        store.create(title?.takeIf { it.isNotBlank() } ?: AgentSessionStore.DEFAULT_TITLE)

    fun conversation(id: String): StoredConversation? = store.load(id)

    /** 改名（对不存在的 id 是**空操作** —— `AgentSessionStore.rename` 就是 `map` 一遍索引）。 */
    fun renameSession(id: String, title: String, lock: Boolean) = store.rename(id, title, lock)

    /** 删除（幂等）。 */
    fun deleteSession(id: String) = store.delete(id)

    /** 这一份部署真给模型的工具名（`/api/agent/config` 的 `toolAllowlist`）。 */
    fun toolAllowlist(searchEngine: String): List<String> =
        runCatching {
            AppJson.parseToJsonElement(toolTable(searchEngine).toolDefinitions).jsonArray
                .mapNotNull { it.jsonObject.obj("function")?.get("name")?.stringValue }
        }.getOrDefault(emptyList())

    // ── 一轮的互斥与取消 ────────────────────────────────────────────────────

    /** 同时只允许一轮（见类 KDoc 的「并发」）。 */
    private val runMutex = Mutex()
    private var runningSessionId: String? = null
    private var runningJob: Job? = null

    /** 自动起标题那一发**不占着这一轮**（与 `AgentViewModel` 的 `viewModelScope.launch` 同义）。 */
    private val background = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 现在有没有一轮在跑（`GET /api/agent/status` 用它）。 */
    val running: Boolean get() = runningSessionId != null

    /** 在跑的是哪个会话（没有就是 null）。 */
    fun runningSession(): String? = runningSessionId

    /** 占住「这一轮」的位置；占不到就是已经有一轮在跑（路由据此答 409）。 */
    fun tryBeginRun(sessionId: String): Boolean {
        if (!runMutex.tryLock()) return false
        runningSessionId = sessionId
        return true
    }

    /** 放开位置（路由的 `finally` 调它，成功/失败/取消都要放开）。 */
    fun endRun() {
        runningSessionId = null
        runningJob = null
        runMutex.unlock()
    }

    /** 停止正在跑的那一轮（幂等：没有在跑就什么都不做）。 */
    fun stop(): Boolean {
        val job = runningJob ?: return false
        job.cancel()
        return true
    }

    /** 跑一轮，把事件按 [write] 写出去（路由传的是「往 SSE 通道写一帧」）。 */
    suspend fun runTurn(sessionId: String, text: String, write: suspend (String) -> Unit) {
        coroutineScope {
            val events = Channel<AgentStreamEvent>(Channel.UNLIMITED)
            val writer = launch { for (event in events) write(agentSseFrame(event)) }
            val job = launch {
                try {
                    executeTurn(sessionId, text) { events.trySend(it) }
                } finally {
                    events.close()
                }
            }
            runningJob = job
            try {
                job.join()
            } finally {
                runningJob = null
            }
            writer.join()
        }
    }

    // ── 一轮的内部 ──────────────────────────────────────────────────────────

    /** 这一轮的一条工具行（落盘用：收尾后的文案 + 失败原因）。 */
    private class ToolLine(val label: String) {
        var error: String? = null
        var success = true
    }

    private suspend fun CoroutineScope.executeTurn(
        sessionId: String,
        text: String,
        emit: (AgentStreamEvent) -> Unit,
    ) {
        val config = loadConfig()
        val persisted = store.load(sessionId) ?: return
        var history: MutableList<JsonElement> = runCatching {
            AppJson.parseToJsonElement(persisted.llmHistory).jsonArray.toMutableList<JsonElement>()
        }.getOrDefault(mutableListOf())
        var totalTokens = persisted.lastTotalTokens
        var exhausted = persisted.contextExhausted
        val ui = persisted.messages.toMutableList()

        val tools = toolTable(config.searchEngine)
        tools.drainWidgets()   // 丢弃上一轮残留，确保本轮控件干净
        val runner = AgentRunner(tools, transport())

        if (history.firstOrNull()?.jsonObject?.get("role")?.stringValue != "system") {
            val systemPrompt = AgentPrompt.build(
                assistantName = config.effectiveName,
                userContext = tools.userContext(allowNetwork = true),
                memoryBlock = memory.promptBlock(),
            )
            val rebuilt = mutableListOf<JsonElement>()
            rebuilt.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            history.filterNot { it.jsonObject.get("role")?.stringValue == "system" }
                .forEach { rebuilt.add(it) }
            history = rebuilt
        }

        val now = Clock.System.now().toEpochMilliseconds()
        ui.add(StoredMessage(role = "user", content = text, timestamp = now))
        history.add(buildJsonObject {
            put("role", "user")
            put("content", nowTag(config.effectiveModel) + "\n" + text)
        })
        history = agentSanitizeHistory(history)   // 自愈：清掉上一次中断留下的 tool_calls 残体
        // 本轮那条 user 消息之后的内容都是「没答完就不该留」的部分（照 discardUnfinishedTurn）
        val unfinishedFrom = history.size - 1

        val toolLines = mutableListOf<ToolLine>()
        val calledTools = mutableListOf<String>()
        val reasoning = StringBuilder()
        var reply: String? = null
        var failure: AgentStreamEvent.Error? = null

        val mfaJob = launch { watchMfa(emit) }
        val pingJob = if (pingIntervalMillis > 0) {
            launch {
                while (true) {
                    delay(pingIntervalMillis)
                    emit(AgentStreamEvent.Ping)
                }
            }
        } else {
            null
        }

        try {
            reply = runner.run(
                messages = history,
                config = config,
                onDelta = { emit(AgentStreamEvent.Delta(it)) },
                onReasoningDelta = { frag ->
                    reasoning.append(frag)
                    emit(AgentStreamEvent.Reasoning(frag))
                },
                onToolCall = { name ->
                    calledTools.add(name)
                    toolLines.add(ToolLine(agentToolLabel(name)))
                    emit(AgentStreamEvent.ToolCall(name))
                },
                onToolResult = { name, success, err ->
                    toolLines.lastOrNull()?.let {
                        it.success = success
                        it.error = if (success) null else (err ?: "工具调用失败")
                    }
                    emit(AgentStreamEvent.ToolResult(name, success, if (success) null else (err ?: "工具调用失败")))
                },
                onUsage = { tokens ->
                    totalTokens = tokens
                    // 800k 阈值在这里判（与 `AgentViewModel` 同一个常量），结果由 `done` 带出去
                    if (tokens >= AGENT_CONTEXT_TOKEN_LIMIT) exhausted = true
                    emit(AgentStreamEvent.Usage(tokens))
                },
            )
        } catch (e: CancellationException) {
            // 停止生成 / 客户端断开（见类 KDoc）：历史不留残体，落盘记「（已停止）」，一个事件都不再发
            finishInterrupted(sessionId, ui, toolLines, history.take(unfinishedFrom).toMutableList(), totalTokens, exhausted)
            throw e
        } catch (e: Exception) {
            history = history.take(unfinishedFrom).toMutableList()
            toolLines.forEach { it.success = false; it.error = it.error ?: "已中断" }
            failure = if (e is SessionExpiredFailure) {
                AgentStreamEvent.Error("session_expired", SESSION_EXPIRED_TEXT)
            } else {
                AgentStreamEvent.Error("llm", e.message?.takeIf { it.isNotBlank() } ?: "未知错误")
            }
        } finally {
            mfaJob.cancel()
            pingJob?.cancel()
        }

        if (failure != null) {
            // 失败也要落盘（与 `AgentViewModel` 的 `finally` 里那份 persist 同一时机）：
            // 屏上看见的那句「出错了：…」下次进来还在，不会因为刷新就消失。
            ui.add(StoredMessage(role = "assistant", content = errorBubbleText(failure!!), timestamp = now))
            persist(sessionId, ui, toolLines, null, null, null, history, totalTokens, exhausted, emptyList())
            emit(failure!!)
            return
        }

        val widgets = tools.drainWidgets()
        val nav = agentNavSuggestions(calledTools)
        emit(AgentStreamEvent.Widgets(widgets.map { it.toStored() }))
        emit(AgentStreamEvent.Nav(nav))
        val title = persist(
            sessionId = sessionId,
            ui = ui,
            toolLines = toolLines,
            reply = reply.orEmpty(),
            nav = nav,
            reasoning = reasoning.toString().takeIf { it.isNotBlank() },
            history = history,
            totalTokens = totalTokens,
            exhausted = exhausted,
            widgets = widgets.map { it.toStored() },
        )
        emit(
            AgentStreamEvent.Done(
                content = reply.orEmpty(),
                sessionId = sessionId,
                title = title,
                contextExhausted = exhausted,
                totalTokens = totalTokens,
            ),
        )
        maybeAutoTitle(sessionId, config)
    }

    /** 被取消的那条路：落盘一份「这一轮没答完」的状态，UI 侧一句话都不说（流已经断了）。 */
    private fun finishInterrupted(
        sessionId: String,
        ui: MutableList<StoredMessage>,
        toolLines: List<ToolLine>,
        history: MutableList<JsonElement>,
        totalTokens: Long?,
        exhausted: Boolean,
    ) {
        toolLines.forEach { it.success = false; it.error = it.error ?: "已中断" }
        persist(
            sessionId = sessionId,
            ui = ui,
            toolLines = toolLines,
            reply = "（已停止）",
            nav = null,
            reasoning = null,
            history = history,
            totalTokens = totalTokens,
            exhausted = exhausted,
            widgets = emptyList(),
        )
    }

    /** 把这一轮的结果写进会话（时机与 `AgentViewModel.persist(turnSid)` 相同：流结束时）。 */
    private fun persist(
        sessionId: String,
        ui: MutableList<StoredMessage>,
        toolLines: List<ToolLine>,
        reply: String?,
        nav: List<Pair<String, String>>?,
        reasoning: String?,
        history: MutableList<JsonElement>,
        totalTokens: Long?,
        exhausted: Boolean,
        widgets: List<StoredWidget>,
    ): String {
        val now = Clock.System.now().toEpochMilliseconds()
        toolLines.forEach {
            ui.add(
                StoredMessage(
                    role = "tool_event",
                    content = if (it.success) com.xjtu.toolbox.agent.agentCompleteToolLabel(it.label) else it.label,
                    timestamp = now,
                    toolError = it.error,
                ),
            )
        }
        if (reply != null) {
            ui.add(
                StoredMessage(
                    role = "assistant",
                    content = reply,
                    nav = nav.orEmpty().map { listOf(it.first, it.second) },
                    widgets = widgets,
                    reasoningContent = reasoning,
                    timestamp = now,
                ),
            )
        }
        val existing = store.list().firstOrNull { it.id == sessionId }
        val title = when {
            existing?.locked == true -> existing.title
            else -> deriveTitle(ui)
        }
        store.save(
            sessionId,
            StoredConversation(ui.toList(), JsonArray(history).toString(), totalTokens, exhausted),
            title,
        )
        return store.list().firstOrNull { it.id == sessionId }?.title ?: title
    }

    /** 标题取首条用户消息（截断 18 字 → `sanitizeAgentTitle` 再截 12），无则默认。 */
    private fun deriveTitle(ui: List<StoredMessage>): String {
        val firstUser = ui.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
        return if (firstUser.isBlank()) {
            AgentSessionStore.DEFAULT_TITLE
        } else {
            sanitizeAgentTitle(firstUser, AgentSessionStore.DEFAULT_TITLE)
        }
    }

    /** 首轮结束后用一次轻量模型调用起标题（与 `AgentViewModel.maybeAutoTitle` 同判据： 只在真用户消息恰好一条、且标题没被锁时做）。 */
    private fun maybeAutoTitle(sessionId: String, config: AgentConfig) {
        val convo = store.load(sessionId) ?: return
        if (store.isLocked(sessionId)) return
        val users = convo.messages.filter { it.role == "user" }
        if (users.size != 1) return
        val firstUser = users.first().content.trim()
        val firstAssistant = convo.messages.lastOrNull { it.role == "assistant" }?.content?.trim().orEmpty()
        if (firstUser.isBlank() || firstAssistant.isBlank()) return
        background.launch {
            val title = runCatching { generateTitle(config, firstUser, firstAssistant) }.getOrNull()
            if (!title.isNullOrBlank() && !store.isLocked(sessionId)) {
                store.rename(sessionId, title, lock = true)
            }
        }
    }


    /** 那一轮失败落盘时写进气泡的那句话。 */
    private fun errorBubbleText(failure: AgentStreamEvent.Error): String =
        if (failure.kind == "session_expired") failure.message else "出错了：${failure.message}"
    /** 每条 user 消息开头的实时标签（与 `AgentViewModel.nowTag` 同形）。 */
    private fun nowTag(model: String): String {
        val now = nowInSystemZone()
        val days = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val w = days.getOrElse(now.dayOfWeek.isoDayNumber) { "" }
        val time = "${now.date} ${formatTwoDigits(now.hour)}:${formatTwoDigits(now.minute)}"
        return "[现在：$time $w" + (if (model.isBlank()) "" else " · $model") + "]"
    }

    /** 把校园会话的 MFA 挂起状态变成 `mfa` 事件（只有站名，没有手机号 —— §5.1）。 */
    private suspend fun watchMfa(emit: (AgentStreamEvent) -> Unit) {
        var last: String? = null
        while (true) {
            delay(AGENT_MFA_POLL_MS)
            val site = mfaSiteName()
            if (site != last) {
                last = site
                emit(AgentStreamEvent.Mfa(pending = site != null, siteName = site))
            }
        }
    }

    companion object {

        /** SSE 保活注释帧的间隔（设计稿 §3.1：每 15 秒一个 `: ping`）。 */
        const val AGENT_SSE_PING_MS = 15_000L

        /** MFA 挂起状态轮询的间隔（见 [watchMfa]）。 */
        private const val AGENT_MFA_POLL_MS = 500L

        /** 课表族缓存的落盘文件（`schedule_cache<账号后缀>`）。 */
        private const val SCHEDULE_CACHE_STORE = "schedule_cache"

        /** 自建日程的落盘文件（里面只有 `custom_courses` 这一个键，与桌面同一份）。 */
        private const val CUSTOM_COURSE_STORE = "custom_courses"

        /** 与 `AgentViewModel` 的那句完全一致（同一个标记接口、同一个文案）。 */
        internal const val SESSION_EXPIRED_TEXT = "登录已失效，请返回并重新进入对应功能页面完成认证后再试。"

        /** serve 进程的默认装配。 */
        fun forServe(session: ServeSession): ServeAgent {
            // 非敏感半与记忆：serve 上必须用**真落盘**的那份（0600 properties），默认的
            // `keyValueStore` 在 jvm 上按端而异，不能靠它（`docs/storage-layout.md` §0 第 3 条）。
            val configStore = AgentConfigStore(
                plainStoreOf = { secureKeyValueStore(it) },
                secureStoreOf = { suffix ->
                    protectedSecureKeyValueStore(
                        AgentConfigStore.STORE_SECURE + suffix,
                        AgentConfigStore.STORE_LEGACY + suffix,
                    )
                },
            )
            val sessionStore = AgentSessionStore()
            val memory = AgentMemory(
                storeOf = { secureKeyValueStore(AgentMemory.STORE + AccountContext.safeSuffix()) },
                legacyStoreOf = { secureKeyValueStore(AgentMemory.STORE) },
            )
            val scheduleCache = KeyValueScheduleCacheStore(
                secureKeyValueStore(SCHEDULE_CACHE_STORE + AccountContext.safeSuffix()),
            )
            val calendar = SchoolCalendarApi()
            val customCourses = KeyValueCustomCourseStore(secureKeyValueStore(CUSTOM_COURSE_STORE))
            // 黄页与教师检索都免登录：与 `/api/info/yellowpage` 那一条路由同一份实现（同一条客户端缝）。
            val yellowPage = YellowPageApi(createToolboxClient(), cache = null)
            val faculty = FacultyApi()

            return ServeAgent(
                session = session,
                configStore = configStore,
                store = sessionStore,
                memory = memory,
                // 工具表按「这一轮真的会给模型的那些定义」现搭：`searchEngine` 这一端用不上
                //（白名单里没有联网搜索那一档），但缝留着（与 `AgentViewModel` 同一个形状）。
                toolTable = {
                    ServeAgentTools(
                        session = session,
                        memory = memory,
                        dataCache = scheduleCache,
                        customCourses = customCourses,
                        calendar = calendar,
                        yellowPage = yellowPage,
                        faculty = faculty,
                    )
                },
                transport = { SseAgentTransport() },
            )
        }
    }
}

/** SSE 那一帧要说的十类事（`docs/agent-web-spec.md` §3.2 的表）+ 一类保活注释帧。 */
internal sealed interface AgentStreamEvent {

    /** 模型正文增量，逐段。 */
    data class Delta(val text: String) : AgentStreamEvent

    /** 思维链增量，逐段（仅思考开启时）。 */
    data class Reasoning(val text: String) : AgentStreamEvent

    /** 一次工具调用，聚合之后、开始执行之前。**只报名字**（文案在消费者那边按同一份表拼）。 */
    data class ToolCall(val name: String) : AgentStreamEvent

    /** 一次工具执行结束。[error] 只在失败时有值（`null` = 明确说成功，见契约 §4）。 */
    data class ToolResult(val name: String, val success: Boolean, val error: String?) : AgentStreamEvent

    /** 模型回报的累计 token（只用来显示/兜底；`contextExhausted` 由服务端判）。 */
    data class Usage(val totalTokens: Long) : AgentStreamEvent

    /** 校园站点的短信二验挂起 / 结束。**不带手机号**。 */
    data class Mfa(val pending: Boolean, val siteName: String?) : AgentStreamEvent

    /** 一轮的卡片，一次给全（落盘形状与 `StoredMessage.widgets` 同形）。 */
    data class Widgets(val widgets: List<StoredWidget>) : AgentStreamEvent

    /** 回复下方那排跳转按钮（`route` 是 `AppRoute.id`）。 */
    data class Nav(val routes: List<Pair<String, String>>) : AgentStreamEvent

    /** 一轮**正常结束**（唯一正常终止事件，之后流关闭）。 */
    data class Done(
        val content: String,
        val sessionId: String,
        val title: String,
        val contextExhausted: Boolean,
        val totalTokens: Long?,
    ) : AgentStreamEvent

    /** 一轮**异常结束**（唯一异常终止事件）。[kind] 见设计稿 §3.2 第 10 条。 */
    data class Error(val kind: String, val message: String) : AgentStreamEvent

    /** 保活注释帧（`:` 开头那一行，客户端解析时必须忽略）。 */
    data object Ping : AgentStreamEvent
}

/** 一个事件 → SSE 的一帧。 */
internal fun agentSseFrame(event: AgentStreamEvent): String = when (event) {
    is AgentStreamEvent.Delta ->
        sseFrame("delta", buildJsonObject { put("text", event.text) })

    is AgentStreamEvent.Reasoning ->
        sseFrame("reasoning", buildJsonObject { put("text", event.text) })

    is AgentStreamEvent.ToolCall ->
        sseFrame("tool_call", buildJsonObject { put("name", event.name) })

    is AgentStreamEvent.ToolResult -> sseFrame(
        "tool_result",
        buildJsonObject {
            put("name", event.name)
            put("success", event.success)
            put("error", event.error)
        },
    )

    is AgentStreamEvent.Usage ->
        sseFrame("usage", buildJsonObject { put("totalTokens", event.totalTokens) })

    is AgentStreamEvent.Mfa -> sseFrame(
        "mfa",
        buildJsonObject {
            put("pending", event.pending)
            put("siteName", event.siteName)
        },
    )

    is AgentStreamEvent.Widgets -> sseFrame(
        "widget",
        buildJsonObject {
            put("widgets", buildJsonArray {
                event.widgets.forEach { w ->
                    add(buildJsonObject {
                        put("type", w.type)
                        put("json", w.json)
                    })
                }
            })
        },
    )

    is AgentStreamEvent.Nav -> sseFrame(
        "nav",
        buildJsonObject {
            put("routes", buildJsonArray {
                event.routes.forEach { (label, route) ->
                    add(buildJsonObject {
                        put("label", label)
                        put("route", route)
                    })
                }
            })
        },
    )

    is AgentStreamEvent.Done -> sseFrame(
        "done",
        buildJsonObject {
            put("content", event.content)
            put("sessionId", event.sessionId)
            put("title", event.title)
            put("contextExhausted", event.contextExhausted)
            put("totalTokens", event.totalTokens)
        },
    )

    is AgentStreamEvent.Error -> sseFrame(
        "error",
        buildJsonObject {
            put("kind", event.kind)
            put("message", event.message)
        },
    )

    AgentStreamEvent.Ping -> ": ping\n\n"
}

/** `event:` + `data:` + 空行（见 [agentSseFrame]）。 */
private fun sseFrame(name: String, payload: kotlinx.serialization.json.JsonObject): String =
    "event: $name\ndata: ${ApiJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)}\n\n"

/** SSE 的 content-type（`text/event-stream`；Ktor 3.6 没有服务端 SSE 插件，帧是本文件手写的）。 */
internal val AGENT_SSE_CONTENT_TYPE: ContentType = ContentType.Text.EventStream
