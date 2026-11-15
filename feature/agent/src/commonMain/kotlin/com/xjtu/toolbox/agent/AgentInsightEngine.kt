package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.nowOnCampus
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 小岱卡的实现：和对话同一份配置、工具、画像、记忆，跑一轮不流式的调度循环，只收一个 JSON 对象。
 *
 * 结果按「卡片 id + 输入」缓存在本机（按账号分开），TTL 内同样的输入不再调模型。
 * 一张卡同时只跑一轮（页面反复进出不会叠请求）。
 */
class AgentInsightEngine(
    private val host: AgentHost,
    private val onAsk: (prompt: String, snapshot: String?) -> Unit = { _, _ -> },
) : AgentInsights {

    override val available: Boolean get() = true

    private val store get() = keyValueStore(STORE + AccountContext.safeSuffix())

    private val locks = HashMap<String, Mutex>()
    private val locksGuard = Mutex()

    private suspend fun lockFor(id: String) = locksGuard.withLock { locks.getOrPut(id) { Mutex() } }

    private fun cacheKey(spec: InsightSpec, input: String) = "${spec.id}:${input.length}:${input.hashCode()}"

    override fun cached(spec: InsightSpec, input: String): InsightOutcome.Ok? {
        val raw = store.getString(cacheKey(spec, input)) ?: return null
        val obj = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val at = obj["at"]?.jsonPrimitive?.longOrNull ?: return null
        val json = obj["out"] as? JsonObject ?: return null
        val log = runCatching { obj["tools"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return InsightOutcome.Ok(json, at, cached = true, toolLog = log)
    }

    override suspend fun run(spec: InsightSpec, input: String, force: Boolean, onProgress: (String) -> Unit): InsightOutcome {
        val config = host.loadConfig()
        if (!config.isConfigured) return InsightOutcome.Unavailable
        return lockFor(spec.id).withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            cached(spec, input)?.takeIf { !force && now - it.at < spec.ttlMs }?.let { return@withLock it }
            try {
                val log = StringBuilder()
                val json = withContext(host.io) { generate(spec, input, config, log, onProgress) }
                    ?: return@withLock InsightOutcome.Failed("小岱没给出能用的结果")
                store.putString(cacheKey(spec, input), buildJsonObject {
                    put("at", now)
                    put("out", json)
                    if (log.isNotEmpty()) put("tools", log.toString())
                }.toString())
                InsightOutcome.Ok(json, now, cached = false, toolLog = log.toString())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                InsightOutcome.Failed(e.message ?: "小岱这会儿没连上")
            }
        }
    }

    override fun ask(prompt: String, snapshot: String?) = onAsk(prompt, snapshot)

    private suspend fun generate(
        spec: InsightSpec,
        input: String,
        config: AgentConfig,
        log: StringBuilder,
        onProgress: (String) -> Unit,
    ): JsonObject? {
        val all = host.tools(config)
        val tools = FilteredAgentTools(all, spec.tools, log)
        val system = AgentPrompt.build(
            assistantName = config.effectiveName,
            userContext = all.userContext(allowNetwork = true),
            memoryBlock = all.memoryBlock(),
        ) + "\n\n" + INSIGHT_RULES + "\n\n" + spec.instruction.trim()
        val now = nowOnCampus()
        val text = "[现在：${now.date} ${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}]\n$input"
        val images = if (AgentVision.supportsVision(config)) spec.images() else emptyList()
        val user: JsonElement = if (images.isEmpty()) JsonPrimitive(text) else buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
            images.forEach { uri ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", uri) })
                })
            }
        }
        val messages = mutableListOf<JsonElement>(
            buildJsonObject { put("role", "system"); put("content", system) },
            buildJsonObject { put("role", "user"); put("content", user) },
        )
        val reply = AgentRunner(tools, host.transport()).run(
            messages, config.copy(thinkingEnabled = spec.thinking && config.thinkingEnabled),
            onToolCall = { onProgress(agentToolLabel(it)) },
            onDelta = { onProgress("整理结果…") },
            onReasoningDelta = { onProgress("思考中…") },
        )
        return parseInsightJson(reply)
    }

    companion object {
        const val STORE = "agent_insight"

        private val INSIGHT_RULES = """
这次不是聊天：你在 App 的某个页面里给用户写一块卡片。只输出一个 JSON 对象，不要 Markdown 代码块，不要 JSON 以外的任何文字。
文案简短、平实、客观。先说明结果，必要时给出下一步；避免套话、夸张语气、重复解释和实现细节。数据以给定内容和工具结果为准，缺失信息保持未知。
        """.trimIndent()
    }
}

/** 从模型回复里抠出那个 JSON 对象：容忍 ```json 围栏和前后多余的话。 */
fun parseInsightJson(reply: String): JsonObject? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { AppJson.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull()
}

/**
 * 只把 [allow] 里的工具交给模型；卡片画不了数据卡，runner 收下的控件不用。
 * 每次调用和结果记进 [log]（每条、总长都截断），给「接着问小岱」用。
 */
private class FilteredAgentTools(
    private val inner: AgentTools,
    private val allow: Set<String>,
    private val log: StringBuilder,
) : AgentTools {
    override val toolDefinitions: String = runCatching {
        JsonArray(
            (AppJson.parseToJsonElement(inner.toolDefinitions) as JsonArray).filter { el ->
                el.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content in allow
            },
        ).toString()
    }.getOrDefault("[]")

    override fun rateLimitKeyOf(toolName: String): String? = inner.rateLimitKeyOf(toolName)

    override fun drainImages(): List<String> = inner.drainImages()

    override fun drainWidgets(): List<AgentWidget> = inner.drainWidgets()

    override suspend fun execute(name: String, argsJson: String): String {
        if (name !in allow) return ToolReply.notFound("tool", name)
        val result = inner.execute(name, argsJson)
        if (log.length < LOG_MAX) {
            log.append("- ").append(name).append(' ').append(argsJson.ifBlank { "{}" }).append('\n')
            log.append(result.take(LOG_PER_CALL).trim()).append("\n\n")
        }
        return result
    }

    private companion object {
        const val LOG_PER_CALL = 3000
        const val LOG_MAX = 9000
    }
}
