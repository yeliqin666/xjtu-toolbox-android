package com.xjtu.toolbox.core.net

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Ktor 形状的响应：把「文本类 / 二进制流」两条路的差别收在一处，供 [ReAuthCall] 与站点会话使用。 */
class KtorReply(
    val status: Int,
    /** 最终生效的 URL（跟随跳转之后），用于把 WebVPN 代理地址还原成原地址打日志。 */
    val finalUrl: String,
    val headers: Headers,
    /** 文本类：完整响应体；二进制类：空数组。 */
    val bytes: ByteArray,
    /** 文本类：响应体前缀（最多 [KtorReply.PEEK_LIMIT] 字节），用于「是不是登录页」的判定；二进制类：null。 */
    val peek: String?,
    private val streamProvider: (suspend () -> ByteReadChannel)?,
    /** 丢弃这条响应（未读的流要放掉，否则连接挂在那儿）。对应原实现的 `response.close()`。 */
    private val discardProvider: (suspend () -> Unit)? = null,
) {
    val isSuccessful: Boolean get() = status in 200..299

    fun header(name: String): String? = headers[name]

    /** 文本类响应的完整正文；二进制类调用会抛（该用 [stream]）。 */
    fun text(): String = bytes.decodeToString()

    suspend fun stream(): ByteReadChannel =
        streamProvider?.invoke() ?: error("这条响应是文本类，已经读完放在 bytes 里了")

    /** 无论哪条路都能安全调：文本类已经读完，二进制类把未读的流放掉。 */
    suspend fun discard() {
        discardProvider?.invoke()
    }

    companion object {
        /** 认证判定只看得上前 8KB：与原实现的 `peekBody(8192)` 同一个数。 */
        const val PEEK_LIMIT = 8192
    }
}

/** 与 `SiteSession.isTextualResponse` 同一套判断（缺省无 CT 时按文本处理，保守）。 */
internal fun isTextualContentType(rawContentType: String?): Boolean {
    val ct = rawContentType?.lowercase() ?: return true
    return "json" in ct || "html" in ct || "text" in ct || "xml" in ct || "javascript" in ct
}

/** 按上一节的规则把 [HttpResponse] 收敛成 [KtorReply]。 */
suspend fun HttpResponse.toKtorReply(peekLimit: Int = KtorReply.PEEK_LIMIT): KtorReply {
    val finalUrl = call.request.url.toString()
    if (!isTextualContentType(headers["Content-Type"])) {
        return KtorReply(
            status = status.value,
            finalUrl = finalUrl,
            headers = headers,
            bytes = ByteArray(0),
            peek = null,
            streamProvider = { bodyAsChannel() },
            discardProvider = { bodyAsChannel().cancel() },
        )
    }
    val all = bodyAsChannel().readRemaining().readByteArray()
    return KtorReply(
        status = status.value,
        finalUrl = finalUrl,
        headers = headers,
        bytes = all,
        peek = all.copyOf(minOf(peekLimit, all.size)).decodeToString(),
        streamProvider = null,
    )
}
