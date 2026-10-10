package com.xjtu.toolbox

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream
import com.xjtu.toolbox.fitness.FitnessFakeUpstream
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.venue.VenueFakeUpstream

/**
 * 把假的校园上游起成**一个本地端口**，按请求行里的 **host** 分派给各个夹具（裸 TCP 前置 + 明文/TLS
 * 两个后端，为什么不是一个 `HttpServer` 就够见 [FakeUpstreamFront]）。
 *
 * ## 为什么是「一个端口 + 按 host 分派」而不是「一个上游一个端口」
 *
 * 因为**客户端那边只认一个默认 `ProxySelector`**（而且它还是进程级、只读一次的，见下面）。一个
 * 端口同时扮演 `rg.lib.xjtu.edu.cn` / `login.xjtu.edu.cn` / `tyxylp.xjtu.edu.cn`（https）/
 * `ncard.xjtu.edu.cn`（https）/ `jwxt.xjtu.edu.cn`（https）/ `js.xjtu.edu.cn`（https，空闲教室的实时状态）/
 * 校历门户 / `gh-release.xjtutoolbox.com`（https，空闲教室的课表快照 CDN），
 * 就绕开了「按 host 选端口」那一层，也最接近真机上「所有域名都从同一条网络出去」的样子。
 * 走代理时请求行是 absolute-form（`GET http://host/path HTTP/1.1`），所以 `requestURI.host`
 * 就是目标域名；https 那一条在 TLS 隧道里会变回 origin-form，域名只剩在 `Host` 头里 ——
 * 两者都认（见 [dispatch]）。分派靠 host，而不是靠路径。
 *
 * ## ⚠️ 那个坑：selector 必须**常驻**且读一个可变端口
 *
 * `HttpClients.base`（`:data`）是进程级 `by lazy`，**它会把当时的默认 `ProxySelector` 抄进自己的
 * 配置**，而 `SessionBackend.client` 又是从它 `newBuilder()` 派生的。于是「每个用例装一个新的
 * selector」只有**先碰 `HttpClients` 的那个类**生效，后面几条仍然拿着上一条的端口（服务已停 ⇒
 * Connection refused）。所以 [start] 只改 `FakeUpstreamProxySelector.port`，[close] 把它设回 0
 * （= 不走代理）：全进程只有**这一份** selector，谁先把 `HttpClients.base` 建出来都不影响结论。
 * 坑的完整记述与「为什么不能一个测试类一份」见 [FakeUpstreamProxySelector] 的 KDoc。
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

    /** 体测系统（`tyxylp.xjtu.edu.cn`，**https**，见 [FakeUpstreamFront]）。 */
    val fitness: FitnessFakeUpstream = FitnessFakeUpstream()

    /**
     * 教务系统（`jwxt.xjtu.edu.cn`，**https**）：全校课表 + 成绩报表两条链共用这一个上游。
     * 登录那半台 CAS 由 [library] 扮演（见 [JwxtFakeUpstream] 的类 KDoc）。
     */
    val jwxt: JwxtFakeUpstream = JwxtFakeUpstream()

    /**
     * 校园卡（`ncard.xjtu.edu.cn`，**https**）：卡面 + 流水两条取数共用一个上游。
     * 登录那半台 CAS 同样由 [library] 扮演（见 [CampusCardFakeUpstream] 的类 KDoc）。
     */
    val campusCard: CampusCardFakeUpstream = CampusCardFakeUpstream()

    /**
     * 空闲教室（第 10 条真数据路由）：
     * - 智慧教室平台 `js.xjtu.edu.cn`（https）= 「实时状态」那一档；
     * - 课表快照 CDN `gh-release.xjtutoolbox.com`（https）= 「CDN 课表」那一档。
     *
     * 两面都由 [EmptyRoomFakeUpstream] 扮（两个域名，一个夹具 —— 它们属于同一条路由的两档取数，
     * 见那个类的 KDoc）；第三档「直查教务」在 [jwxt] 里（同一个教务域名）。
     */
    val emptyRoom: EmptyRoomFakeUpstream = EmptyRoomFakeUpstream()

    /**
     * 体育场馆（第 11 条真数据路由）：
     * - 开放平台 `org.xjtu.edu.cn`（https）= 登录入口那一跳（302 到统一认证）；
     * - 场馆站 `202.117.17.144:8080`（**明文 http**）= 业务与登录探针。
     *
     * 两面都由 [VenueFakeUpstream] 扮（两个域名，一个夹具 —— 它们属于同一条登录链，
     * 见那个类的 KDoc）；CAS 那半台在 [library] 里。⚠️ 分派只看 host，所以 80 端口那台
     * 支付站也落在这里（那座站点只被拼进 URL，不真请求）。
     */
    val venue: VenueFakeUpstream = VenueFakeUpstream()
    private val server: FakeUpstreamFront =
        FakeUpstreamFront(::dispatch, HTTPS_HOSTS)

    /** 代理端口（`start()` 之后有效）。 */
    val port: Int get() = server.port

    /** 起服务并把 selector 指过来。返回自身，便于 `.start().use { }`。 */
    fun start(): FakeCampusProxy = apply {
        server.start()
        FakeUpstreamProxySelector.port = port
    }

    override fun close() {
        FakeUpstreamProxySelector.port = 0
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
            JwxtFakeUpstream.HOST -> jwxt.handle(exchange)
            CampusCardFakeUpstream.HOST -> campusCard.handle(exchange)
            EmptyRoomFakeUpstream.JS_HOST -> emptyRoom.handleJs(exchange)
            EmptyRoomFakeUpstream.CDN_HOST -> emptyRoom.handleCdn(exchange)
            VenueFakeUpstream.OAUTH_HOST -> venue.handleOauth(exchange)
            VenueFakeUpstream.HOST -> venue.handle(exchange)
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
         * 1. 常驻的 selector（端口 0 = 不走代理，见 [FakeUpstreamProxySelector]）；
         * 2. 假上游那枚自签证书的信任库（体测与教务都是 https：`OkHttpClient.Builder.build()` 那一刻
         *    就把平台 trust manager 抄进客户端，晚一步那一条链必然 PKIX 失败 ——
         *    详见 [FakeUpstreamFront] 的类 KDoc）。
         *
         * 幂等。
         */
        fun installFakeUpstreams() {
            FakeUpstreamProxySelector.install()
            FakeUpstreamTls.installTrustStore(HTTPS_HOSTS)
        }

        /**
         * 需要 CONNECT 隧道的那几个域名 —— 一枚证书带这几个 SAN（信任库是单个系统属性，只能装一份）。
         */
        private val HTTPS_HOSTS = setOf(
            FitnessFakeUpstream.HOST,
            JwxtFakeUpstream.HOST,
            CampusCardFakeUpstream.HOST,
            // 统一认证：绝大多数登录器把会话管家缓存的公钥传进来（不会走这条路），
            // 但 `CampusCardLogin` 是唯一不收 `cachedRsaKey` 的那个 ⇒ 它每次登录都会取一次
            // `https://login.xjtu.edu.cn/cas/jwt/publicKey`（见 `LibraryFakeUpstream.PUBLIC_KEY_PATH`）。
            LibraryFakeUpstream.CAS_HOST,
            // 空闲教室那两档：实时状态的智慧教室平台与课表快照 CDN（都是 https）。
            // ⚠️ 多一个域名就多一个 SAN —— 该集合变了，自签证书会**重生一份**（见 `FakeUpstreamTls`），
            // 而不是把旧的叠上去。
            EmptyRoomFakeUpstream.JS_HOST,
            EmptyRoomFakeUpstream.CDN_HOST,
            // 体育场馆的登录入口那一跳（`org.xjtu.edu.cn` 的 OAuth 授权页）；业务那半台是**明文**
            // `202.117.17.144:8080`，不走隧道，所以不在这里。
            VenueFakeUpstream.OAUTH_HOST,
        )
    }
}

