package com.xjtu.toolbox.agent

import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.stringValue
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible function calling 调度循环。
 *
 * 安全限制：
 * - 每个问题最多 [TOOL_CALL_FUSE] 次工具调用，到了就不再给工具，逼模型直接作答
 * - 同一站点的两次工具调用之间隔开 800ms（`SAME_SITE_MIN_INTERVAL_MS`），不同站点互不影响
 * - 会话失效异常（[SessionExpiredFailure]）直接上抛，不让 LLM 自行重试
 *
 * 多轮会话：调用方负责维护 messages（含 system prompt），run() 在原数组上追加
 * assistant/tool 消息，返回后 messages 即为完整历史，可供下一轮复用。
 */
class AgentRunner(
    private val tools: AgentTools,
    private val transport: AgentTransport,
) {
    companion object {
        /** 同一站点两次工具调用的最小间隔。不同站点互不影响。 */
        private const val SAME_SITE_MIN_INTERVAL_MS = 800L

        /**
         * 单条工具结果默认上限。课表/通知列表用这个就够；
         * 搜索列表和网页正文另开更高预算，避免搜到了却读不了中间段落。
         */
        private const val DEFAULT_TOOL_RESULT_CHARS = 4000
        private const val SEARCH_TOOL_RESULT_CHARS = 8000
        private const val FETCH_TOOL_RESULT_CHARS = 12_000

        /** public 而不是 internal：`:app` 的 `AgentWebTest` 要调它。 */
        fun toolResultCap(toolName: String): Int = when (toolName) {
            "web_fetch" -> FETCH_TOOL_RESULT_CHARS
            "read_zyxf_file", "get_lms_activity" -> 20_000
            "web_search", "search_school_courses" -> SEARCH_TOOL_RESULT_CHARS
            else -> DEFAULT_TOOL_RESULT_CHARS
        }

        /**
         * 超长结果保留开头和结尾、省略中间，**按整行截**并写明省略了几行。
         * 以前按字数从中间砍一刀，常把一行（一门课、一条流水）劈成两半，模型也不知道少了多少条。
         * 只有一行却超长（网页正文）时退回按字截。
         */
        fun capToolResult(result: String, toolName: String = ""): String {
            val maxChars = toolResultCap(toolName)
            if (result.length <= maxChars) return result
            val lines = result.split('\n')
            if (lines.size < 3) {
                val marker = "\n…（已省略中间部分，原长 ${result.length} 字）…\n"
                val budget = maxChars - marker.length
                val head = (budget * 0.6).toInt().coerceAtLeast(200)
                return result.take(head) + marker + result.takeLast((budget - head).coerceAtLeast(200))
            }
            val budget = maxChars - 40
            val headBudget = (budget * 0.6).toInt()
            var used = 0
            var headCount = 0
            while (headCount < lines.size && used + lines[headCount].length + 1 <= headBudget) {
                used += lines[headCount].length + 1
                headCount++
            }
            var tailCount = 0
            while (tailCount < lines.size - headCount - 1 &&
                used + lines[lines.size - 1 - tailCount].length + 1 <= budget
            ) {
                used += lines[lines.size - 1 - tailCount].length + 1
                tailCount++
            }
            val omitted = lines.size - headCount - tailCount
            val marker = "…（已省略中间 $omitted 行）…"
            return (lines.take(headCount) + marker + lines.takeLast(tailCount)).joinToString("\n")
        }

        /**
         * 一个问题的工具调用保险丝。不是给模型的预算——不写进提示词、不在设置里露出，
         * 该查几次由模型自己判断；这道只防它陷进死循环（反复调同一个失败的工具），
         * 白烧用户的 API 额度。正常问题碰不到。
         */
        const val TOOL_CALL_FUSE = 20


        /** 只在熔断那一刻告诉模型，平时不提次数，免得它束手束脚。 */
        fun remainingToolHint(used: Int): String? =
            if (used >= TOOL_CALL_FUSE) "\n（工具次数已用尽）" else null
    }

    /** 站点 -> 上次调用时刻，用于按站点限流。 */
    private val lastCallAt = mutableMapOf<String, Long>()

    /** 模型选中展示的卡片和页面入口。 */
    val shownWidgets = mutableListOf<AgentWidget>()
    val shownTools = mutableListOf<String>()


    /**
     * 执行一次用户消息的 agent 循环。
     *
     * @param messages 完整历史数组（已包含 system prompt 和本轮 user 消息）。
     *                 函数会将本轮的 assistant/tool 消息就地追加到此数组，
     *                 调用方持有同一引用，下次调用时历史自动延续。
     */
    suspend fun run(
        messages: MutableList<JsonElement>,
        config: AgentConfig,
        onToolCall: (name: String) -> Unit = {},
        onToolResult: (name: String, success: Boolean, errorMessage: String?) -> Unit = { _, _, _ -> },
        onDelta: suspend (String) -> Unit = {},
        onReasoningDelta: suspend (String) -> Unit = {},
        onUsage: (Long) -> Unit = {}
    ): String {
        val baseToolDefs = AppJson.parseToJsonElement(tools.toolDefinitions).jsonArray
            .map { AgentWidgetPresentation.queryDefinition(it.jsonObject) }
        data class Snapshot(val tool: String, val widgets: List<AgentWidget>)
        val snapshots = linkedMapOf<String, Snapshot>()
        val displayedSnapshots = mutableMapOf<String, List<AgentWidget>>()
        fun present(id: String, widgets: List<AgentWidget>) {
            displayedSnapshots[id]?.let { old -> shownWidgets.removeAll { current -> old.any { it === current } } }
            displayedSnapshots[id] = widgets
            shownWidgets += widgets
            snapshots[id]?.tool?.let { if (it !in shownTools) shownTools += it }
        }
        var toolCallCount = 0
        val assembled = StringBuilder()
        var lengthContinues = 0

        while (true) {
            val toolDefs = JsonArray(baseToolDefs + if (snapshots.isEmpty()) emptyList() else
                listOf(AgentWidgetPresentation.toolDefinition(snapshots.keys)))
            // 保险丝烧了，这一轮不带 tools，逼模型直接作答
            val allowTools = toolCallCount < TOOL_CALL_FUSE && toolDefs.isNotEmpty()
            val reqBody = buildJsonObject {
                put("model", config.effectiveModel)
                put("messages", messagesForProvider(messages, config))
                put("stream", true)
                if (config.provider == AgentConfig.PROVIDER_DEEPSEEK) {
                    put("stream_options", buildJsonObject { put("include_usage", true) })
                    // 按官方文档：开关是 `thinking.type`，强度是顶层 `reasoning_effort`（none / low / high / max，
                    // none 即关思考）。`thinking` 里只有 type 一个字段。自动档不发强度，服务端默认 high。
                    put("thinking", buildJsonObject {
                        put("type", if (config.thinkingEnabled) "enabled" else "disabled")
                    })
                    when {
                        !config.thinkingEnabled -> put("reasoning_effort", "none")
                        config.reasoningEffort != AgentConfig.REASONING_AUTO -> put("reasoning_effort", config.reasoningEffort)
                    }
                }
                if (allowTools) {
                    put("tools", toolDefs)
                    put("tool_choice", "auto")
                }
            }

            val partialContent = StringBuilder()
            val response = try {
                transport.stream(config, reqBody, { fragment -> partialContent.append(fragment); onDelta(fragment) }, onReasoningDelta)
            } catch (failure: Exception) {
                val marker = if (failure is kotlinx.coroutines.CancellationException) "[用户打断]" else "[网络中断]"
                // 当前这一段思考尚未完成，不伪造 reasoning_content；已提交的完整轮次原样保留。
                messages.add(buildJsonObject {
                    put("role", "assistant")
                    put("content", partialContent.toString().trimEnd().let { if (it.isEmpty()) marker else "$it\n\n$marker" })
                })
                throw failure
            }
            val sr = if (response.finishReason == "aborted") AgentStreamResult(response.content, "", emptyList(), response.finishReason, response.totalTokens) else response
            sr.totalTokens?.let(onUsage)

            // 思考模式下更常见的是思维链先用完了输出上限（默认 64K），不一定是上下文满了
            if (sr.finishReason == "length" && sr.content.isBlank() && sr.toolCalls.isEmpty())
                return "回复被截断：输出长度达到上限（思考过长或上下文已满）。可以把问题拆小、调低思考强度，或新开对话。"
            if (sr.finishReason == "aborted" && sr.content.isBlank() && sr.toolCalls.isEmpty()) {
                messages.add(buildJsonObject { put("role", "assistant"); put("content", "[网络中断]") })
                return "[网络中断]"
            }
            if (sr.finishReason == "content_filter" && sr.content.isBlank())
                return "回复被内容过滤拦截。"
            if (sr.finishReason == "insufficient_system_resource" && sr.content.isBlank())
                return "模型推理资源暂时不足，请稍后重试。"

            // 组装 assistant 消息写回历史（保持 OpenAI 结构，供续聊）
            val assistantMsg = buildJsonObject {
                put("role", "assistant")
                put("content", if (sr.finishReason == "aborted") sr.content + "\n\n[网络中断]" else sr.content)
                if (sr.reasoningContent.isNotBlank()) {
                    put("reasoning_content", sr.reasoningContent)
                }
                if (sr.toolCalls.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        sr.toolCalls.forEach { tc ->
                            add(buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
            }

            // 无工具调用（或预算用尽）：本轮即最终回答。
            // 兼容 OpenAI-compatible 后端：有些会在存在 tool_calls 时仍返回 finish_reason=stop，
            // 因此只看实体 toolCalls 是否非空，不迷信 finishReason。
            if (!allowTools || sr.toolCalls.isEmpty()) {
                assembled.append(sr.content)
                // 有正文却被 length 截断时，把已写部分入历史并续写，尽量拿到完整结尾。
                if (sr.finishReason == "length" && sr.content.isNotBlank() && lengthContinues < 2) {
                    messages.add(assistantMsg)
                    messages.add(buildJsonObject {
                        put("role", "user")
                        put("content", "（系统）上一条回复因长度被截断。从断处接着写，不重复，不解释。")
                    })
                    lengthContinues++
                    continue
                }
                messages.add(assistantMsg)
                // 只提示用户，不写进历史
                if (sr.finishReason == "aborted") assembled.append("\n\n[网络中断]")
                return assembled.toString().ifBlank { "（无回复）" }
            }

            // 原子提交：先把所有 tool 结果算好，再「assistant(tool_calls) + 全部 tool」一起入历史。
            // 若中途被取消/抛会话失效，则什么都不写——历史不会留下"有 tool_calls 却没 tool 回应"的残体，
            val toolResults = ArrayList<JsonObject>(sr.toolCalls.size)
            var activeCallIndex = 0
            var executingCall = false
            try {
            for ((callIndex, tc) in sr.toolCalls.withIndex()) {
                activeCallIndex = callIndex
                executingCall = false
                onToolCall(tc.name)
                // 限流按**站点**算，不再全局一刀切。
                // 原来是 `if (toolCallCount > 0) delay(1000L)`：不管这次打的是哪个系统都先等 1 秒。
                // 而连环问（"几点下课 / 哪有空教室 / 图书馆还有座"）恰恰打的是不同系统，
                // 互相之间没有任何限频理由，白白多等好几秒。登录侧的防刷已由 CasGate 全局串行 +
                // 失败退避覆盖，这里只需防"同一站点被连续猛打"。
                tools.rateLimitKeyOf(tc.name)?.let { site ->
                    val last = lastCallAt[site]
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (last != null && now - last < SAME_SITE_MIN_INTERVAL_MS) {
                        delay(SAME_SITE_MIN_INTERVAL_MS - (now - last))
                    }
                    lastCallAt[site] = Clock.System.now().toEpochMilliseconds()
                }
                executingCall = true
                val toolResult = runCatching {
                    if (tc.name == AgentWidgetPresentation.TOOL) {
                        val args = AppJson.parseToJsonElement(tc.arguments).jsonObject
                        val id = args.argStr("result_id") ?: error("缺少 result_id")
                        val snapshot = snapshots[id] ?: error("本轮不存在该结果，请先查询")
                        val selected = AgentWidgetPresentation.apply(snapshot.widgets, AgentWidgetPresentation.options(tc.arguments))
                        present(id, selected)
                        if (selected.isEmpty()) "page_entry: shown; tool: ${snapshot.tool}"
                        else AgentWidgetPresentation.receipt(selected)
                    } else tools.execute(tc.name, tc.arguments)
                }
                // 查询保留快照，展示在拿到结果后单独决定。
                val made = if (tc.name == AgentWidgetPresentation.TOOL) emptyList() else tools.drainWidgets()
                var result: String
                val toolErrorMsg: String?
                if (toolResult.isSuccess) {
                    result = toolResult.getOrThrow() as String
                    toolErrorMsg = null
                    if (tc.name != AgentWidgetPresentation.TOOL && (made.isNotEmpty() || AgentWidgetPresentation.supportsResult(tc.name))) {
                        snapshots[tc.id] = Snapshot(tc.name, made)
                        if (made.isNotEmpty()) result += "\ncard_rows (original, 1-based):\n" + AgentWidgetPresentation.catalog(made)
                        result += "\nresult_id: ${tc.id}\npresentation: ready; not_shown; type: ${if (made.isEmpty()) "page_entry" else "data_card"}"
                    }
                    // 兼容旧历史中的 show 参数；新工具表只提供后置展示。
                    if (tc.name != AgentWidgetPresentation.TOOL && ToolShow.requested(tc.arguments)) {
                        val presentation = runCatching { AgentWidgetPresentation.apply(made, AgentWidgetPresentation.options(tc.arguments)) }
                        if (presentation.isSuccess) {
                            if (made.isNotEmpty()) {
                                present(tc.id, presentation.getOrThrow())
                                result += "\n" + AgentWidgetPresentation.receipt(presentation.getOrThrow())
                            } else if (tc.name !in shownTools) shownTools += tc.name
                        } else result += "\ncard_error: ${presentation.exceptionOrNull()?.message}"
                    }
                } else {
                    val e = toolResult.exceptionOrNull()
                    // 用户点了停止：取消不是工具出错，不能记成一次失败塞进历史，原样往上抛
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // `:app` 的 AuthExpiredException / `:data` 的 SessionExpiredException 都实现它
                    if (e is SessionExpiredFailure) throw e
                    result = "工具调用出错：${e?.message ?: "未知异常"}"
                    toolErrorMsg = e?.message ?: "未知异常"
                }
                onToolResult(tc.name, toolErrorMsg == null, toolErrorMsg)
                toolCallCount++
                toolResults.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("content", capToolResult(result, tc.name))
                })
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                val completedIds = toolResults.map { it["tool_call_id"]?.stringValue }.toSet()
                for ((index, pending) in sr.toolCalls.withIndex()) {
                    if (pending.id in completedIds) continue
                    toolResults.add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", pending.id)
                        put("content", if (index == activeCallIndex && executingCall)
                            "status: interrupted; reason: user_interrupt; execution: unknown" else
                            "status: not_started; reason: user_interrupt")
                    })
                }
                messages.add(assistantMsg)
                messages.addAll(toolResults)
                messages.add(buildJsonObject { put("role", "assistant"); put("content", "[用户打断]") })
                throw cancelled
            }
            remainingToolHint(toolCallCount)?.let { hint ->
                val last = toolResults.lastOrNull() ?: return@let
                toolResults[toolResults.lastIndex] = JsonObject(last + ("content" to JsonPrimitive(last["content"].stringValue + hint)))
            }
            // 工具读到的图（PDF 页面等）只能放在 user 消息里交给模型；模型不认图就在结果里说一声
            val images = tools.drainImages()
            val canSee = images.isNotEmpty() && AgentVision.supportsVision(config)
            if (images.isNotEmpty() && !canSee) {
                val last = toolResults.last()
                toolResults[toolResults.lastIndex] = JsonObject(
                    last + ("content" to JsonPrimitive(last["content"].stringValue + "\n（当前模型不能看图，这些页面没交给你）")),
                )
            }
            messages.add(assistantMsg)
            toolResults.forEach { messages.add(it) }
            if (canSee) messages.add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "（系统）上面工具读到的文档页面图：") })
                    images.forEach { uri ->
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", uri) })
                        })
                    }
                })
            })
        }
    }

    /**
     * 只有 OpenAI 官方删 `reasoning_content`（它不认这个字段）。自定义端点原样回传：思维链就是它自己返回的，
     * 而 DeepSeek 系模型带 tools 时要求历史思维链完整回传，删了会 400。
     */
    private fun messagesForProvider(messages: List<JsonElement>, config: AgentConfig): JsonArray {
        if (config.provider != AgentConfig.PROVIDER_OPENAI) return JsonArray(messages)
        return buildJsonArray {
            messages.forEach { el ->
                val src = el.jsonObject
                add(buildJsonObject {
                    src.entries.forEach { (key, value) ->
                        if (key != "reasoning_content") put(key, value)
                    }
                })
            }
        }
    }
}
