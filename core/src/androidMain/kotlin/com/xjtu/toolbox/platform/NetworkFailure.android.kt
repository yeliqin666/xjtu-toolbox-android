package com.xjtu.toolbox.platform

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Android：按 JVM 异常类型判，**顺序即优先级**。
 *
 * 与搬迁前 `FriendlyError` 里那个 `when (e)` 的顺序逐条对齐：UnknownHost → SocketTimeout →
 * Connect → SSL → 其它 IOException。okhttp 抛的就是这些类型（TLS 失败是
 * `javax.net.ssl.SSLException` 的子类）。
 */
actual fun classifyNetworkFailure(e: Throwable): NetworkFailure? = when (e) {
    is UnknownHostException -> NetworkFailure.NO_HOST
    is SocketTimeoutException -> NetworkFailure.TIMEOUT
    is ConnectException -> NetworkFailure.REFUSED
    is SSLException -> NetworkFailure.TLS
    is IOException -> NetworkFailure.IO
    else -> null
}
