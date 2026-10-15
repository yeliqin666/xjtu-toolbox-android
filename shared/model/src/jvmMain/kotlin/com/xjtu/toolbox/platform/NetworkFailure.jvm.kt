package com.xjtu.toolbox.platform

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** jvm/desktop：同一套 JVM 异常类型。 */
actual fun classifyNetworkFailure(e: Throwable): NetworkFailure? = when (e) {
    is UnknownHostException -> NetworkFailure.NO_HOST
    is SocketTimeoutException -> NetworkFailure.TIMEOUT
    is ConnectException -> NetworkFailure.REFUSED
    is SSLException -> NetworkFailure.TLS
    is IOException -> NetworkFailure.IO
    else -> null
}
