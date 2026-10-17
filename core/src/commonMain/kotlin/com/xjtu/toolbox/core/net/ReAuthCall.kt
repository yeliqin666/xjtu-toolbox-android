package com.xjtu.toolbox.core.net

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request

/** 「发一次请求 → 判定认证失效 → 回调重认证 → 原样重放一次」的机制，与 okhttp 类型无关。 */
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
