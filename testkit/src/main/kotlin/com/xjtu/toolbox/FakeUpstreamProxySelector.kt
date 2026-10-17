package com.xjtu.toolbox

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** 常驻的「把连接指到当前假上游」selector：端口 0 = 不走代理（进程默认状态）。 */
object FakeUpstreamProxySelector : ProxySelector() {

    @Volatile
    var port: Int = 0

    override fun select(uri: URI): List<Proxy> {
        val p = port
        return if (p == 0) {
            listOf(Proxy.NO_PROXY)
        } else {
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", p)))
        }
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit

    /** 把进程默认 selector 换成这一份（幂等）。**必须早于任何 `OkHttpClient` 被建出来。** */
    fun install() {
        if (ProxySelector.getDefault() !== this) ProxySelector.setDefault(this)
    }
}
