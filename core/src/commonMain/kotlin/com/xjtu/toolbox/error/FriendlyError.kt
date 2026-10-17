package com.xjtu.toolbox.error

import com.xjtu.toolbox.platform.NetworkFailure
import com.xjtu.toolbox.platform.classifyNetworkFailure
import kotlinx.coroutines.CancellationException

/** 「登录态失效」这一类失败。 */
interface SessionExpiredFailure

/** 消息本身就是给用户看的中文短句的异常（应用自己抛的，例如 `账号或密码无效`）。 */
interface UserFacingFailure

/** 异常 → 用户友好文案。 */
object FriendlyError {

    /** 应用自己抛出的错误里带的状态码，写法统一为 `HTTP 502`。 */
    private val HTTP_STATUS = Regex("""HTTP[ :]*(\d{3})""", RegexOption.IGNORE_CASE)
    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /** 401 与「会话过期异常」共用同一句。 */
    private const val SESSION_EXPIRED = "登录已过期，请在「我的」中重新登录"

    fun of(e: Throwable, defaultAction: String = "完成此操作"): String {
        if (e is CancellationException) throw e
        when (e) {
            is SessionExpiredFailure -> return SESSION_EXPIRED
            // 这几类异常的消息是应用自己写的中文提示，直接可用
            is UserFacingFailure ->
                return e.message?.takeIf { it.isNotBlank() } ?: "${defaultAction}失败，请稍后重试"
        }
        val failure = classifyNetworkFailure(e)
        when (failure) {
            NetworkFailure.NO_HOST -> return "无法连接到服务器，请检查网络"
            NetworkFailure.TIMEOUT -> return "请求超时，请稍后重试"
            NetworkFailure.REFUSED -> return "无法连接到服务器，请检查网络或 VPN 设置"
            NetworkFailure.TLS -> return "安全连接失败，请检查网络环境（VPN/代理）"
            NetworkFailure.IO, null -> {}
        }
        return fromStatusOrMessage(e, defaultAction, networkFailure = failure != null)
    }

    /** HTTP 状态码对应的说明；非错误状态返回 null。 */
    fun forStatus(code: Int): String? = when (code) {
        401 -> SESSION_EXPIRED
        403 -> "没有访问权限，可能需要重新登录"
        404 -> "服务暂不可用，请稍后再试"
        408, 504 -> "服务响应超时，请稍后再试"
        in 500..599 -> "服务器开小差了，请稍后再试"
        else -> null
    }

    private fun fromStatusOrMessage(e: Throwable, defaultAction: String, networkFailure: Boolean): String {
        val raw = e.message.orEmpty()
        HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let(::forStatus)?.let { return it }
        // 应用自己写的中文提示原样给用户；英文的是 JDK / 库的内部消息，不能给
        if (raw.length <= 80 && CJK.containsMatchIn(raw)) return raw
        return if (networkFailure) "${defaultAction}失败：网络异常，请稍后重试"
        else "${defaultAction}失败，请稍后重试"
    }
}
