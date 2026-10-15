package com.xjtu.toolbox.web

import com.xjtu.toolbox.agent.StoredWidget
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import kotlinx.serialization.json.JsonObject

/**
 * `POST /api/agent/chat` 那条 SSE 流的**一帧**（`docs/api-contract.md` §5.11 的十一类 + 一类「认不出」）。
 *
 * 事件名与载荷逐条照契约：`delta` / `reasoning` / `tool_call` / `tool_result` / `usage` / `mfa` /
 * `widget` / `nav` / `done` / `error`，外加注释帧 `: ping`（SSE 规范里 `:` 开头的注释行，必须忽略）。
 * 服务端那一半是 `:server` 的 `agentSseFrame`（同一个文件里的纯函数），两端**对着写**：
 * 改一边不改另一边，本文件的用例会红。
 *
 * ## 三件必须如实的事
 *
 * - **注释帧不算内容**：[Ping] 不写任何状态（保活每 15 秒一次，写进去就会平白多出一条消息）；
 * - **`done` 是唯一正常终止、`error` 是唯一异常终止**：被停止 / 客户端断开 / 写帧失败时
 *   **一个终止事件都不发**（契约 §5.11 明文写的），所以消费者不能在「流断了」之后自己补一句
 *   「正常结束」或「出错了」—— 那一档由 [AgentStreamState.stopped] 说「（已停止）」；
 * - **认不出的帧不写状态**（[Unknown]）：契约将来加一类事件时，旧页面应当「少显示一块」，
 *   而不是把整条流当成坏了。[Unknown.raw] 只为让人能查（页面上一个字都不显示它）。
 */
internal sealed interface AgentFrame {

    /** 模型正文增量，逐段。 */
    data class Delta(val text: String) : AgentFrame

    /** 思维链增量，逐段（仅思考开启时）。 */
    data class Reasoning(val text: String) : AgentFrame

    /** 一次工具调用（聚合之后、开始执行之前）。**只有名字**：文案按 `:core` 的同一份表拼。 */
    data class ToolCall(val name: String) : AgentFrame

    /** 一次工具执行结束。[error] 只在失败时有值。 */
    data class ToolResult(val name: String, val success: Boolean, val error: String?) : AgentFrame

    /** 模型回报的累计 token（只用来兜底判断上下文是否烧到上限）。 */
    data class Usage(val totalTokens: Long) : AgentFrame

    /** 校园站点的短信二验挂起 / 结束。[siteName] 只有站名，**没有手机号**（红线）。 */
    data class Mfa(val pending: Boolean, val siteName: String?) : AgentFrame

    /** 一轮的卡片（落盘形状，与 `StoredMessage.widgets` 同形）。 */
    data class Widgets(val widgets: List<StoredWidget>) : AgentFrame

    /** 回复下方那排跳转按钮（`route` 是 `AppRoute.id`）。 */
    data class Nav(val routes: List<Pair<String, String>>) : AgentFrame

    /** 一轮**正常结束**（唯一正常终止事件）。 */
    data class Done(
        val content: String,
        val sessionId: String,
        val title: String,
        val contextExhausted: Boolean,
        val totalTokens: Long?,
    ) : AgentFrame

    /** 一轮**异常结束**（唯一异常终止事件）。`kind` = `session_expired` / `llm` / `tool` / `internal`。 */
    data class Error(val kind: String, val message: String) : AgentFrame

    /** 保活注释帧（`: ping`）：解析时**忽略**。 */
    data object Ping : AgentFrame

    /** 认不出的事件名 / 载荷不是 JSON：不写状态，留着能查。 */
    data class Unknown(val name: String?, val raw: String) : AgentFrame
}

/**
 * **一帧一行**的 SSE 解析器（纯逻辑，不碰网络 —— 这一半由 `:web` 的用例逐条钉住）。
 *
 * 为什么自己写而不是用某个库：这一族是契约 §5.11 手写帧（`:server` 侧没引 `ktor-server-sse`），
 * 形状固定成 `event:` + 一行 `data:` + 空行，而浏览器这一侧的 fetch 桥给的又是**任意切的字节块**
 * （`TextDecoder` 解出来的字符串可能停在半帧上）⇒ 需要的就是一个「喂多少吃多少、完整的帧吐出来、
 * 半帧留着等下一块」的缓冲器。这正是 [push] 的语义。
 *
 * 容忍度（照 SSE 规范，且都有用例）：
 * - `\r\n` / 单独 `\r` 一律当行分隔；
 * - 一行里第一个 `:` 之前是字段名，之后的第一个空格不算值（`data: {...}` 与 `data:{...}` 等价）；
 * - `data:` 多行按换行拼起来（契约里服务端只写一行，但规范允许）；
 * - 认不出的字段（`id` / `retry` / 别的）与空行一律忽略；
 * - 一整个块里只有注释行 ⇒ 一个 [AgentFrame.Ping]。
 */
internal class AgentSseParser {

    private val pending = StringBuilder()

    /** 喂一块（可能是半帧、可能黏着好几帧），吐出这一块里**已经完整**的那些帧。 */
    fun push(chunk: String): List<AgentFrame> {
        // 行分隔统一成 \n（服务端写的就是 \n；容错是给反代与中间层留的余地）
        pending.append(chunk.replace("\r\n", "\n").replace('\r', '\n'))
        val out = mutableListOf<AgentFrame>()
        while (true) {
            val end = pending.indexOf(BLOCK_SEP)
            if (end < 0) break
            val block = pending.substring(0, end)
            // 吐掉这一块（commonMain 的 StringBuilder 没有 `delete(start, end)`：重建尾部这一段）
            val rest = pending.substring(end + BLOCK_SEP.length)
            pending.setLength(0)
            pending.append(rest)
            parseBlock(block)?.let { out += it }
        }
        return out
    }

    /** 一个完整块（到空行为止）→ 一帧；空块与「只有认不出的字段」返回 null。 */
    private fun parseBlock(block: String): AgentFrame? {
        if (block.isBlank()) return null
        var name: String? = null
        val data = StringBuilder()
        var sawData = false
        var sawComment = false
        block.split('\n').forEach { line ->
            if (line.isEmpty()) return@forEach
            if (line.startsWith(":")) {
                // SSE 的注释行：契约里唯一的用法是保活 `: ping`
                sawComment = true
                return@forEach
            }
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val raw = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> name = raw.trim()
                "data" -> {
                    if (sawData) data.append('\n')
                    data.append(raw)
                    sawData = true
                }
                // `id` / `retry` 与认不出的字段：契约里没有它们（断线重连明确不做，见 §5.11），照规范忽略
                else -> Unit
            }
        }
        if (!sawData) return if (sawComment) AgentFrame.Ping else null
        return frameOf(name, data.toString())
    }

    private fun frameOf(name: String?, raw: String): AgentFrame {
        val payload = runCatching { AppJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return AgentFrame.Unknown(name, raw)
        return when (name) {
            "delta" -> AgentFrame.Delta(payload["text"].safeString())
            "reasoning" -> AgentFrame.Reasoning(payload["text"].safeString())
            "tool_call" -> AgentFrame.ToolCall(payload["name"].safeString())
            "tool_result" -> AgentFrame.ToolResult(
                name = payload["name"].safeString(),
                success = payload["success"].safeBoolean(),
                error = payload.safeGet("error")?.safeString(),
            )
            "usage" -> AgentFrame.Usage(payload["totalTokens"].safeLong())
            "mfa" -> AgentFrame.Mfa(
                pending = payload["pending"].safeBoolean(),
                siteName = payload.safeGet("siteName")?.safeString()?.takeIf { it.isNotBlank() },
            )
            "widget" -> AgentFrame.Widgets(
                payload.arr("widgets").orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    StoredWidget(type = o["type"].safeString(), json = o["json"].safeString())
                },
            )
            "nav" -> AgentFrame.Nav(
                payload.arr("routes").orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    o["label"].safeString() to o["route"].safeString()
                },
            )
            "done" -> AgentFrame.Done(
                content = payload["content"].safeString(),
                sessionId = payload["sessionId"].safeString(),
                title = payload["title"].safeString(),
                contextExhausted = payload["contextExhausted"].safeBoolean(),
                // 服务端 `explicitNulls = false` ⇒ 没有 usage 时这一格**不存在**（契约 §4：字段缺失 = 不投影）
                totalTokens = payload.safeGet("totalTokens")?.safeLong(),
            )
            "error" -> AgentFrame.Error(
                kind = payload["kind"].safeString(),
                message = payload["message"].safeString(),
            )
            // 没有 `event:` 行 / 认不出的名字：不猜它是哪一类
            else -> AgentFrame.Unknown(name, raw)
        }
    }

    private companion object {
        /** 帧与帧之间那一个空行。 */
        const val BLOCK_SEP = "\n\n"
    }
}
