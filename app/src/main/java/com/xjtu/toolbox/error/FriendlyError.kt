package com.xjtu.toolbox.error

import com.xjtu.toolbox.auth.AccountSwitchedException
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginCooldownException
import com.xjtu.toolbox.auth.MfaCancelledException
import com.xjtu.toolbox.auth.MfaRequiredException
import com.xjtu.toolbox.auth.PasswordInvalidatedException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 异常 → 用户友好文案。界面不要直接显示 `e.message`：那是程序员视角的细节（类名、
 * "Connection reset"、HTTP 401）。只按异常类型和 HTTP 状态码分类，不在消息里猜关键字。
 *
 * ```
 * errorMessage = FriendlyError.of(e, defaultAction = "加载通知")
 * ```
 */
object FriendlyError {

    /** 应用自己抛出的错误里带的状态码，写法统一为 `HTTP 502`。 */
    private val HTTP_STATUS = Regex("""HTTP[ :]*(\d{3})""", RegexOption.IGNORE_CASE)
    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /**
     * @param defaultAction 中文动词短语，能直接接在「无法」之后，例如「加载通知」「查询成绩」
     * @return 不超过 80 字的短句
     */
    fun of(e: Throwable, defaultAction: String = "完成此操作"): String {
        if (e is kotlinx.coroutines.CancellationException) throw e
        return when (e) {
            is AuthExpiredException -> "登录已过期，请在「我的」中重新登录"
            // 这几类异常的消息是应用自己写的中文提示，直接可用
            is PasswordInvalidatedException, is LoginCooldownException, is MfaRequiredException,
            is MfaCancelledException, is AccountSwitchedException ->
                e.message?.takeIf { it.isNotBlank() } ?: "${defaultAction}失败，请稍后重试"
            is UnknownHostException -> "无法连接到服务器，请检查网络"
            is SocketTimeoutException -> "请求超时，请稍后重试"
            is ConnectException -> "无法连接到服务器，请检查网络或 VPN 设置"
            is SSLException -> "安全连接失败，请检查网络环境（VPN/代理）"
            else -> fromStatusOrMessage(e, defaultAction)
        }
    }

    /** HTTP 状态码对应的说明；非错误状态返回 null。 */
    fun forStatus(code: Int): String? = when (code) {
        401 -> "登录已过期，请在「我的」中重新登录"
        403 -> "没有访问权限，可能需要重新登录"
        404 -> "服务暂不可用，请稍后再试"
        408, 504 -> "服务响应超时，请稍后再试"
        in 500..599 -> "服务器开小差了，请稍后再试"
        else -> null
    }

    private fun fromStatusOrMessage(e: Throwable, defaultAction: String): String {
        val raw = e.message.orEmpty()
        HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let(::forStatus)?.let { return it }
        // 应用自己写的中文提示原样给用户；英文的是 JDK / 库的内部消息，不能给
        if (raw.length <= 80 && CJK.containsMatchIn(raw)) return raw
        return if (e is IOException) "${defaultAction}失败：网络异常，请稍后重试"
        else "${defaultAction}失败，请稍后重试"
    }
}