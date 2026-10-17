package com.xjtu.toolbox.server

import com.xjtu.toolbox.agent.AGENT_CONTEXT_EXHAUSTED_MESSAGE
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentSession
import com.xjtu.toolbox.agent.StoredMessage
import com.xjtu.toolbox.agent.StoredWidget
import com.xjtu.toolbox.agent.normalizeReasoningEffort
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** `/api/agent/…` —— 屁岱（Agent）在浏览器上那一族十四个端点（`docs/api-contract.md` §5.11）。 */
internal fun Route.agentRoutes(agent: ServeAgent) {
    route(AGENT_SEGMENT) {

        // ── 1. 读配置 ────────────────────────────────────────────────────────
        get(AGENT_CONFIG) {
            val config = agent.loadConfig()
            call.respond(
                ApiEnvelope.ok(
                    AgentConfigData(
                        provider = config.provider,
                        model = config.model,
                        effectiveModel = config.effectiveModel,
                        baseUrl = config.baseUrl,
                        assistantName = config.assistantName,
                        searchEngine = config.searchEngine,
                        thinkingEnabled = config.thinkingEnabled,
                        reasoningEffort = config.reasoningEffort,
                        showReasoning = config.showReasoning,
                        // 唯一允许投影的「key 相关」信息：一个 bool（不是掩码，不是前四位）
                        keyConfigured = config.apiKey.isNotBlank(),
                        // 真给模型的那批工具名（从工具定义里读，见 ServeAgent.toolAllowlist）
                        toolAllowlist = agent.toolAllowlist(config.searchEngine),
                        // 端能力：浏览器与 serve 都**没有**闹钟 / 应用设置 / 自建日程 / 相册 / 存下载
                        honored = AGENT_HONORED,
                        canEditKey = true,
                    ),
                ),
            )
        }

        // ── 2. 写配置（**整份覆盖**，apiKey 缺省 = 不动那一格）─────────────────
        post(AGENT_CONFIG) {
            val body = call.receiveOrNull<AgentConfigBody>()
                ?: return@post call.respondBadRequestMessage(AGENT_CONFIG_BODY_MESSAGE)
            val provider = body.provider?.trim().orEmpty()
            if (provider !in AgentConfig.PROVIDERS) {
                return@post call.respondBadRequestMessage(
                    "provider 认不出来（只认 ${AgentConfig.PROVIDERS.joinToString(" / ")}）",
                )
            }
            val searchEngine = body.searchEngine?.trim().orEmpty()
            if (searchEngine !in AgentConfig.SEARCH_ENGINES) {
                return@post call.respondBadRequestMessage(
                    "searchEngine 认不出来（只认 ${AgentConfig.SEARCH_ENGINES.joinToString(" / ")}）",
                )
            }
            val effort = body.reasoningEffort?.trim().orEmpty()
            if (effort.lowercase() !in AGENT_REASONING_EFFORTS) {
                return@post call.respondBadRequestMessage(
                    "reasoningEffort 认不出来（只认 ${AgentConfig.REASONING_EFFORTS.joinToString(" / ")}）",
                )
            }
            val missing = listOfNotNull(
                "model" to body.model,
                "baseUrl" to body.baseUrl,
                "assistantName" to body.assistantName,
                "thinkingEnabled" to body.thinkingEnabled,
                "showReasoning" to body.showReasoning,
            ).firstOrNull { it.second == null }?.first
            if (missing != null) {
                return@post call.respondBadRequestMessage("$missing 必填（本端点整份覆盖，不做的字段也要给现值）")
            }

            val current = agent.loadConfig()
            agent.saveConfig(
                current.copy(
                    provider = provider,
                    model = body.model.orEmpty(),
                    baseUrl = body.baseUrl.orEmpty(),
                    assistantName = body.assistantName.orEmpty(),
                    searchEngine = searchEngine,
                    thinkingEnabled = body.thinkingEnabled ?: current.thinkingEnabled,
                    reasoningEffort = normalizeReasoningEffort(effort),
                    showReasoning = body.showReasoning ?: current.showReasoning,
                    apiKey = body.apiKey ?: current.apiKey,
                ),
            )
            call.respond(ApiEnvelope.ok(AgentOkData(message = "配置已保存")))
        }

        // ── 3. 拉模型列表（服务商那一枪在 serve 上打）──────────────────────────
        post(AGENT_MODELS) {
            val body = call.receiveOrNull<AgentModelsBody>()
                ?: return@post call.respondBadRequestMessage(AGENT_MODELS_BODY_MESSAGE)
            val provider = body.provider?.trim().orEmpty()
            if (provider !in AgentConfig.PROVIDERS) {
                return@post call.respondBadRequestMessage(
                    "provider 认不出来（只认 ${AgentConfig.PROVIDERS.joinToString(" / ")}）",
                )
            }
            val current = agent.loadConfig()
            if (current.apiKey.isBlank()) return@post call.respondKeyMissing()
            val config = current.copy(provider = provider, baseUrl = body.baseUrl?.trim().orEmpty())
            try {
                call.respond(ApiEnvelope.ok(AgentModelsData(agent.models(config))))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 服务商/网络那一档：502 + 服务商原文（照设计稿：message = 服务商原文）
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiEnvelope<Unit>(ApiErrors.UPSTREAM_FAILED, message = e.message ?: "拉取模型列表失败"),
                )
            }
        }

        // ── 4. 会话列表（序 = updatedAt 降序，与 store.list() 同一条）───────────
        get(AGENT_SESSIONS) {
            call.respond(ApiEnvelope.ok(AgentSessionsData(agent.sessions().map { it.dto() })))
        }

        // ── 5. 新建会话 ──────────────────────────────────────────────────────
        post(AGENT_SESSIONS) {
            // 请求体整个不给（或不是 JSON）= 用默认标题；给了 `title` 就用它（空串也算没给）
            val body = runCatching { call.receiveOrNull<AgentSessionCreateBody>() }.getOrNull()
            val session = agent.createSession(body?.title)
            call.respond(ApiEnvelope.ok(AgentSessionData(session.dto())))
        }

        // ── 6. 单条会话的内容（**不在这条会话**答 200 + null，见契约 §5.11）────
        get("$AGENT_SESSIONS/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) return@get call.respondBadRequestMessage("id 必填（会话 id，取自 /api/agent/sessions）")
            val convo = agent.conversation(id)
            call.respond(
                ApiEnvelope.ok(
                    AgentConversationData(
                        conversation = convo?.let {
                            AgentConversationBody(
                                messages = it.messages.map { m -> m.dto() },
                                lastTotalTokens = it.lastTotalTokens,
                                contextExhausted = it.contextExhausted,
                            )
                        },
                    ),
                ),
            )
        }

        // ── 7. 改名（对不存在的 id 是空操作，照 store.rename 的原样）───────────
        post("$AGENT_SESSIONS/{id}/rename") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) return@post call.respondBadRequestMessage("id 必填（会话 id，取自 /api/agent/sessions）")
            val body = call.receiveOrNull<AgentRenameBody>()
                ?: return@post call.respondBadRequestMessage(AGENT_RENAME_BODY_MESSAGE)
            val title = body.title?.trim().orEmpty()
            if (title.isEmpty()) return@post call.respondBadRequestMessage("title 必填（新标题）")
            agent.renameSession(id, title, body.lock ?: true)
            call.respond(ApiEnvelope.ok(AgentOkData(message = "已改名")))
        }

        // ── 8. 删除（幂等）────────────────────────────────────────────────────
        post("$AGENT_SESSIONS/{id}/delete") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) return@post call.respondBadRequestMessage("id 必填（会话 id，取自 /api/agent/sessions）")
            agent.deleteSession(id)
            call.respond(ApiEnvelope.ok(AgentOkData(message = "已删除")))
        }

        // ── 9. 记忆 ──────────────────────────────────────────────────────────
        get(AGENT_MEMORY) {
            call.respond(
                ApiEnvelope.ok(
                    AgentMemoryData(agent.memoryItems().map { AgentMemoryItem(it.first, it.second) }),
                ),
            )
        }

        // ── 10. 忘掉一条（message = store 的原话）──────────────────────────────
        post("$AGENT_MEMORY/forget") {
            val body = call.receiveOrNull<AgentMemoryForgetBody>()
                ?: return@post call.respondBadRequestMessage(AGENT_FORGET_BODY_MESSAGE)
            val key = body.key?.trim().orEmpty()
            if (key.isEmpty()) return@post call.respondBadRequestMessage("key 必填（要忘掉的那一条偏好）")
            val existed = agent.memoryItems().any { it.first == key }
            val message = agent.forgetMemory(key)
            call.respond(ApiEnvelope.ok(AgentOkData(ok = existed, message = message)))
        }

        // ── 11. 清空记忆 ──────────────────────────────────────────────────────
        post("$AGENT_MEMORY/clear") {
            agent.clearMemory()
            call.respond(ApiEnvelope.ok(AgentOkData(message = "偏好已清空")))
        }

        // ── 12. 一轮对话（SSE；开门阶段的失败仍是信封）──
        post(AGENT_CHAT) {
            val body = call.receiveOrNull<AgentChatBody>()
                ?: return@post call.respondBadRequestMessage(AGENT_CHAT_BODY_MESSAGE)
            val text = body.text?.trim().orEmpty()
            val sessionId = body.sessionId?.trim().orEmpty()
            if (sessionId.isEmpty()) return@post call.respondBadRequestMessage("sessionId 必填（取自 /api/agent/sessions）")
            if (text.isEmpty()) return@post call.respondBadRequestMessage("text 必填（这一轮要发给模型的话）")
            // 顺序：形状 → 会话在不在(404) → 配没配 key(409) → 有没有一轮在跑(409) → 上下文耗尽(409)
            val convo = agent.conversation(sessionId)
                ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    ApiEnvelope<Unit>(ApiErrors.NOT_FOUND, message = "会话不存在：$sessionId"),
                )
            if (!agent.keyConfigured()) return@post call.respondKeyMissing()
            if (!agent.tryBeginRun(sessionId)) return@post call.respondAgentBusy()
            if (convo.contextExhausted) {
                agent.endRun()
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiEnvelope<Unit>(ApiErrors.ACTION_REJECTED, message = AGENT_CONTEXT_EXHAUSTED_MESSAGE),
                )
            }
            try {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                // 反代别缓冲这条流
                call.response.header("x-accel-buffering", "no")
                call.respondBytesWriter(AGENT_SSE_CONTENT_TYPE, HttpStatusCode.OK) {
                    agent.runTurn(sessionId, text) { frame ->
                        writeStringUtf8(frame)
                        flush()
                    }
                }
            } finally {
                agent.endRun()
            }
        }

        // ── 13. 停止（幂等：没有在跑也 200）────────────────────────────────────
        post(AGENT_STOP) {
            val body = call.receiveOrNull<AgentStopBody>()
            val asked = body?.sessionId?.trim().orEmpty()
            val stopped = agent.stop()
            val running = agent.runningSession()
            val target = if (asked.isEmpty() || asked == running) stopped else false
            call.respond(
                ApiEnvelope.ok(
                    AgentOkData(
                        ok = target,
                        message = if (target) "已请求停止" else "现在没有在跑的生成",
                    ),
                ),
            )
        }

        // ── 14. 状态（有没有一轮在跑 / 是哪个会话）────────────────────────────
        get(AGENT_STATUS) {
            call.respond(
                ApiEnvelope.ok(AgentStatusData(running = agent.running, sessionId = agent.runningSession())),
            )
        }
    }
}

/** 没配 API Key：`409` + 指路文案（**不静默失败**，见本文件 KDoc 第 2 条）。 */
private suspend fun ApplicationCall.respondKeyMissing() = respond(
    HttpStatusCode.Conflict,
    ApiEnvelope<Unit>(
        ApiErrors.ACTION_REJECTED,
        message = "还没配置 API Key：请先在运行 serve 的那台机器上设置（POST $AGENT_CONFIG_PATH 的 apiKey），" +
            "浏览器这一端不保存 Key。",
    ),
)

/** 已经有一轮在跑：`409` + 一句中文（Android 是静默忽略，这里有意更清楚）。 */
private suspend fun ApplicationCall.respondAgentBusy() = respond(
    HttpStatusCode.Conflict,
    ApiEnvelope<Unit>(ApiErrors.ACTION_REJECTED, message = "上一轮还在生成，先点停止或等它结束"),
)

/** `GET /api/agent/config` 的 `data`。 */
@kotlinx.serialization.Serializable
internal data class AgentConfigData(
    val provider: String,
    val model: String,
    val effectiveModel: String,
    val baseUrl: String,
    val assistantName: String,
    val searchEngine: String,
    val thinkingEnabled: Boolean,
    val reasoningEffort: String,
    val showReasoning: Boolean,
    val keyConfigured: Boolean,
    val toolAllowlist: List<String>,
    val honored: List<String>,
    val canEditKey: Boolean,
)

/** `{ok, message}` 那一类形状（先例是 `/api/library/action`）。 */
@kotlinx.serialization.Serializable
internal data class AgentOkData(val ok: Boolean = true, val message: String)

/** `POST /api/agent/models` 的 `data`：已去重升序的型号列表。 */
@kotlinx.serialization.Serializable
internal data class AgentModelsData(val models: List<String>)

/** `GET /api/agent/sessions` 的 `data`。 */
@kotlinx.serialization.Serializable
internal data class AgentSessionsData(val sessions: List<AgentSessionDto>)

/** `POST /api/agent/sessions` 与其它返回单条会话的端点的 `data`。 */
@kotlinx.serialization.Serializable
internal data class AgentSessionData(val session: AgentSessionDto)

/** 一行会话。 */
@kotlinx.serialization.Serializable
internal data class AgentSessionDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val locked: Boolean,
)

/** `GET /api/agent/sessions/{id}` 的 `data`：`conversation` 为 `null` = **这条会话不在了**。 */
@kotlinx.serialization.Serializable
internal data class AgentConversationData(val conversation: AgentConversationBody?)

/** 一条会话的内容。`messages` 是**落盘形状**（浏览器照它重建气泡），`llmHistory` **不投影**。 */
@kotlinx.serialization.Serializable
internal data class AgentConversationBody(
    val messages: List<StoredMessageDto>,
    val lastTotalTokens: Long?,
    val contextExhausted: Boolean,
)

/** 落盘消息的对外形状：同 [StoredMessage]，多一格 `images`。 */
@kotlinx.serialization.Serializable
internal data class StoredMessageDto(
    val role: String,
    val content: String,
    val nav: List<List<String>> = emptyList(),
    val widgets: List<StoredWidget>? = emptyList(),
    val reasoningContent: String? = null,
    val timestamp: Long? = null,
    val toolError: String? = null,
)

/** `GET /api/agent/memory` 的 `data`：`__order` 那个内部键**不在这条形状里**。 */
@kotlinx.serialization.Serializable
internal data class AgentMemoryData(val items: List<AgentMemoryItem>)

/** 一条记忆。 */
@kotlinx.serialization.Serializable
internal data class AgentMemoryItem(val key: String, val value: String)

/** `GET /api/agent/status` 的 `data`（可选端点 P2，但本批就端出来了）。 */
@kotlinx.serialization.Serializable
internal data class AgentStatusData(val running: Boolean, val sessionId: String?)

// ── 请求体 ────────────────────────────────────────────────────────────────

/** `POST /api/agent/config` 的请求体：**整份覆盖**，只有 `apiKey` 可以不给（= 不动那一格）。 */
@kotlinx.serialization.Serializable
internal data class AgentConfigBody(
    val provider: String? = null,
    val model: String? = null,
    val baseUrl: String? = null,
    val assistantName: String? = null,
    val searchEngine: String? = null,
    val thinkingEnabled: Boolean? = null,
    val reasoningEffort: String? = null,
    val showReasoning: Boolean? = null,
    val apiKey: String? = null,
)

/** `POST /api/agent/models` 的请求体（`baseUrl` 缺省 = 用存下来的那一格）。 */
@kotlinx.serialization.Serializable
internal data class AgentModelsBody(val provider: String? = null, val baseUrl: String? = null)

/** `POST /api/agent/sessions` 的请求体（整个不给 = 默认标题）。 */
@kotlinx.serialization.Serializable
internal data class AgentSessionCreateBody(val title: String? = null)

/** `POST /api/agent/sessions/{id}/rename` 的请求体（`lock` 缺省 = `true`，与 store 的默认同）。 */
@kotlinx.serialization.Serializable
internal data class AgentRenameBody(val title: String? = null, val lock: Boolean? = null)

/** `POST /api/agent/memory/forget` 的请求体。 */
@kotlinx.serialization.Serializable
internal data class AgentMemoryForgetBody(val key: String? = null)

/** `POST /api/agent/chat` 的请求体。 */
@kotlinx.serialization.Serializable
internal data class AgentChatBody(val sessionId: String? = null, val text: String? = null)

/** `POST /api/agent/stop` 的请求体（整个不给 = 停正在跑的那一轮）。 */
@kotlinx.serialization.Serializable
internal data class AgentStopBody(val sessionId: String? = null)

// ── 映射与小常量 ──────────────────────────────────────────────────────────

private fun AgentSession.dto() = AgentSessionDto(id, title, createdAt, updatedAt, locked)

private fun StoredMessage.dto() = StoredMessageDto(
    role = role,
    content = content,
    nav = nav,
    widgets = widgets,
    reasoningContent = reasoningContent,
    timestamp = timestamp,
    toolError = toolError,
)

/** 端能力那几档：[com.xjtu.toolbox.agent.AgentCapability] 里 serve / 浏览器真会照着办的那些。 */
private val AGENT_HONORED: List<String> = emptyList()

/** `reasoningEffort` 认得的写法：本地那四档 + DeepSeek 的别名（`minimal` / `medium` / `xhigh`）。 */
private val AGENT_REASONING_EFFORTS: Set<String> =
    (AgentConfig.REASONING_EFFORTS + listOf("minimal", "medium", "xhigh")).toSet()

private const val AGENT_CONFIG_BODY_MESSAGE =
    "请求体需要 JSON：{\"provider\":\"…\",\"model\":\"…\",\"baseUrl\":\"…\",\"assistantName\":\"…\"," +
        "\"searchEngine\":\"…\",\"thinkingEnabled\":true,\"reasoningEffort\":\"auto\",\"showReasoning\":true,\"apiKey\":\"…\"}"

private const val AGENT_MODELS_BODY_MESSAGE =
    "请求体需要 JSON：{\"provider\":\"deepseek\",\"baseUrl\":\"…\"}"

private const val AGENT_RENAME_BODY_MESSAGE = "请求体需要 JSON：{\"title\":\"…\",\"lock\":true}"

private const val AGENT_FORGET_BODY_MESSAGE = "请求体需要 JSON：{\"key\":\"…\"}"

private const val AGENT_CHAT_BODY_MESSAGE = "请求体需要 JSON：{\"sessionId\":\"…\",\"text\":\"…\"}"

/** `/api/agent` 那一族的路由段与各端点路径。 */
internal const val AGENT_SEGMENT = "agent"
internal const val AGENT_PATH = "$API_PREFIX/$AGENT_SEGMENT"
internal const val AGENT_CONFIG = "config"
internal const val AGENT_MODELS = "models"
internal const val AGENT_SESSIONS = "sessions"
internal const val AGENT_MEMORY = "memory"
internal const val AGENT_CHAT = "chat"
internal const val AGENT_STOP = "stop"
internal const val AGENT_STATUS = "status"

internal const val AGENT_CONFIG_PATH = "$AGENT_PATH/$AGENT_CONFIG"
internal const val AGENT_MODELS_PATH = "$AGENT_PATH/$AGENT_MODELS"
internal const val AGENT_SESSIONS_PATH = "$AGENT_PATH/$AGENT_SESSIONS"
internal const val AGENT_MEMORY_PATH = "$AGENT_PATH/$AGENT_MEMORY"
internal const val AGENT_CHAT_PATH = "$AGENT_PATH/$AGENT_CHAT"
internal const val AGENT_STOP_PATH = "$AGENT_PATH/$AGENT_STOP"
internal const val AGENT_STATUS_PATH = "$AGENT_PATH/$AGENT_STATUS"

/** 某条会话的路径（`GET /api/agent/sessions/{id}`）。 */
internal fun agentSessionPath(id: String) = "$AGENT_SESSIONS_PATH/$id"

/** 改名路径（`POST /api/agent/sessions/{id}/rename`）。 */
internal fun agentSessionRenamePath(id: String) = "$AGENT_SESSIONS_PATH/$id/rename"

/** 删除路径（`POST /api/agent/sessions/{id}/delete`）。 */
internal fun agentSessionDeletePath(id: String) = "$AGENT_SESSIONS_PATH/$id/delete"

/** 忘掉一条记忆的路径（`POST /api/agent/memory/forget`）。 */
internal const val AGENT_MEMORY_FORGET_PATH = "$AGENT_MEMORY_PATH/forget"

/** 清空记忆的路径（`POST /api/agent/memory/clear`）。 */
internal const val AGENT_MEMORY_CLEAR_PATH = "$AGENT_MEMORY_PATH/clear"

