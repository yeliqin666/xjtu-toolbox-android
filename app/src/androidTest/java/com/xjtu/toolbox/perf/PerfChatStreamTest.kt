package com.xjtu.toolbox.perf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjtu.toolbox.agent.ChatMessage
import com.xjtu.toolbox.agent.groupAgentRows
import com.xjtu.toolbox.agent.streamFlushDue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 聊天流式输出：`groupAgentRows` 的每调用成本随会话长度的增长。
 *
 * 该函数原本在 composable 主体里、每次重组都跑一遍，而流式输出每收到一个 chunk 就触发
 * 一次重组（`AgentRunner` 逐行读 SSE，`onDelta` 走 `Dispatchers.Main.immediate`）。
 * 所以「一条 N 轮会话每收一个 token 要花多少」就是这块的核心指标。
 *
 * 用语料覆盖两种真实形状：
 * - 纯文本轮次（最常见）；
 * - 带工具调用的轮次（会走 flatMap / distinctBy 分支，更贵）。
 */
@RunWith(AndroidJUnit4::class)
class PerfChatStreamTest {

    /** 构造 [turns] 轮、每轮 [chunksPerTurn] 个 assistant 增量的会话。 */
    private fun conversation(turns: Int, fenced: Boolean): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(turns * 4)
        var ts = 1_700_000_000_000L
        repeat(turns) { t ->
            out += ChatMessage(role = "user", content = "第 $t 个问题，关于课表和空教室", timestamp = ts++)
            if (fenced) {
                // 工具事件 + 失败记录
                out += ChatMessage(role = "tool_event", content = "get_schedule 调用中…", isToolCall = true, timestamp = ts++)
                out += ChatMessage(role = "tool_event", content = "已获取 12 门课程", timestamp = ts++)
            }
            // 流式增量：这些是流式过程中不断追加的同一条消息的中间态
            out += ChatMessage(role = "assistant", content = "这是第 $t 轮的回答。", timestamp = ts++)
            out += ChatMessage(
                role = "assistant",
                content = "这是第 $t 轮的回答。补充说明若干，让文本有点长度。",
                timestamp = ts++,
            )
        }
        return out
    }

    private fun bench(name: String, turns: Int, fenced: Boolean) {
        val msgs = conversation(turns, fenced)
        val stats = Perf.measureMedian(warmup = 100, iterations = 500, rounds = 5) {
            groupAgentRows(msgs)
        }
        Perf.report(name, stats)
    }

    @Test
    fun small() = bench("groupAgentRows 10 turns", 10, fenced = false)

    @Test
    fun medium() = bench("groupAgentRows 50 turns", 50, fenced = false)

    @Test
    fun large() = bench("groupAgentRows 200 turns", 200, fenced = false)

    @Test
    fun large_withTools() = bench("groupAgentRows 200 turns+tools", 200, fenced = true)

    /* ------------------------- 流式累积：当前写法 vs 新写法 ------------------------- */

    /**
     * 当前写法：每个 chunk 都把整条 content 重新拼一次再 `copy` 成新消息。
     *
     * `messages[i] = messages[i].copy(content = messages[i].content + frag)` —— 第 k 个
     * chunk 要复制前 k-1 个 chunk 的全文，所以一条总长 L 的消息累计复制 O(L²/2) 字符，
     * 同时分配 O(L) 个中间 String。
     */
    @Test
    fun streaming_appendCurrent() {
        val frags = fragments()
        val stats = Perf.measureMedian(warmup = 20, iterations = 200, rounds = 5) {
            var msg = ChatMessage(role = "assistant", content = "")
            for (f in frags) msg = msg.copy(content = msg.content + f)
            msg
        }
        Perf.report("stream append (current)", stats, divisor = frags.size, unit = "chunk")
    }

    /** 新写法：`StringBuilder` 累积，只在 flush 时生成一次字符串。 */
    @Test
    fun streaming_appendStringBuilder() {
        val frags = fragments()
        val stats = Perf.measureMedian(warmup = 20, iterations = 200, rounds = 5) {
            val sb = StringBuilder()
            for (f in frags) sb.append(f)
            sb.toString()
        }
        Perf.report("stream append (builder)", stats, divisor = frags.size, unit = "chunk")
    }

    /** 一条 800 字的回答，按 3 字符/chunk 切成流式增量（接近真实 SSE 粒度）。 */
    private fun fragments(): List<String> {
        val text = buildString {
            repeat(60) { append("这是一段用于测量流式累积成本的回答文本，长度接近真实回复。") }
        }
        return text.chunked(3)
    }

    /* ------------------------- 端到端：一条回复收完的总开销 ------------------------- */

    /**
     * 这是本块最贴近现实的指标：**一条回复从首字到末字，累计花掉了多少**。
     *
     * 两种做法都跑同一串增量、同一个会话规模，逐 token 计时求和：
     * - 旧：每个 token 都 copy 全文（O(n²) 字符串复制）+ 重排整个会话列表一次；
     * - 新：累积到缓冲、每帧最多写一次，列表只在写入时派生重算。
     *
     * token 到达速率是关键前提，取 **4ms 一个**（约 250 token/s）——快模型（如
     * deepseek-flash）实测能到这个量级，此时一帧内会挤进 4–5 个 token。若按
     * 16.67ms/token（一帧一个）模拟，节流根本触发不了，测不出任何差别，那是因为
     * 那种速率下本来就不需要节流。
     */
    @Test
    fun endToEnd_turnCost() {
        val frags = fragments()
        val perTokenNs = 4_000_000L   // 约 250 token/s

        // 旧做法：逐 token copy + 逐 token 重排
        val oldStats = Perf.measureMedian(warmup = 5, iterations = 40, rounds = 5) {
            val msgs = conversation(turns = 50, fenced = false).toMutableList()
            val idx = msgs.size.also { msgs.add(ChatMessage("assistant", "")) }
            for (f in frags) {
                msgs[idx] = msgs[idx].copy(content = msgs[idx].content + f)
                groupAgentRows(msgs)   // 每 token 一次列表重排
            }
            msgs.size
        }
        Perf.report("turn cost (old: per-token)", oldStats)

        // 新做法：累积 + 每帧最多写一次 + 只在写入时重排
        val newStats = Perf.measureMedian(warmup = 5, iterations = 40, rounds = 5) {
            val msgs = conversation(turns = 50, fenced = false).toMutableList()
            val idx = msgs.size.also { msgs.add(ChatMessage("assistant", "")) }
            val sb = StringBuilder()
            var lastFlush = 0L
            var t = 0L
            var rows = 0
            for (f in frags) {
                sb.append(f)
                t += perTokenNs
                if (streamFlushDue(t, lastFlush, force = false)) {
                    lastFlush = t
                    msgs[idx] = msgs[idx].copy(content = sb.toString())
                    rows += groupAgentRows(msgs).size   // 仅写入时重排
                }
            }
            // 收尾强制写回
            msgs[idx] = msgs[idx].copy(content = sb.toString())
            rows += groupAgentRows(msgs).size
            rows
        }
        Perf.report("turn cost (new: per-frame)", newStats)

        val ratio = oldStats.bytesPerOp / newStats.bytesPerOp.coerceAtLeast(1.0)
        Log.i(Perf.TAG, "  -> turn-cost allocation reduced %.1fx".format(ratio))
    }
}
