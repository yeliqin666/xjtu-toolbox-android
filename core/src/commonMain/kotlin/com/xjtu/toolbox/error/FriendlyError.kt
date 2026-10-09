package com.xjtu.toolbox.error

import com.xjtu.toolbox.platform.NetworkFailure
import com.xjtu.toolbox.platform.classifyNetworkFailure
import kotlinx.coroutines.CancellationException

/**
 * 「登录态失效」这一类失败。:app 的 `AuthExpiredException` 实现它。
 *
 * 为什么是一个标记接口而不是把那个异常类搬进来：`AuthExpiredException` 在 :app 里
 * **必须继续继承 `java.io.IOException`** —— 站点层（`SiteSession.executeWithReAuth` /
 * `SessionManager`）是按 `IOException` 记「这次登录失败、进冷却」的，换掉父类型会改掉重认证
 * 的走向（交接文档 §2 的硬约束「Android 行为零变化」正是冲着这种事）。
 * 所以用「:core 定策略、:app 认领类型」这条缝（交接文档 §5.1 的路 1）。
 */
interface SessionExpiredFailure

/**
 * 消息本身就是给用户看的中文短句的异常（应用自己抛的，例如 `账号或密码无效`）。
 *
 * 有它就不必再让 [FriendlyError] 去猜：搬迁前那几个认证异常是逐个 `is XxxException` 列出来的，
 * 现在由异常自己认领，`:core` 不必认识 :app 的任何类。
 */
interface UserFacingFailure

/**
 * 异常 → 用户友好文案。界面不要直接显示 `e.message`：那是程序员视角的细节（类名、
 * "Connection reset"、HTTP 401）。只按异常类型和 HTTP 状态码分类，不在消息里猜关键字。
 *
 * ```
 * errorMessage = FriendlyError.of(e, defaultAction = "加载通知")
 * ```
 *
 * 从 :app 的 `error/FriendlyError.kt` 搬进 commonMain：**每个 ViewModel / 屏都在用它**，
 * 留在 :app 就等于把 47 屏 都挡在外面（交接文档 §5 的「挡住 28 屏」）。
 *
 * 搬迁时唯一被拆掉的是「认识 :app 的认证异常」：那 6 个类留在 :app（上一条注释说明了原因），
 * 改成实现 [SessionExpiredFailure] / [UserFacingFailure] 两个标记接口；`java.io` / `java.net` /
 * `javax.net.ssl` 那五个纯类型判断换成了平台家族 `classifyNetworkFailure`。
 * **文案逐字未动**，`when` 的分支顺序也逐条对齐（会话过期 → 自带中文提示 → 网络分类 → 状态码/兜底）。
 */
object FriendlyError {

    /** 应用自己抛出的错误里带的状态码，写法统一为 `HTTP 502`。 */
    private val HTTP_STATUS = Regex("""HTTP[ :]*(\d{3})""", RegexOption.IGNORE_CASE)
    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /** 401 与「会话过期异常」共用同一句，免得两处口径漂移。 */
    private const val SESSION_EXPIRED = "登录已过期，请在「我的」中重新登录"

    /**
     * @param defaultAction 中文动词短语，能直接接在「无法」之后，例如「加载通知」「查询成绩」
     * @return 不超过 80 字的短句
     */
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
            // IO 与「认不出」都要继续走状态码/消息：搬迁前 `IOException("HTTP 404")`
            // 也是先被状态码规则接走的，不是在异常类型那一步就定成「网络异常」
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

    /**
     * @param networkFailure 已经判定为「网络/IO 失败」（搬迁前这里是 `e is IOException`）。
     *   两者等价：平台家族对 JVM 上任何 `IOException` 都返回非 null（`NO_HOST` / `TIMEOUT` /
     *   `REFUSED` / `TLS` / `IO`），而前四种在上面已经提前返回了。
     */
    private fun fromStatusOrMessage(e: Throwable, defaultAction: String, networkFailure: Boolean): String {
        val raw = e.message.orEmpty()
        HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let(::forStatus)?.let { return it }
        // 应用自己写的中文提示原样给用户；英文的是 JDK / 库的内部消息，不能给
        if (raw.length <= 80 && CJK.containsMatchIn(raw)) return raw
        return if (networkFailure) "${defaultAction}失败：网络异常，请稍后重试"
        else "${defaultAction}失败，请稍后重试"
    }
}
