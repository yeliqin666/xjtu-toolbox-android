package com.xjtu.toolbox.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.xjtu.toolbox.agent.AGENT_CONTEXT_EXHAUSTED_MESSAGE
import com.xjtu.toolbox.agent.AgentChatSession
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentSession
import com.xjtu.toolbox.agent.AgentSessionPersistence
import com.xjtu.toolbox.agent.ChatMessage
import com.xjtu.toolbox.agent.normalizeReasoningEffort
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 浏览器侧的屁岱驱动（`docs/agent-web-spec.md` §4.2 的逐成员对应表）。 */
internal class WebAgentSession(
    private val client: HttpClient,
    private val scope: CoroutineScope,
) : AgentChatSession {

    /** 事件 → 状态那一半（屏读的就是它那几格）。 */
    internal val state = AgentStreamState()

    override val messages: SnapshotStateList<ChatMessage> get() = state.messages

    override val isLoading: Boolean get() = state.isLoading

    /** 浏览器端没有外部深链，这一格只为接口对齐。 */
    override var draft: String? by mutableStateOf(null)

    override var errorMessage: String?
        get() = state.errorMessage
        set(value) {
            state.errorMessage = value
        }

    override val contextExhausted: Boolean get() = state.contextExhausted

    override val contextExhaustedJustTriggered: Boolean get() = state.contextExhaustedJustTriggered

    override fun consumeContextExhaustedTrigger(): Boolean = state.consumeContextExhaustedTrigger()

    override val sessions: SnapshotStateList<AgentSession> = mutableStateListOf()

    override var currentSessionId by mutableStateOf<String?>(null)
        private set

    private var bound = false

    /** 这一轮属于哪条会话（`POST /api/agent/stop` 要指名道姓）。 */
    private var turnSessionId: String? = null

    /** 正在跑的那条流的 key（[AgentSseBridge] 用 key 换手 abort）。 */
    private var runningKey: String? = null

    /** 流 key 的自增量（同一页里可能连开好几轮）。 */
    private var streamSeq = 0

    /** 「这一轮」的代数：取消 / 切走 / 新开都 +1 ⇒ 旧那一轮的消费者一个字都不再写。 */
    private var turnGeneration = 0

    // ── 会话表 ──────────────────────────────────────────────────────────────

    /** 屏进来时调一次（`AgentChatSession.bind` 的语义）：拉会话表，接上最近那一条（没有就新建一条）。 */
    override fun bind(store: AgentSessionPersistence) {
        if (bound) return
        bound = true
        scope.launch {
            refreshSessions()
                .onSuccess { list ->
                    val first = list.firstOrNull()
                    if (first != null) switchSession(first.id) else newSession()
                }
                .onFailure { errorMessage = it.message }
        }
    }

    override fun newSession() {
        cancelTurn()
        scope.launch {
            agentCall(AGENT_SESSIONS_PATH, "{}")
                .onSuccess { data ->
                    currentSessionId = sessionOf(data["session"])?.id
                    state.clear()
                    refreshSessions()
                }
                .onFailure { errorMessage = it.message }
        }
    }

    override fun switchSession(id: String) {
        if (id == currentSessionId && messages.isNotEmpty() && !isLoading) return
        // 切走就取消（照 `AgentViewModel`：切换前停掉旧生成，避免两个会话内容串台）
        cancelTurn()
        scope.launch {
            agentCall("$AGENT_SESSIONS_PATH/$id", null)
                .onSuccess { data ->
                    currentSessionId = id
                    state.load(data["conversation"] as? JsonObject)
                }
                .onFailure { errorMessage = it.message }
        }
    }

    override fun deleteSession(id: String) {
        scope.launch {
            agentCall("$AGENT_SESSIONS_PATH/$id/delete", "{}").onFailure { errorMessage = it.message }
            val list = refreshSessions().getOrElse { emptyList() }
            if (currentSessionId != id) return@launch
            // 删掉的正是屏幕上这一条：先把它从屏上拿掉（否则 switchSession 的「同一条就不动」会挡着）
            currentSessionId = null
            state.clear()
            val next = list.firstOrNull()
            if (next != null) switchSession(next.id) else newSession()
        }
    }

    override fun stop() {
        // 两条一起走（契约 §3.4：「停止」与「断开」是同一条路）：显式 stop 让服务端立刻回一句，
        // abort 保证本端这条流一定收摊（写帧失败 / 客户端断开也是同一条取消判据）。
        val target = turnSessionId
        scope.launch {
            agentCall(
                AGENT_STOP_PATH,
                AppJson.encodeToString(AgentStopBody.serializer(), AgentStopBody(sessionId = target)),
            ).onFailure { errorMessage = it.message }
        }
        cancelTurn()
    }

    // ── 一轮对话 ────────────────────────────────────────────────────────────

    override fun sendMessage(userText: String, config: AgentConfig, llmAnnex: String?, images: List<String>) {
        // 与 `AgentViewModel` 同一判据：正在生成就静默忽略（同一页里撞不上；两个标签页那种并发由服务端答 409）
        if (userText.isBlank() || isLoading) return
        if (contextExhausted) {
            errorMessage = AGENT_CONTEXT_EXHAUSTED_MESSAGE
            return
        }
        errorMessage = null
        val sessionId = currentSessionId
        if (sessionId == null) {
            scope.launch {
                ensureSession()
                    .onSuccess { startTurn(it, userText) }
                    .onFailure { state.fail(it.message ?: "新建会话失败") }
            }
        } else {
            startTurn(sessionId, userText)
        }
    }

    override fun replaceLastUserAndSend(newText: String, config: AgentConfig) {
        val text = newText.trim()
        if (text.isBlank()) return
        cancelTurn()
        state.dropLastUserTurn()
        sendMessage(text, config)
    }

    private fun startTurn(sessionId: String, text: String) {
        val turnSid = sessionId
        val generation = ++turnGeneration
        turnSessionId = turnSid
        state.beginTurn(text)

        val key = "agent-${streamSeq++}"
        runningKey = key
        val channel = Channel<TurnSignal>(Channel.UNLIMITED)
        val parser = AgentSseParser()

        scope.launch {
            var sawTerminal = false
            while (true) {
                // 50ms 静默 ⇒ 把攒着的正文落到气泡上（照抄 `AgentViewModel` 的 `STREAM_FLUSH_MS` 合流）
                val signal = withTimeoutOrNull(STREAM_FLUSH_MS) { channel.receiveCatching() }
                if (signal == null) {
                    // 静默满 50ms：把攒着的正文落到气泡上（这就是「合流」的节拍）
                    if (generation == turnGeneration) state.flush()
                    continue
                }
                // 通道被关（两个终止回调都会关它）= 这一轮读完了
                val value = signal.getOrNull() ?: break
                // 这一轮已经被取消 / 切走了：一个字都不写（防串台）
                if (generation != turnGeneration) continue
                when (value) {
                    is TurnSignal.Frame -> {
                        if (value.frame is AgentFrame.Done || value.frame is AgentFrame.Error) sawTerminal = true
                        state.apply(value.frame)
                    }

                    is TurnSignal.Failed -> {
                        val message = agentStreamFailureMessage(value.payload)
                        // `null` = 我方自己停的（`kind:"abort"`）⇒ 走「（已停止）」那一档，不说它出错
                        if (message == null) state.stopped() else state.fail(message)
                    }

                    TurnSignal.Ended -> {
                        // 见过 `done` / `error` 才算正常收摊；没见过 = 被停止 / 客户端断开（契约 §5.11）
                        if (sawTerminal) state.finishTurn() else state.stopped()
                    }
                }
            }
            if (generation == turnGeneration) {
                state.flush()
                runningKey = null
                // 标题可能被 serve 改了（`done.title`）⇒ 拉一次会话表（与 `persist()` 后的 refresh 同一时机）
                refreshSessions()
            }
        }

        AgentSseBridge.start(
            key = key,
            url = AGENT_CHAT_PATH,
            token = serveToken.token,
            body = AppJson.encodeToString(
                AgentChatBody.serializer(),
                AgentChatBody(sessionId = turnSid, text = text),
            ),
            onFrame = { chunk -> parser.push(chunk).forEach { channel.trySend(TurnSignal.Frame(it)) } },
            // 两个终止回调都要**关通道**：消费循环靠它收摊（不关的话那一轮读完了也一直在
            // 50ms 地空转）。无界通道里已经攒着的帧会先被取完，再读到关闭。
            onDone = {
                channel.trySend(TurnSignal.Ended)
                channel.close()
            },
            onError = { payload ->
                channel.trySend(TurnSignal.Failed(payload))
                channel.close()
            },
        )
    }

    /** 取消当前那一轮：abort 掉流，并让它的消费者一个字都不再写（见 [turnGeneration]）。 */
    private fun cancelTurn() {
        turnGeneration++
        turnSessionId = null
        runningKey?.let { AgentSseBridge.abort(it) }
        runningKey = null
    }

    /** 手里没有会话（还没 `bind` 完 / 刚删掉）就现建一条 —— 与 `AgentViewModel` 的 `newSession()` 同义。 */
    private suspend fun ensureSession(): Result<String> {
        currentSessionId?.let { return Result.success(it) }
        return agentCall(AGENT_SESSIONS_PATH, "{}").map { data ->
            val id = sessionOf(data["session"])?.id.orEmpty()
            currentSessionId = id
            id
        }.mapCatching { id ->
            if (id.isBlank()) throw IllegalStateException("serve 新建的会话没有 id") else id
        }
    }

    private suspend fun refreshSessions(): Result<List<AgentSession>> =
        agentCall(AGENT_SESSIONS_PATH, null).map { data ->
            val list = data.arr("sessions").orEmpty().mapNotNull { sessionOf(it) }
            sessions.clear()
            sessions.addAll(list)
            list
        }

    // ── 配置 / 模型 / 记忆（配置面板那两格与「能做什么」那一段用）────────────────

    /** `GET /api/agent/config`：这一端认的那几格（没有 key）。 */
    suspend fun configView(): Result<AgentConfigView> =
        agentCall(AGENT_CONFIG_PATH, null).map { configViewOf(it) }

    /** `POST /api/agent/config`：整份覆盖，但那一格 `apiKey` 整个不出现。 */
    suspend fun saveConfig(config: AgentConfig): Result<Unit> = agentCall(
        AGENT_CONFIG_PATH,
        AppJson.encodeToString(
            AgentConfigBody.serializer(),
            AgentConfigBody(
                provider = config.provider,
                model = config.model,
                baseUrl = config.baseUrl,
                assistantName = config.assistantName,
                searchEngine = config.searchEngine,
                thinkingEnabled = config.thinkingEnabled,
                reasoningEffort = config.reasoningEffort,
                showReasoning = config.showReasoning,
            ),
        ),
    ).map { }

    /** `POST /api/agent/models`：拉模型列表那一枪在 serve 上打。失败**抛**（面板那一格显示 `e.message`）。 */
    suspend fun models(config: AgentConfig): List<String> = agentCall(
        AGENT_MODELS_PATH,
        AppJson.encodeToString(
            AgentModelsBody.serializer(),
            AgentModelsBody(provider = config.provider, baseUrl = config.baseUrl),
        ),
    ).map { data -> data.arr("models").orEmpty().map { it.safeString() }.filter { it.isNotBlank() } }
        .getOrThrow()

    /** `GET /api/agent/memory`：**模型用的那一份**记忆（在 serve 上；`__order` 不投影）。 */
    suspend fun memory(): Result<List<Pair<String, String>>> =
        agentCall(AGENT_MEMORY_PATH, null).map { data ->
            data.arr("items").orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val key = o["key"].safeString()
                if (key.isBlank()) null else key to o["value"].safeString()
            }
        }

    /** `POST /api/agent/memory/forget`：`message` 是 store 的原话（「没有记过「X」。」也照原样）。 */
    suspend fun forgetMemory(key: String): Result<String> = agentCall(
        AGENT_MEMORY_FORGET_PATH,
        AppJson.encodeToString(AgentMemoryForgetBody.serializer(), AgentMemoryForgetBody(key = key)),
    ).map { it["message"].safeString() }

    /** `POST /api/agent/memory/clear`。 */
    suspend fun clearMemory(): Result<Unit> = agentCall(AGENT_MEMORY_CLEAR_PATH, "{}").map { }

    // ── HTTP 那一层 ─────────────────────────────────────────────────────────

    /** 一次 `/api/agent` 那一族的调用：读 §4 的信封（规矩与 `WebServeSession.call` 一字不差）。 */
    private suspend fun agentCall(path: String, body: String?): Result<JsonObject> = try {
        val response = client.request {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            url("$API_BASE$path")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = response.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("serve 返回的不是 JSON 对象（HTTP ${response.status.value}）")
        if (envelope["code"].safeInt(-1) != 0) {
            error(
                envelope["message"].safeString().trim()
                    .ifBlank { "serve 返回 HTTP ${response.status.value}" },
            )
        }
        Result.success(envelope["data"] as? JsonObject ?: JsonObject(emptyMap()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/** 桥递回来的失败载荷 → 屏上那句话。 */
internal fun agentStreamFailureMessage(payload: String): String? {
    val obj = runCatching { AppJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        ?: return "读取回复失败：$payload"
    return when (obj["kind"].safeString()) {
        "http" -> {
            val body = obj["body"].safeString()
            val envelope = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            envelope?.get("message").safeString().trim().takeIf { it.isNotEmpty() }
                ?: "HTTP ${obj["status"].safeInt()}"
        }

        "abort" -> null

        else -> obj["message"].safeString().trim().ifBlank { "读取回复失败：$payload" }
    }
}

/** `GET /api/agent/config` 的那几格（契约 §5.11 第 1 条）。 */
internal data class AgentConfigView(
    val config: AgentConfig,
    val toolAllowlist: List<String>,
    val canEditKey: Boolean,
)

/** `GET /api/agent/config` 的 `data` → [AgentConfigView]。 */
internal fun configViewOf(data: JsonObject): AgentConfigView = AgentConfigView(
    config = AgentConfig(
        provider = data["provider"].safeString().ifBlank { AgentConfig.PROVIDER_DEEPSEEK },
        // ⚠️ 响应里没有 key；`apiKey` 恒为空串，「配好了没」看 `keyConfigured`。
        model = data["model"].safeString(),
        baseUrl = data["baseUrl"].safeString(),
        assistantName = data["assistantName"].safeString().ifBlank { AgentConfig.DEFAULT_ASSISTANT_NAME },
        searchEngine = data["searchEngine"].safeString().ifBlank { AgentConfig.SEARCH_AUTO },
        thinkingEnabled = data["thinkingEnabled"].safeBoolean(true),
        reasoningEffort = normalizeReasoningEffort(data["reasoningEffort"].safeString()),
        showReasoning = data["showReasoning"].safeBoolean(true),
        keyConfigured = data["keyConfigured"].safeBoolean(),
    ),
    toolAllowlist = data.arr("toolAllowlist").orEmpty().map { it.safeString() }.filter { it.isNotBlank() },
    canEditKey = data["canEditKey"].safeBoolean(),
)

/** `GET /api/agent/sessions` / `POST /api/agent/sessions` 里的一行会话（`updatedAt` 是 epoch 毫秒）。 */
private fun sessionOf(element: kotlinx.serialization.json.JsonElement?): AgentSession? {
    val o = element as? JsonObject ?: return null
    val id = o["id"].safeString()
    if (id.isBlank()) return null
    return AgentSession(
        id = id,
        title = o["title"].safeString(),
        createdAt = o["createdAt"].safeLong(),
        updatedAt = o["updatedAt"].safeLong(),
        locked = o["locked"].safeBoolean(),
    )
}

/** 一次「读这一轮」的输入（见 [WebAgentSession.startTurn] 的消费循环）。 */
private sealed interface TurnSignal {

    /** 一帧（已经由 [AgentSseParser] 切好、解码好）。 */
    data class Frame(val frame: AgentFrame) : TurnSignal

    /** 桥递回来的失败载荷（见 [agentStreamFailureMessage]）。 */
    data class Failed(val payload: String) : TurnSignal

    /** 流干净地关了。 */
    data object Ended : TurnSignal
}

/** 流式分片合并写入的间隔：与 `AgentViewModel.STREAM_FLUSH_MS` **同一个数**（约 3 帧）。 */
private const val STREAM_FLUSH_MS = 50L

// ── 端点路径（契约 §5.11 的十四条；`:server` 那几个常量是它模块内的，拿不到，所以这里照抄一份）──

private const val AGENT_PATH = "$API_BASE/api/agent"
private const val AGENT_CONFIG_PATH = "$AGENT_PATH/config"
private const val AGENT_MODELS_PATH = "$AGENT_PATH/models"
private const val AGENT_SESSIONS_PATH = "$AGENT_PATH/sessions"
private const val AGENT_MEMORY_PATH = "$AGENT_PATH/memory"
private const val AGENT_CHAT_PATH = "$AGENT_PATH/chat"
private const val AGENT_STOP_PATH = "$AGENT_PATH/stop"
private const val AGENT_MEMORY_FORGET_PATH = "$AGENT_MEMORY_PATH/forget"
private const val AGENT_MEMORY_CLEAR_PATH = "$AGENT_MEMORY_PATH/clear"


/** `POST /api/agent/chat` 的请求体。 */
@Serializable
private data class AgentChatBody(val sessionId: String, val text: String)

/** `POST /api/agent/stop` 的请求体（`sessionId` 缺省 = 停正在跑的那一轮）。 */
@Serializable
private data class AgentStopBody(val sessionId: String? = null)

/** `POST /api/agent/config` 的请求体：故意没有 `apiKey` 那一格（见 [WebAgentSession.saveConfig]）。 */
@Serializable
private data class AgentConfigBody(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val assistantName: String,
    val searchEngine: String,
    val thinkingEnabled: Boolean,
    val reasoningEffort: String,
    val showReasoning: Boolean,
)

/** `POST /api/agent/models` 的请求体。 */
@Serializable
private data class AgentModelsBody(val provider: String, val baseUrl: String)

/** `POST /api/agent/memory/forget` 的请求体。 */
@Serializable
private data class AgentMemoryForgetBody(val key: String)
