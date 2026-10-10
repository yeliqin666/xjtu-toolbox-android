package com.xjtu.toolbox

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.fitness.FitnessFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 把假的校园上游起成**一个本地端口**，按请求行里的 **host** 分派给各个夹具（裸 TCP 前置 + 明文/TLS
 * 两个后端，为什么不是一个 `HttpServer` 就够见 [FakeUpstreamFront]）。
 *
 * ## 为什么是「一个端口 + 按 host 分派」而不是「一个上游一个端口」
 *
 * 因为**客户端那边只认一个默认 `ProxySelector`**（而且它还是进程级、只读一次的，见下面）。一个
 * 端口同时扮演 `rg.lib.xjtu.edu.cn` / `login.xjtu.edu.cn` / `tyxylp.xjtu.edu.cn`（https）/ 校历门户，
 * 就绕开了「按 host 选端口」那一层，也最接近真机上「所有域名都从同一条网络出去」的样子。
 * 走代理时请求行是 absolute-form（`GET http://host/path HTTP/1.1`），所以 `requestURI.host`
 * 就是目标域名；https 那一条在 TLS 隧道里会变回 origin-form，域名只剩在 `Host` 头里 ——
 * 两者都认（见 [dispatch]）。分派靠 host，而不是靠路径。
 *
 * ## ⚠️ 那个坑：selector 必须**常驻**且读一个可变端口
 *
 * `HttpClients.base`（`:data`）是进程级 `by lazy`，**它会把当时的默认 `ProxySelector` 抄进自己的
 * 配置**，而 `SessionBackend.client` 又是从它 `newBuilder()` 派生的。于是「每个用例装一个新的
 * selector」只有第一条生效，后面几条仍然拿着上一条的端口（服务已停 ⇒ Connection refused）。
 * 所以 [start] 只改 `UpstreamSelector.port`，[close] 把它设回 0（= 不走代理），
 * 而 `HttpClients.base` 抄下去的那一份永远指向这个可变字段。
 *
 * ## 用法
 *
 * ```kotlin
 * FakeCampusProxy.installFakeUpstreams()  // 两件进程级前置，**必须在碰 HttpClients 之前**
 * FakeCampusProxy().start().use { fake ->
 *     ... 跑真登录 / 取数 ...
 *     fake.library.credentialPosts.get()   // 断言
 *     fake.fitness.launchCallbacks.get()   // 体测那条链真的回跳过
 *     fake.calendar.calendarJson           // 假上游手里的原文
 * }
 * ```
 *
 * ⚠️ `installFakeUpstreams()` 与 `start()` 的**先后**：前者只装 selector（端口 0 ⇒ 不走代理）
 * 与信任库，先装它不会影响任何真实请求；忘了先装，后面再装就晚了 —— `HttpClients.base` 已经把
 * 当时的 selector 与 trust manager 都抄完了（https 那条链会以 PKIX 失败收场）。
 */
class FakeCampusProxy(private val casEnabled: Boolean = true) : AutoCloseable {

    /** 图书馆座位系统 + 统一认证（可选扮演）。 */
    val library: LibraryFakeUpstream = LibraryFakeUpstream(LibraryFakeUpstream.LIBRARY_BASE, casEnabled)

    /** 校历门户。 */
    val calendar: SchoolCalendarFakeUpstream = SchoolCalendarFakeUpstream()

    /** 体测系统（`tyxylp.xjtu.edu.cn`，**https** —— 它是唯一需要 TLS 的那一个，见 [FakeUpstreamFront]）。 */
    val fitness: FitnessFakeUpstream = FitnessFakeUpstream()

    private val server: FakeUpstreamFront =
        FakeUpstreamFront(::dispatch, FitnessFakeUpstream.HOST)

    /** 代理端口（`start()` 之后有效）。 */
    val port: Int get() = server.port

    /** 起服务并把 selector 指过来。返回自身，便于 `.start().use { }`。 */
    fun start(): FakeCampusProxy = apply {
        server.start()
        UpstreamSelector.port = port
    }

    override fun close() {
        UpstreamSelector.port = 0
        server.close()
    }

    /**
     * 按目标 host 分派。两种请求形态都要认（见类 KDoc 与 [FakeUpstreamFront]）：
     *
     * - **明文**走代理：请求行是 absolute-form（`GET http://host/path HTTP/1.1`）⇒ `requestURI.host`；
     * - **TLS 隧道里**（CONNECT 之后那一条）：请求行变回 origin-form（`GET /path HTTP/1.1`），
     *   目标域名只剩在 `Host` 头里 ⇒ 回退读它。少了这一步，https 站点会被当成「没有 host」
     *   落进下面的兜底分支，表现就是一条莫名其妙的 404。
     */
    private fun dispatch(exchange: HttpExchange) {
        val host = exchange.requestURI.host?.lowercase()
            ?: exchange.requestHeaders.getFirst("Host")?.substringBefore(':')?.lowercase()
        when (host) {
            LibraryFakeUpstream.LIBRARY_HOST, LibraryFakeUpstream.CAS_HOST -> library.handle(exchange)
            FitnessFakeUpstream.HOST -> fitness.handle(exchange)
            SchoolCalendarFakeUpstream.HOST ->
                if (exchange.requestURI.path == SchoolCalendarFakeUpstream.PATH) {
                    calendar.handle(exchange)
                } else {
                    // 路径漂了要响亮地失败（否则「换了个 URL 但假上游照答」会静默成立）
                    val body = "no such calendar path: ${exchange.requestURI.path}".toByteArray()
                    exchange.sendResponseHeaders(404, body.size.toLong())
                    exchange.responseBody.write(body)
                    exchange.close()
                }
            // host 识别不出来（既没 absolute-form 也没 Host 头）⇒ 按图书馆夹具处理。
            else -> library.handle(exchange)
        }
    }

    companion object {
        /**
         * 装上两件进程级前置 —— **都必须在碰 `HttpClients`（建会话 / 建任何客户端）之前调**：
         *
         * 1. 常驻的 selector（端口 0 = 不走代理，见类 KDoc）；
         * 2. 假上游那枚自签证书的信任库（体测是 https：`OkHttpClient.Builder.build()` 那一刻
         *    就把平台 trust manager 抄进客户端，晚一步那一条链必然 PKIX 失败 ——
         *    详见 [FakeUpstreamFront] 的类 KDoc）。
         *
         * 幂等。
         */
        fun installFakeUpstreams() {
            if (ProxySelector.getDefault() !== UpstreamSelector) ProxySelector.setDefault(UpstreamSelector)
            FakeUpstreamTls.installTrustStore(FitnessFakeUpstream.HOST)
        }
    }
}

/**
 * 常驻的「把连接指到当前假上游」selector。端口 0 = 不走代理（进程默认状态）。
 *
 * ⚠️ 同一个坑在 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest` 里有一份等价的单 host 版本
 * （它先于本类存在）。两处都留着的原因：那条测试直连夹具（okhttp 拦截器重写 URL），
 * 走的不是代理这条路，收敛反而会把两种传输搅在一起。**坑本身的完整记述以本文件为准。**
 */
private object UpstreamSelector : ProxySelector() {

    @Volatile var port: Int = 0

    override fun select(uri: URI): List<Proxy> {
        val p = port
        return if (p == 0) {
            listOf(Proxy.NO_PROXY)
        } else {
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", p)))
        }
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
}
