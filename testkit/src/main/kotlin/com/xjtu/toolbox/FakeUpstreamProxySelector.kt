package com.xjtu.toolbox

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 常驻的「把连接指到当前假上游」selector：端口 0 = 不走代理（进程默认状态）。
 *
 * ## 为什么必须是**同一个对象**、且端口可变
 *
 * `HttpClients.base`（`:data`）是进程级 `by lazy`，而 `OkHttpClient` 在 `Builder.build()` 那一刻
 * 就把当时的 `ProxySelector.getDefault()` **抄进自己的配置**（`SessionBackend.client` 又是从它
 * `newBuilder()` 派生的）。于是「每个测试类装一个新的 selector」只有**先碰 `HttpClients` 的那个类**
 * 生效：后面的类即使 `setDefault` 换掉了默认值，已经建好的客户端仍然拿着上一个人的对象 ——
 * 表现就是「代理端口不对 ⇒ Connection refused」或者「干脆不走代理 ⇒ 连真站点」。
 *
 * 所以全进程只留**这一份** selector：所有假上游（`FakeCampusProxy` 一个端口按 host 分派）
 * 与所有用手写 `HttpServer` 的测试（`:data:jvmTest` 的 `LibraryLoginSessionJvmTest`）
 * 都只改它的 [port]。谁先初始化 `HttpClients.base` 都不影响结论。
 *
 * ## 用法
 *
 * ```kotlin
 * FakeUpstreamProxySelector.install()   // 必须在建任何会话/客户端之前
 * FakeUpstreamProxySelector.port = port // 起服务之后指过来
 * ...跑...
 * FakeUpstreamProxySelector.port = 0    // 结束复原
 * ```
 */
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
