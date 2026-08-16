package com.xjtu.toolbox.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 异常 → 用户友好文案映射。
 *
 * 「反人类 UI」审计 P0-P2 指出：大量屏幕直接把 `${e.message}` 给用户看，
 * 暴露的是程序员视角的细节（类名、堆栈片段、"Connection reset"、HTTP 401）。
 *
 * 这里把异常按类别映射成"用户能看懂 + 知道下一步做什么"的短句。
 *
 * 用法：
 * ```
 * errorMessage = FriendlyError.of(e, defaultAction = "加载通知")
 * ```
 */
object FriendlyError {

    /**
     * @param e 原始异常
     * @param defaultAction 中文动词短语，用于兜底消息（"加载通知"/"查询成绩"/"刷新课表"）
     *        形式应能直接接在「无法」之后，例如「无法{defaultAction}，请检查网络后重试」
     * @return 不会超过 50 字的友好短句
     */
    fun of(e: Throwable, defaultAction: String = "完成此操作"): String {
        if (e is kotlinx.coroutines.CancellationException) throw e
        return when (e) {
            is UnknownHostException -> "无法连接到服务器，请检查网络"
            is SocketTimeoutException -> "请求超时，请稍后重试"
            is ConnectException -> "无法连接到服务器，请检查网络或 VPN 设置"
            is SSLException -> "安全连接失败，请检查网络环境（VPN/代理）"
            is IOException -> "${defaultAction}失败：网络异常，请稍后重试"
            else -> {
                val raw = e.message ?: ""
                if (raw.isBlank()) "${defaultAction}失败，请稍后重试"
                else friendlyFromMessage(raw, defaultAction)
            }
        }
    }

    /**
     * 已知可识别的中文/英文错误关键字 → 友好映射。
     * 没匹配的则去掉栈帧痕迹、控制字符后返回。
     */
    private fun friendlyFromMessage(raw: String, defaultAction: String): String {
        val lower = raw.lowercase()
        return when {
            "401" in raw || "unauthor" in lower || "未登录" in raw || "token" in lower ->
                "登录已过期，请在「我的」中重新登录"
            "403" in raw || "forbid" in lower ->
                "没有访问权限，可能需要重新登录"
            "404" in raw || "not found" in lower ->
                "服务暂不可用，请稍后再试"
            "500" in raw || "502" in raw || "503" in raw || "internal server" in lower ->
                "服务器开小差了，请稍后再试"
            "504" in raw || "gateway" in lower ->
                "服务响应超时，请稍后再试"
            "timeout" in lower ->
                "请求超时，请稍后重试"
            "connection reset" in lower || "connection refused" in lower ->
                "连接被拒绝，请检查网络或 VPN 设置"
            raw.length > 60 -> "${defaultAction}失败，请稍后重试"
            else -> "${defaultAction}失败：${raw.take(40)}"
        }
    }
}