package com.xjtu.toolbox.platform

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Android：按 JVM 异常类型判，顺序即优先级。 */
actual fun classifyNetworkFailure(e: Throwable): NetworkFailure? = when (e) {
    is UnknownHostException -> NetworkFailure.NO_HOST
    is SocketTimeoutException -> NetworkFailure.TIMEOUT
    is ConnectException -> NetworkFailure.REFUSED
    is SSLException -> NetworkFailure.TLS
    is IOException -> NetworkFailure.IO
    else -> null
}
