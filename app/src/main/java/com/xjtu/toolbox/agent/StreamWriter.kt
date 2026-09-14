package com.xjtu.toolbox.agent

/**
 * 流式输出的写入协调器。
 *
 * 模型每个 token 回调一次增量，而每次都直接改可观察列表代价很大：字符串要整条重拼
 * （累计 O(n²)），列表还要跟着重排一次。这里的做法是累积到缓冲区、按时间片合并写回，
 * 让写入次数从「每 token 一次」压到「每帧最多一次」。
 *
 * 为什么抽成一个类而不是留在 ViewModel 里写几个局部函数：它的正确性全在**编排**上
 * （何时重置、何时必须强制写回），而这正是最容易写错、也最容易在重构里悄悄坏掉的部分。
 * 抽出来之后，测试可以用假时钟精确驱动它，把两个不变量钉死：
 *
 * 1. **不丢字**——本段收到的所有增量，最终必须一字不差地写出去。
 * 2. **不串段**——换段（工具调用后另起气泡）时，上一段已写出的文字不能被新段覆盖。
 *
 * 第 2 条尤其要紧：收尾时正文会被模型返回的完整文本覆盖，但思考内容只会经这里写入，
 * 一旦换段时忘了强制写回，用户就会看到思考栏少了一截。
 *
 * 线程模型：只在主线程（`Dispatchers.Main.immediate` 的回调里）使用，内部无同步。
 */
internal class StreamWriter(
    /** 真正落到消息上的写入动作。 */
    private val write: (content: String, reasoning: String) -> Unit,
    /** 取当前时刻，纳秒。测试注入假时钟。 */
    private val now: () -> Long = { System.nanoTime() },
) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private var lastFlushNs = 0L

    /** 本段是否已经有任何内容（用于判断该不该建气泡）。 */
    var hasContent: Boolean = false
        private set

    /**
     * 追加一个正文增量，必要时写回。
     */
    fun appendContent(frag: String) {
        content.append(frag)
        hasContent = true
        maybeFlush()
    }

    /** 追加一个思考增量，必要时写回。 */
    fun appendReasoning(frag: String) {
        reasoning.append(frag)
        hasContent = true
        maybeFlush()
    }

    /**
     * 立即写回并清空本段累积。
     *
     * [force] = true 用于换段与收尾：无视节流间隔，保证最后一段绝不丢。
     * 返回是否真的发生了写入。
     */
    fun flush(force: Boolean = false): Boolean {
        if (!hasContent) return false
        val t = now()
        if (!streamFlushDue(t, lastFlushNs, force)) return false
        lastFlushNs = t
        write(content.toString(), reasoning.toString())
        return true
    }

    /**
     * 结束本段（换气泡前调用）：强制写回并清空缓冲，让下一段从零开始。
     *
     * 只清缓冲不清 [hasContent] 之外的计时——新段的首个增量要立即上屏，所以
     * [lastFlushNs] 一并归零。
     */
    fun endSegment() {
        flush(force = true)
        content.setLength(0)
        reasoning.setLength(0)
        hasContent = false
        lastFlushNs = 0L
    }

    private fun maybeFlush() {
        flush(force = false)
    }
}
