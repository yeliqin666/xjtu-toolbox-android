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

/**
 * 屁岱在 serve 侧的那一半：**配置 / 会话 / 记忆三份落盘** + **整个调度循环** + **SSE 事件流**。
 *
 * ## 它是谁（与 `AgentHost` / `:web` 的关系）
 *
 * `docs/agent-web-spec.md` §1.2 选了「循环跑在 serve，不跑在浏览器」：`:core` 的 [AgentRunner]、
 * `:data` 的 [SseAgentTransport] 与三个 store 在 serve（jvm）上**原样可用**，所以这一端要写的只有
 * 「把它们接起来」这一层 —— 就是本类。它**不是** [com.xjtu.toolbox.agent.AgentHost] 的实现
 *（那个面的语义是「这一端有什么**端上资源**」，浏览器与 serve 都没有那些资源，见设计稿 §4.1），
 * 也**不是** `AgentViewModel`：那个类带着 Compose 状态与「切屏/串台」的 UI 顾虑，这里的消费者是
 * HTTP 与 SSE。
 *
 * ## 与 `AgentViewModel` 的对应（同一份判据，两处消费者）
 *
 * | 这件事 | `AgentViewModel` | 这里 |
 * |---|---|---|
 * | 系统提示（一段对话只生成一次） | 首轮按 `hasSystem` 生成 | 同一个判据，写进落盘的历史 |
 * | 每条 user 消息头 `[现在：…]` | `nowTag` | [nowTag]（逐字同形） |
 * | 循环 / 保险丝 / 限流 / 截断 / 原子提交 | `AgentRunner` | **同一份** [AgentRunner] |
 * | 模型那一枪 | `SseAgentTransport` | **同一份**（serve 上直接 `new`） |
 * | 工具行文案 / 跳转建议 | `:core` 的 `agentToolLabel` / `agentNavSuggestions` | 同一份（serve 只报工具名与路由 id） |
 * | 落盘时机 | 一轮结束时 `persist(turnSid)` | 同一时机（流结束/被中断都在 `finally` 里落） |
 * | 自愈损坏的历史 | `sanitizeHistory` | 同一份 `agentSanitizeHistory` |
 * | 800k 阈值 | `AGENT_CONTEXT_TOKEN_LIMIT` | 同一个常量，结果由 `done` 带出 |
 *
 * **不在这里**的：气泡的 50ms 合流、思考动画、弹窗（那些是浏览器屏的事）、
 * 以及 `replaceLastUserAndSend` 的「改上一条」（v1 不画那个入口，见设计稿 §9 未决 #7）。
 *
 * ## 工具层：白名单九件（S-c 已落地）
 *
 * [toolTable] 是「这次部署真给模型的工具表」这一格缝。[forServe] 交的是 [ServeAgentTools] ——
 * 设计稿 §6 那九件（四条逐字搬 `:app` + 3 条纯逻辑 + 公开通讯录两条），
 * 见那个类的 KDoc 与 `ServeAgentToolCopyDriftTest`（两份不漂的钉子）。
 * 后果是**可见的**：`/api/agent/config` 的 `toolAllowlist` 就是那九件（它是从真下发给模型的
 * 工具定义里读出来的，不是另维护一份清单），`tool_call` / `tool_result` / `widget` / `nav`
 * 四类事件在真机上都会出现。
 *
 * ## 并发与取消（设计稿 §3.4）
 *
 * - **同时只允许一轮**（[runMutex] + [tryBeginRun]）：两个并发 run 会往同一份 `llmHistory` 里
 *   交错写 tool 消息，历史直接烂掉。第二发由路由答 `409`；
 * - **停止 / 客户端断开是同一条路**：[stop] 取消的是这一轮的 [Job]（不是 HTTP 那个 call 的协程 ——
 *   那会把响应也一起掐断，客户端就连「流干净地结束了」都看不到）。被取消时：
 *   历史**不留残体**（照 `discardUnfinishedTurn` 把本轮那条 user 消息之后的全部丢掉）、
 *   落盘里末尾那句写「（已停止）」、**不再发任何事件**（`done` / `error` 都不发：
 *   这一轮没跑完，说它「正常结束」或「出错」都是假话；消费者自己知道自己按了停止）。
 *
 * @param session serve 的会话装配：MFA 询问（[mfaSiteName]）与「这一轮该不该联网补画像」都由它给。
 * @param configStore 配置那两份落盘（非敏感 + 密钥半）。**serve 上必须传真落盘的 KV**，
 *   否则 `:core` 的 jvm `keyValueStore` 是进程内存（`docs/storage-layout.md` §0 第 3 条）。
 * @param store 会话落盘（`agent_sessions<suffix>/index.json` + `convo_<id>.json`）。
 * @param memory 记忆（`agent_memory<suffix>`）—— 与配置、会话同一套账号后缀。
 * @param toolTable 「搜索源 → 工具表」：与 `AgentViewModel` 一样按搜索源重建（工具表构造时绑账号）。
 * @param transport 「发一次请求」那一格缝。生产是 `:data` 的 [SseAgentTransport]，测试用夹具。
 * @param mfaSiteName 正在等哪个站点的短信验证码（null = 没有）。默认读 serve 的会话内核。
 * @param pingIntervalMillis SSE 保活注释帧的间隔；`<= 0` 表示不发（测试用）。
 *
 * 【可见性】这个类是 **public** 的，只有一个原因：[serveModule] / [serveServer] 是 public 函数，
 * 而它们的签名里带着这一格（Kotlin 不让 public 函数暴露 internal 类型）。它不是给模块外用的 API：
 * 外面那一层契约始终是 HTTP（契约 §5.11）。
 */
class ServeAgent(
    private val session: ServeSession,
    private val configStore: AgentConfigStore,
    private val store: AgentSessionStore,
    private val memory: AgentMemory,
    private val toolTable: (String) -> AgentTools,
    private val transport: () -> AgentTransport,
    /**
     * 拉模型列表那一枪（`POST /api/agent/models`）。默认就是 `:data` 的
     * `AgentModelFetcher`（与 Android / 桌面同一份）；测试换成夹具 —— 这一族**不许**真打服务商。
     */
    private val fetchModels: suspend (AgentConfig) -> List<String> = { AgentModelFetcher.fetch(it) },
    /**
     * 首轮结束后那一枪「给这段对话起个标题」（`:data` 的 `AgentTitleGen`，与 Android / 桌面同一份）。
     * 测试换成夹具／关掉 —— 和 [fetchModels] 同一条理由：**这一族不许真打服务商**。
     */
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

    /**
     * 这一份部署真给模型的工具名（= `/api/agent/config` 的 `toolAllowlist`）。
     *
     * **从工具定义里读**，不是另维护一份常量清单：白名单与「模型能调什么」共用一处真相，
     * 两边不可能对上不上（设计稿 §6 的「同一份常量两个消费者」在这里的落法是「同一份定义」）。
     */
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

    /**
     * 占住「这一轮」的位置；占不到就是**已经有一轮在跑**（路由据此答 409）。
     *
     * 用 `tryLock` 而不是 `lock`：第二发要**立刻**拿到答案，不能排在可能几十秒的一轮后面。
     */
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

    /**
     * 跑一轮，把事件按 [write] 写出去（路由传的是「往 SSE 通道写一帧」）。
     *
     * 结构：事件先进一个**无界队列**，一个写手协程按顺序出队写 —— 因为 [AgentRunner] 的几个回调
     * （`onToolCall` / `onToolResult` / `onUsage`）**不是 suspend 的**，不能直接调写通道。
     * 取消只落在这一轮的 [Job] 上：写手照旧把队列排空，流干净地结束。
     */
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

        // 系统提示**一段对话只生成一次**：与 `AgentViewModel` 同一个判据（历史里没有 system 才生成）。
        // 画像要学号，只在 serve 侧拼得出来（设计稿 §5.2 红线：学号不出网）。
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
                // 与 `AgentViewModel` 的 catch 分支逐字同源（同一条「登录失效」的标记接口）
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

    /**
     * 把这一轮的结果写进会话（时机与 `AgentViewModel.persist(turnSid)` 相同：流结束时）。
     *
     * @param reply 助手这一轮的回复；[cancelled] 为 true 时是那句「（已停止）」。
     * @return 落盘后的标题（`done.title` 用它）。
     */
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

    /**
     * 首轮结束后用一次轻量模型调用起标题（与 `AgentViewModel.maybeAutoTitle` 同判据：
     * 只在真用户消息恰好一条、且标题没被锁时做）。
     *
     * 它在**这一轮之外**跑（[background]）：那一枪最长 20 秒，压在这一轮里会让浏览器在
     * `done` 之后还挂着一个没关的流。
     */
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


    /**
     * 那一轮失败落盘时写进气泡的那句话 —— 与 `AgentViewModel` 的两条 catch 分支逐字同源：
     * 登录失效是那一整句，其余是「出错了：$detail」（浏览器侧照同一句画）。
     */
    private fun errorBubbleText(failure: AgentStreamEvent.Error): String =
        if (failure.kind == "session_expired") failure.message else "出错了：${failure.message}"
    /**
     * 每条 user 消息开头的实时标签（与 `AgentViewModel.nowTag` 逐字同形）。
     * 契约 §4 说时间用服务端系统时区（这一端就是 serve 那台机器）。
     */
    private fun nowTag(model: String): String {
        val now = nowInSystemZone()
        val days = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val w = days.getOrElse(now.dayOfWeek.isoDayNumber) { "" }
        val time = "${now.date} ${formatTwoDigits(now.hour)}:${formatTwoDigits(now.minute)}"
        return "[现在：$time $w" + (if (model.isBlank()) "" else " · $model") + "]"
    }

    /**
     * 把校园会话的 MFA 挂起状态变成 `mfa` 事件（**只有站名，没有手机号** —— §5.1 红线）。
     *
     * 为什么是轮询而不是监听：serve 的会话内核把这件事暴露成 `activeMfaRequest` 这个**属性**
     *（`StateFlow` 的 `.value`），而这里只要「变了就说一声」。500ms 的粒度对一条「请等短信」的
     * 提示完全够，且不会漏掉一次请求（它最长挂 150 秒）。
     */
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

        /**
         * 课表族缓存的落盘文件（`schedule_cache<账号后缀>`）。
         *
         * ⚠️ 与 `:desktop` 的 `DesktopScheduleHost.SCHEDULE_CACHE_STORE`、`:web` 的同名常量
         * **逐字相同**：serve 与桌面读的是同一份文件（设计稿 §9 未决 #5 的口径）。三处各写一份
         * 字面量（跨模块拿不到彼此的 `internal` 常量），改一处就得三处一起改。
         */
        private const val SCHEDULE_CACHE_STORE = "schedule_cache"

        /** 自建日程的落盘文件（里面只有 `custom_courses` 这一个键，与桌面同一份）。 */
        private const val CUSTOM_COURSE_STORE = "custom_courses"

        /** 与 `AgentViewModel` 的那句完全一致（同一个标记接口、同一个文案）。 */
        internal const val SESSION_EXPIRED_TEXT = "登录已失效，请返回并重新进入对应功能页面完成认证后再试。"

        /**
         * serve 进程的默认装配：三份落盘按 `docs/storage-layout.md` §2/§3/§5 的口径接上，
         * 密钥半套 `:data` 的凭据保护器（Windows = DPAPI；Linux/macOS 没有保护器 ⇒ 明文 0600，
         * 与今天逐字相同 —— 设计稿 §5.1 的「缺口 A」在 serve 这一侧就此收口）。
         * ⚠️ **工具表这一格交的是 [ServeAgentTools]**（白名单九件，与 `/api/agent/config` 的
         * `toolAllowlist` 同一份定义）：这一行与那一份清单不可能对不上（设计稿 §6 的「同一份常量两个消费者」）。
         */
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
            // 课表族缓存与自建日程：文件名与桌面**逐字相同** ⇒ serve 读得到桌面写下的那一份
            //（设计稿 §8 「自建日程读」那一条：桌面加的看得到，Android 的 Room 看不到）。
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

/**
 * SSE 那一帧要说的十类事（`docs/agent-web-spec.md` §3.2 的表）+ 一类保活注释帧。
 *
 * 事件名与载荷**逐条照设计稿**：`delta` / `reasoning` / `tool_call` / `tool_result` / `usage` /
 * `mfa` / `widget` / `nav` / `done` / `error`，外加 `: ping`（SSE 规范里的注释行，客户端必须忽略）。
 */
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

/**
 * 一个事件 → SSE 的一帧。**纯函数**（`:web` 那一侧的解析器与它对着写，也是本文件唯一被单测直接钉的形状）。
 *
 * 帧的形状照 SSE 规范：`event: <名>\n` + `data: <一行 JSON>\n` + 一个空行。
 * 载荷里**没有换行**（JSON 自己会把换行转义成 `\n`），所以「一帧一行」这条成立。
 */
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
