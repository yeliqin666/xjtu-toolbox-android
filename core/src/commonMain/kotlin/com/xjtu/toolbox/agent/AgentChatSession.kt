package com.xjtu.toolbox.agent

import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 「一屏屁岱对话」这件事的**成员面**：屏（`AgentChat.kt` 的输入栏与对话列表、`AgentScreen.kt` 的
 * 会话抽屉与配置面板入口）真正用到的那十几个成员，一个字不加、一个字不减。
 *
 * ## 为什么要抽它
 *
 * 屏要在两个不同的驱动上跑：
 *  - Android / 桌面 —— [AgentViewModel]：**循环在本端**（工具层、传输层、落盘都在手边）；
 *  - 浏览器 —— `WebAgentSession`（D10 的 S-d）：循环在 **serve**，这一端只是「SSE 事件 → 状态」
 *    的消费者（`docs/agent-web-spec.md` §4.2 的逐成员对应表）。
 *
 * 两边的状态字段与动作**逐字同名同型**，屏不该认识其中哪一个。没有这条缝，浏览器那一端就得
 * 自己写一屏（丙口径当场破）——所以它是「同一屏、两种驱动」的前提，也是 S-d 的前置。
 *
 * ## 语义边界（写在这里，免得实现方猜）
 *
 * - [messages] 与 [sessions] 是 Compose 的**可观察列表**（`SnapshotStateList`）：屏按它重组，
 *   实现方往里写就等于上屏，不需要额外的通知机制；
 * - [isLoading] 是「**一轮**还在跑」这一个信号（不是「有请求在飞」）：实现方写它的时候要顺带
 *   同步 `AgentThinkingHost.isThinking`（照 [AgentViewModel.applyLoading] 那一处）；
 * - [contextExhaustedJustTriggered] 是**一次性**事件，只有 [consumeContextExhaustedTrigger] 能取走它
 *   （取走即清零）——屏用 `LaunchedEffect` 弹一次窗，不是「真则一直弹」；
 * - [bind] 是「把落盘接进来、并把会话表与当前会话拉齐」这一个动作，**只在屏进来时调一次**
 *   （实现方自己保证幂等）。
 *
 * 默认参数只写在**这条接口**上：实现方（比如 [AgentViewModel]）不许重复声明默认值，
 * 否则「同一件事两个默认」迟早漂开。
 */
interface AgentChatSession {

    /** 屏上的消息。`delta` / `reasoning` / `tool_call` / `tool_result` 都往这里写。 */
    val messages: SnapshotStateList<ChatMessage>

    /** 有没有一轮正在生成（底栏的思考动画与它同源）。 */
    val isLoading: Boolean

    /** 这一轮的失败文案（`null` = 没有）。与 `AgentViewModel` 同名同型。 */
    var errorMessage: String?

    /** 上下文是否已达上限（800k token）。 */
    val contextExhausted: Boolean

    /** [contextExhausted] 刚刚由 `false` 变 `true` 的一次性标记。 */
    val contextExhaustedJustTriggered: Boolean

    /** 取走 [contextExhaustedJustTriggered]（取走即清零），返回取走的值。 */
    fun consumeContextExhaustedTrigger(): Boolean

    /** 会话表（新的在前，按 `updatedAt` 降序）。 */
    val sessions: SnapshotStateList<AgentSession>

    /** 当前会话 id（`null` = 还没建）。 */
    val currentSessionId: String?

    /** 接上落盘并拉齐会话表与当前会话（屏进来时调一次）。 */
    fun bind(store: AgentSessionPersistence)

    /** 新开一个会话。 */
    fun newSession()

    /** 切到某个会话（实现方负责「切走就取消本轮、防串台」）。 */
    fun switchSession(id: String)

    /** 删掉某个会话（幂等）。 */
    fun deleteSession(id: String)

    /** 停止正在生成的那一轮。 */
    fun stop()

    /**
     * 改掉最后一条用户消息并重发。语义在浏览器那一端**不画这个入口**（`docs/agent-web-spec.md`
     * §9 未决 #7），但接口留着它：屏上是同一个「改上一条」编辑框。
     */
    fun replaceLastUserAndSend(newText: String, config: AgentConfig)

    /**
     * 发一条用户消息。
     *
     * @param llmAnnex 只进发给模型的那条 user 消息、**不进气泡**的提醒快照（本端才有的东西）。
     * @param images 随消息发出的图片路径（没有相册的端恒为空）。
     */
    fun sendMessage(
        userText: String,
        config: AgentConfig,
        llmAnnex: String? = null,
        images: List<String> = emptyList(),
    )

    companion object {

        /**
         * 上下文耗尽时那句文案，四端逐字相同。
         * 就是 `:core` 顶层的 [AGENT_CONTEXT_EXHAUSTED_MESSAGE]（serve 侧 409 的 message 也用它）。
         */
        const val CONTEXT_EXHAUSTED_MESSAGE = AGENT_CONTEXT_EXHAUSTED_MESSAGE
    }
}
