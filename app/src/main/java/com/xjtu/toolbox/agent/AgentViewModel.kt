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

    // LLM 多轮历史（含 system prompt + 所有轮次），与 messages（UI 专用）独立维护。
    // 保持历史稳定以利用 provider 端 prefix cache：system prompt 只在首轮写入一次。
    private var llmHistory = JsonArray()
    private var systemPromptAdded = false

    // AgentToolRegistry 保持在 ViewModel 级别，使 loginFailedAt 冷却状态跨消息保留
    private var tools: AgentToolRegistry? = null

    fun sendMessage(
        userText: String,
        config: AgentConfig,
        loginState: AppLoginState,
        context: Context
    ) {
        if (userText.isBlank() || isLoading) return
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
                        addProperty("content", AgentPrompt.build(LocalDate.now()))
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
                            "get_current_time"   -> "获取当前时间…"
                            "get_schedule"       -> "查询课表…"
                            "get_exam_schedule"  -> "查询考试安排…"
                            "get_empty_rooms"    -> "查询空教室…"
                            "get_attendance"     -> "查询考勤记录…"
                            else                 -> "调用工具 $name…"
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
                        "get_grades"                        -> "成绩单"     to "score_report"
                        "get_card_balance"                  -> "校园卡"     to "campus_card"
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
            }
        }
    }

    fun clearMessages() {
        messages.clear()
        llmHistory = JsonArray()
        systemPromptAdded = false
        errorMessage = null
        // tools 保留（loginFailedAt 冷却状态有价值），不在 clearMessages 时重置
    }
}
