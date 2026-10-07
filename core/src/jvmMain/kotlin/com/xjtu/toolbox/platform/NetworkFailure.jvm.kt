package com.xjtu.toolbox.platform

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * jvm/desktop：与 Android 完全同一套 JVM 类型（桌面端暂时只有测试在用）。
 *
 * `:core:jvmTest` 就是靠这个 actual 才能验证分类结果 —— Android 侧的实际取值由同一份
 * 类型表给出（androidMain 的实现与这里逐字相同）。
 */
actual fun classifyNetworkFailure(e: Throwable): NetworkFailure? = when (e) {
    is UnknownHostException -> NetworkFailure.NO_HOST
    is SocketTimeoutException -> NetworkFailure.TIMEOUT
    is ConnectException -> NetworkFailure.REFUSED
    is SSLException -> NetworkFailure.TLS
    is IOException -> NetworkFailure.IO
    else -> null
}
