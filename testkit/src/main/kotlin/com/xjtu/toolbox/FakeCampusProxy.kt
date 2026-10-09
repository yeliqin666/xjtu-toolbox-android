package com.xjtu.toolbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executors

/**
 * 把假的校园上游起成一个**本地 HTTP 代理**：一个 HttpServer，按请求行里的 **host** 分派给各个夹具。
 *
 * ## 为什么是「一个代理 + 按 host 分派」而不是「一个上游一个端口」
 *
 * 因为**客户端那边只认一个默认 `ProxySelector`**（而且它还是进程级、只读一次的，见下面）。一个
 * 代理端口同时扮演 `rg.lib.xjtu.edu.cn` / `login.xjtu.edu.cn` / `workflow.xjtu.edu.cn`，就绕开了
 * 「按 host 选端口」那一层，也最接近真机上「所有域名都从同一条网络出去」的样子。
 * 走代理时请求行是 absolute-form（`GET http://host/path HTTP/1.1`），所以 `requestURI.host`
 * 就是目标域名 —— 分派靠它，而不是靠路径。
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
 * FakeCampusProxy.installProxySelector()   // 必须在**碰 HttpClients 之前**（也就是建任何会话之前）
 * FakeCampusProxy().start().use { fake ->
 *     ... 跑真登录 / 取数 ...
 *     fake.library.credentialPosts.get()   // 断言
 *     fake.calendar.calendarJson           // 假上游手里的原文
 * }
 * ```
 *
 * ⚠️ `installProxySelector()` 与 `start()` 的**先后**：前者只装 selector（端口 0 ⇒ 不走代理），
 * 所以先装它不会影响任何真实请求；忘了先装，后面再装就晚了（`HttpClients.base` 已经抄完了）。
 */
class FakeCampusProxy(private val casEnabled: Boolean = true) : AutoCloseable {

    /** 图书馆座位系统 + 统一认证（可选扮演）。 */
    val library: LibraryFakeUpstream = LibraryFakeUpstream(LibraryFakeUpstream.LIBRARY_BASE, casEnabled)

    /** 校历门户。 */
    val calendar: SchoolCalendarFakeUpstream = SchoolCalendarFakeUpstream()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** 代理端口（`start()` 之后有效）。 */
    val port: Int get() = server.address.port

    /** 起服务并把 selector 指过来。返回自身，便于 `.start().use { }`。 */
    fun start(): FakeCampusProxy = apply {
        server.createContext("/", ::dispatch)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        UpstreamSelector.port = port
    }

    override fun close() {
        UpstreamSelector.port = 0
        server.stop(0)
    }

    /** 按目标 host 分派（见类 KDoc：代理请求的 `requestURI` 是 absolute-form）。 */
    private fun dispatch(exchange: HttpExchange) {
        when (exchange.requestURI.host?.lowercase()) {
            LibraryFakeUpstream.LIBRARY_HOST, LibraryFakeUpstream.CAS_HOST -> library.handle(exchange)
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
            // 没有 host（origin-form：不是经代理、而是直连到本机端口）⇒ 按图书馆夹具处理，
            // `:data:jvmTest` 那条「直连会话」的测试就是直连用法。
            else -> library.handle(exchange)
        }
    }

    companion object {
        /**
         * 装上常驻 selector。**必须在碰 `HttpClients`（建会话 / 发第一个请求）之前调** —— 见类 KDoc。
         * 幂等：只是把默认 selector 换成同一个单例。
         */
        fun installProxySelector() {
            if (ProxySelector.getDefault() !== UpstreamSelector) ProxySelector.setDefault(UpstreamSelector)
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
