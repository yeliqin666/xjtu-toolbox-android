package com.xjtu.toolbox.auth

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/**
 * 消息收纳那一路（一网通办站点 + 收纳四路取数）的**钉住测试**共用的登录骨架 ——
 * 第 12 条真数据路由（桌面端要自己登一网通办、自己去那四路取数）。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 那条链扎在 [SiteSession.executeWithReAuth] 上（它被 CAS 状态机、cookie 罐、重认证重放包着），
 * 而它是 `final` 的 —— 打不了桩。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的
 * [FakeCampusProxy]，按 host 分派 `ywtb.xjtu.edu.cn` 与收纳那四路的域名）+ 真 [SessionManager]
 * + 真 [YwtbSession]/`YwtbLogin`。请求 URL 一个字符都不改，走的是完整 CAS：
 *
 * ```
 * GET  https://login.xjtu.edu.cn/cas/login?service=<一网通办门户>   → 登录表单
 * POST 同一地址（凭据 RSA 加密，由 LibraryFakeUpstream 扮演）
 *                                    → 种 TGC → 302 回跳门户，`ticket=` 是一枚 **JWT**
 * GET  https://ywtb.xjtu.edu.cn/?path=…&ticket=<JWT>               → 门户页 200（登录终点）
 * ```
 *
 * ⚠️ 一网通办与别处唯一的差别就是那颗 ticket 的形状：普通站点拿到 `ST-n`，它拿到的是
 * 「payload 里有 `idToken` 的 JWT」（`YwtbLogin.postLogin` 解的就是它）—— 所以那半台 CAS
 * 会对这个 service 照真实形状签一枚，见 `LibraryFakeUpstream.ticketFor`。
 *
 * 与 `withJwxtLogin` / `withVenueLogin` / `withCampusCardLogin` 是同一套骨架，差别只有注册的站点
 * 与临时目录名。传给 block 的是**会话管家**（这一屏的取数源收的是管家，不是某个站点）。
 *
 * ## 进程级的那三个坑（与其余骨架逐条相同）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（`installFakeUpstreams()`：常驻 selector +
 *    自签证书的信任库 —— `OkHttpClient.Builder.build()` 那一刻就把它俩抄进客户端了）；
 * 2. cookie jar 与 `secureKeyValueStore` 是**进程级**缓存的 ⇒ 必须把上一轮的内存态与文件一起清，
 *    否则上一条测试留下的 cookie 会把「真登录」变成 SSO 直通；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 */
internal fun withYwtbLogin(block: (manager: SessionManager, fake: FakeCampusProxy) -> Unit) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-ywtb-login-test").toFile()
    val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
    for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
        wipeSecureStore("${base}_default")
        wipeSecureStore("$base$suffix")
    }
    for (name in listOf(
        "cookies_normal_default", "cookies_webvpn_default",
        "cookies_normal$suffix", "cookies_webvpn$suffix",
    )) {
        PersistentCookieJar(name).clear()
    }
    AccountContext.activeAccountId = null

    FakeCampusProxy.installFakeUpstreams()
    fake.start()
    try {
        val manager = SessionManager().apply {
            // 照真机路径预置「缓存过的 RSA 公钥」：没有它 `XJTULogin` 会去
            // `https://login.xjtu.edu.cn/cas/jwt/publicKey` 取（本夹具也答，但真机上那份是首次登录取回、
            // 存进凭据文件后复用的）。
            cachedRsaKey = TestRsaKey.publicKeyBase64
            register(YwtbSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        // 登录成功那一枪要交给宿主（`:app` = `CampusProbe.ywtbToken`）—— 令牌搬进 :data 之后
        // 走的就是这条缝，所以这里在登录**之前**挂上。挂在后面等于没验。
        val handedTokens = CopyOnWriteArrayList<String>()
        manager.onYwtbToken = { handedTokens += it }
        val site = runBlocking { manager.ensureSite(YwtbSession.SITE_KEY, userInitiated = true) }
        check(site.hasLogin) { "假上游上的真登录应当成功" }
        check(handedTokens == listOf(com.xjtu.toolbox.ywtb.YwtbFakeUpstream.ID_TOKEN)) {
            "CAS 回跳那枚 JWT 里的 idToken 应当经 onYwtbToken 交给宿主：$handedTokens"
        }
        // 四路取数都只认这颗令牌：夹具会把它原样记下来，测试里直接断言那次集成。
        block(manager, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}
