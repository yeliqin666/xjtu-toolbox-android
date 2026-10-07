package com.xjtu.toolbox.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Ktor 形状的响应：把「文本类 / 二进制流」两条路的差别收在一处，供 [ReAuthCall] 与站点会话使用。
 *
 * 为什么要有这一层：okhttp 的 `executeWithReAuth` 靠 `response.peekBody(8192)` 在不消耗流的前提下
 * 取一段响应体来判「这是不是 CAS 登录页」；**Ktor 没有 peekBody**（`bodyAsChannel()` 读一次就没了）。
 * 这一层用两条各自正确的路替掉它（与原实现的 `isTextualResponse` 判断逐字对齐）：
 *
 * - **文本类**（`Content-Type` 含 json/html/text/xml/javascript，或缺省 CT）：
 *   整个读完放进 [KtorReply.bytes]，同时留下前 [peekLimit] 字节作为 [KtorReply.peek] 供认证判定。
 *   代价是“整页读进内存”，但这一路上调用方本来就要 `.body.string()` 读完整页
 *   （课表页、成绩页、图书馆页……都是几百 KB 的 HTML），所以只是把原来「peek 一份 + 再读一份」
 *   变成了「读一份」，没有引入新的量级。
 * - **二进制类**（PDF / 图片 / 附件下载）：**不读**、不 peek —— 与原来一致
 *   （原实现的注释：“二进制流不可能是 CAS 登录页，却要为此多拷贝+解码 8KB”），
 *   响应体留着给调用方流式读（[KtorReply.stream]）。
 */
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

/**
 * 按上一节的规则把 [HttpResponse] 收敛成 [KtorReply]。
 *
 * ⚠️ 只有**文本类**会把正文读进内存；二进制类必须保持流式（教材全文库、成绩单 PDF、座位二维码
 * 都在这条路上），否则几十 MB 的文件会整份进堆。
 */
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
