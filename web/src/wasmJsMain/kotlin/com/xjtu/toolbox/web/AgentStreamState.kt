package com.xjtu.toolbox.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.xjtu.toolbox.agent.AGENT_CONTEXT_TOKEN_LIMIT
import com.xjtu.toolbox.agent.AgentThinkingHost
import com.xjtu.toolbox.agent.AgentWidget
import com.xjtu.toolbox.agent.ChatMessage
import com.xjtu.toolbox.agent.StoredWidget
import com.xjtu.toolbox.agent.agentCompleteToolLabel
import com.xjtu.toolbox.agent.agentToolLabel
import com.xjtu.toolbox.agent.storedToWidget
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** 「SSE 帧 → 屏上的状态」这件事的本体（`docs/agent-web-spec.md` §4.2 的逐成员对应表）。 */
internal class AgentStreamState {

    /** 屏上的消息（`delta` / `reasoning` / `tool_call` / `tool_result` 都往这里写）。 */
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()

    /** 有没有一轮正在生成。 */
    var isLoading by mutableStateOf(false)
        private set

    /** 这一轮的失败文案（`null` = 没有）。 */
    var errorMessage by mutableStateOf<String?>(null)

    /** 上下文是否已达上限（**serve 判**、这一端只显示）。 */
    var contextExhausted by mutableStateOf(false)
        private set

    /** [contextExhausted] 刚刚由 `false` 变 `true` 的一次性标记（屏弹一次窗）。 */
    var contextExhaustedJustTriggered by mutableStateOf(false)
        private set

    /** 流式回答气泡的下标（首个 `delta` / `reasoning` 到达时创建；一次工具调用之后要新开一个）。 */
    private var streamIdx = -1

    /** 还没收尾的工具行（`tool_result` 对上最近那一条，照 `AgentViewModel` 的 `toolBubbleIndices`）。 */
    private val toolBubbleIndices = mutableListOf<Int>()

    /** MFA 那条气泡的下标（`mfa.pending=false` 时收尾）。 */
    private var mfaBubbleIdx = -1

    private var pendingWidgets: List<AgentWidget> = emptyList()
    private var pendingNav: List<Pair<String, String>> = emptyList()
    private val pendingContent = StringBuilder()
    private val pendingReasoning = StringBuilder()

    /** 加载态的唯一写入口：屏上的加载态与底栏思考动画共用同一个信号（照 `applyLoading`）。 */
    fun setLoading(loading: Boolean) {
        isLoading = loading
        AgentThinkingHost.isThinking = loading
    }

    fun consumeContextExhaustedTrigger(): Boolean {
        val value = contextExhaustedJustTriggered
        contextExhaustedJustTriggered = false
        return value
    }

    /** 这一轮开始：把用户那句话放上屏、清掉上一轮的残余、点亮「在生成」。 */
    fun beginTurn(userText: String) {
        messages.add(ChatMessage(role = "user", content = userText))
        resetTurn()
        errorMessage = null
        setLoading(true)
    }

    /** 把这一轮没答完的痕迹整段摘掉（照 `dropLastUserTurn` 的 UI 那一半）。 */
    fun dropLastUserTurn() {
        val idx = messages.indexOfLast { it.role == "user" }
        if (idx >= 0) {
            while (messages.size > idx) messages.removeAt(messages.lastIndex)
        }
    }

    /** 一帧一帧地写状态（[AgentFrame.Ping] 与 [AgentFrame.Unknown] 不写任何东西）。 */
    fun apply(frame: AgentFrame) {
        when (frame) {
            is AgentFrame.Delta -> {
                ensureStreamBubble()
                pendingContent.append(frame.text)
            }

            is AgentFrame.Reasoning -> {
                ensureStreamBubble()
                pendingReasoning.append(frame.text)
            }

            is AgentFrame.ToolCall -> {
                flush()
                streamIdx = -1
                messages.add(ChatMessage("tool_event", agentToolLabel(frame.name), isToolCall = true))
                toolBubbleIndices.add(messages.lastIndex)
            }

            is AgentFrame.ToolResult -> {
                val idx = toolBubbleIndices.removeLastOrNull() ?: return
                settleToolAt(idx, frame.success, frame.error)
            }

            is AgentFrame.Usage -> {
                // 只用来兜底：`done` 也带同一格（两处同一条判据，见类 KDoc 第 1 条）
                if (frame.totalTokens >= AGENT_CONTEXT_TOKEN_LIMIT) applyExhausted(true)
            }

            is AgentFrame.Mfa -> applyMfa(frame)

            is AgentFrame.Widgets -> pendingWidgets = frame.widgets.mapNotNull { storedToWidget(it) }

            is AgentFrame.Nav -> pendingNav = frame.routes

            is AgentFrame.Done -> applyDone(frame)

            is AgentFrame.Error -> applyError(frame.kind, frame.message)

            AgentFrame.Ping -> Unit

            is AgentFrame.Unknown -> Unit
        }
    }

    /** 把攒着的正文 / 思考落到气泡上（按 `STREAM_FLUSH_MS` 驱动，见调用处）。 */
    fun flush() {
        if (pendingContent.isEmpty() && pendingReasoning.isEmpty()) return
        if (streamIdx in messages.indices) {
            val bubble = messages[streamIdx]
            messages[streamIdx] = bubble.copy(
                content = bubble.content + pendingContent,
                reasoningContent = bubble.reasoningContent + pendingReasoning,
            )
        }
        pendingContent.setLength(0)
        pendingReasoning.setLength(0)
    }

    /** 流干净地关了：把残余落上屏、熄掉加载态。 */
    fun finishTurn() {
        flush()
        setLoading(false)
    }

    /** 「这一轮没跑完就被停了」——照 `AgentViewModel` 的取消路径：工具行标「已中断」、正文空的话写「（已停止）」。 */
    fun stopped() {
        flush()
        toolBubbleIndices.forEach { settleToolAt(it, false, "已中断") }
        toolBubbleIndices.clear()
        if (streamIdx in messages.indices) {
            val bubble = messages[streamIdx]
            messages[streamIdx] = bubble.copy(content = bubble.content.ifBlank { "（已停止）" })
        }
        streamIdx = -1
        setLoading(false)
    }

    /** 传输层的失败（开门阶段的信封 / 断线）。 */
    fun fail(message: String) {
        flush()
        errorMessage = message
        messages.add(ChatMessage("assistant", "出错了：$message"))
        setLoading(false)
    }

    /** 切走 / 新开 / 重读一条会话：把屏清空并复位（照 `switchSession` 那一套）。 */
    fun clear() {
        resetTurn()
        messages.clear()
        errorMessage = null
        contextExhausted = false
        contextExhaustedJustTriggered = false
        setLoading(false)
    }

    /** `GET /api/agent/sessions/{id}` 的 `conversation` → 一屏气泡（`null` = 这条会话不在了）。 */
    fun load(conversation: JsonObject?) {
        clear()
        if (conversation == null) return
        conversation.arr("messages").orEmpty().forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            messages.add(
                ChatMessage(
                    role = o["role"].safeString(),
                    content = o["content"].safeString(),
                    navSuggestions = o.arr("nav").orEmpty().mapNotNull { pair ->
                        val p = pair as? JsonArray ?: return@mapNotNull null
                        val label = p.getOrNull(0)?.safeString() ?: return@mapNotNull null
                        val route = p.getOrNull(1)?.safeString() ?: return@mapNotNull null
                        label to route
                    },
                    widgets = o.arr("widgets").orEmpty().mapNotNull { w ->
                        val wo = w as? JsonObject ?: return@mapNotNull null
                        storedToWidget(StoredWidget(wo["type"].safeString(), wo["json"].safeString()))
                    },
                    reasoningContent = o["reasoningContent"].safeString(),
                    // 落盘里那一格是 epoch 毫秒（契约 §5.11 有意偏离 ISO 的那一处）；缺了就用现在
                    timestamp = o.safeGet("timestamp")?.safeLong() ?: Clock.System.now().toEpochMilliseconds(),
                    // 落盘里的工具事件都是**已经收尾**的（`ToolCallNote` 按这个字段决定画不画转圈）
                    isToolCall = false,
                    toolError = o.safeGet("toolError")?.safeString(),
                ),
            )
        }
        // 上下文耗尽按落盘那一格原样接上（**不**触发弹窗：那是「刚刚变真」那一次的事）
        contextExhausted = conversation["contextExhausted"].safeBoolean()
        contextExhaustedJustTriggered = false
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun resetTurn() {
        streamIdx = -1
        toolBubbleIndices.clear()
        mfaBubbleIdx = -1
        pendingWidgets = emptyList()
        pendingNav = emptyList()
        pendingContent.setLength(0)
        pendingReasoning.setLength(0)
    }

    private fun ensureStreamBubble() {
        if (streamIdx < 0) {
            messages.add(ChatMessage("assistant", ""))
            streamIdx = messages.lastIndex
        }
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

    private fun applyMfa(frame: AgentFrame.Mfa) {
        if (frame.pending) {
            if (mfaBubbleIdx >= 0) return
            messages.add(
                ChatMessage(
                    role = "tool_event",
                    content = "需要登录验证：等待「${frame.siteName.orEmpty()}」短信验证码…",
                    isToolCall = true,
                ),
            )
            mfaBubbleIdx = messages.lastIndex
        } else if (mfaBubbleIdx in messages.indices) {
            messages[mfaBubbleIdx] = messages[mfaBubbleIdx].copy(isToolCall = false)
            mfaBubbleIdx = -1
        }
    }

    private fun applyDone(frame: AgentFrame.Done) {
        flush()
        if (streamIdx in messages.indices) {
            messages[streamIdx] = messages[streamIdx].copy(
                // 收尾那一句以服务端的 `done.content` 为准（与流式攒出来的是同一份正文）
                content = frame.content.ifBlank { messages[streamIdx].content },
                navSuggestions = pendingNav,
                widgets = pendingWidgets,
            )
        } else {
            messages.add(
                ChatMessage("assistant", frame.content, navSuggestions = pendingNav, widgets = pendingWidgets),
            )
        }
        streamIdx = -1
        pendingNav = emptyList()
        pendingWidgets = emptyList()
        applyExhausted(frame.contextExhausted)
    }

    private fun applyError(kind: String, message: String) {
        flush()
        if (kind == "session_expired") {
            // 与 `AgentViewModel` 的 `SessionExpiredFailure` 分支同一句文案
            messages.add(ChatMessage("assistant", message))
        } else {
            errorMessage = message
            messages.add(ChatMessage("assistant", "出错了：$message"))
        }
        setLoading(false)
    }

    /** 只在「未耗尽 → 耗尽」那一次置位（照 `AgentViewModel.onUsage` 里的 `wasExhausted` 判据）。 */
    private fun applyExhausted(exhausted: Boolean) {
        if (!exhausted || contextExhausted) return
        contextExhausted = true
        contextExhaustedJustTriggered = true
    }
}
