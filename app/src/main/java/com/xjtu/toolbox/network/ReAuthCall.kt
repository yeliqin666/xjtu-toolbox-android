package com.xjtu.toolbox.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request

/**
 * 「发一次请求 → 判定认证失效 → 回调重认证 → 原样重放一次」的机制，**与 okhttp 类型无关**。
 *
 * 这是 okhttp 版 [com.xjtu.toolbox.auth.SiteSession.executeWithReAuth] 里那段循环的等价物，
 * 抽出来单独一个类，是为了在两件事上摆脱 okhttp 类型：
 *  1. 站点会话（`SiteSession` 及其 16 处子类重写）将来可以整体搬进 `:core`；
 *  2. 这段逻辑**可以被单测钉住** —— 会话本身要 `Context`/`SessionBackend`，在单测里造不出来，
 *     而「401 之后重认证、重放、第二次仍 401 就放弃」这件事恰恰是最不该靠肉眼看的。
 *
 * 语义与原实现逐条对齐（[重放一次] 就够：原实现也是 `retried` 标志位只递归一层）：
 * - 第一次就失效 → 调用 [onAuthFailure]（会话在里做 `markWebVpnStale` + `ensureLogin(force)`），
 *   然后**原样重放**（同一个请求构建 lambda，重新构造请求）；
 * - 重放后仍失效 → 调用 [onRepeatFailure]（会话在那里抛 `AuthExpiredException`）。
 *   两次都会先经过 [onAuthFailureDetected]（会话在那里打「判据」日志：状态码 + URL + preview，
 *   原实现的注释说得很清楚：只打一句 auth failure 的话，遇到误判根本无从分辨）。
 *
 * ⚠️ 三次回调都可能抛（例如凭据已失效、被取消），异常按调用方处理，这里不吞。
 */
class ReAuthCall(
    private val http: HttpClient,
    private val peekLimit: Int = KtorReply.PEEK_LIMIT,
) {
    suspend fun execute(
        /** 请求构建；重放时会**再调一次**，所以必须是纯函数（不要在里面累加状态）。 */
        block: HttpRequestBuilder.() -> Unit,
        isAuthFailure: (KtorReply) -> Boolean,
        onAuthFailureDetected: (KtorReply) -> Unit = {},
        onAuthFailure: suspend (KtorReply) -> Unit,
        onRepeatFailure: (KtorReply) -> Nothing,
    ): KtorReply {
        val first = send(block)
        if (!isAuthFailure(first)) return first
        onAuthFailureDetected(first)
        // 与原实现一致：判成认证失效就把这条响应丢掉（okhttp 那边是 response.close()），
        // 二进制响应的未读流也在这里放掉，不留悬挂连接
        first.discard()
        onAuthFailure(first)

        val second = send(block)
        if (!isAuthFailure(second)) return second
        onAuthFailureDetected(second)
        second.discard()
        onRepeatFailure(second)
    }

    private suspend fun send(block: HttpRequestBuilder.() -> Unit): KtorReply =
        http.request(block).toKtorReply(peekLimit)
}
