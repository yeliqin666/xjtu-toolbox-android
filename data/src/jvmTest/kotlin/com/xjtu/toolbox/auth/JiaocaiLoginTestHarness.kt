package com.xjtu.toolbox.auth

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

/**
 * 教材中心（`jiaocai.lib.xjtu.edu.cn`）站点的**钉住测试**用的登录骨架 —— `jiaocai1`
 * （教材全文库）那一条路由借的就是这个站点的会话（见 [JiaocaiSession] 的 KDoc）。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 与 `withLmsLogin` / `withCouponLogin` 逐条同由：那条链扎在 [SiteSession.executeWithReAuth]
 * 上（`final`，打不了桩）。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的
 * [FakeCampusProxy]，按 host 分派 `login.xjtu.edu.cn`(CAS) / `jiaocai.lib.xjtu.edu.cn`(登录入口)
 * / `jiaocai1.lib.xjtu.edu.cn`(业务)）+ 真 [SessionManager] + 真 [JiaocaiSession]/`JiaocaiLogin`：
 *
 * ```
 * GET  https://jiaocai.lib.xjtu.edu.cn/entry/login → 302 http://login.xjtu.edu.cn/cas/login?service=…（登录页扮演：LibraryFakeUpstream）
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 https://jiaocai.lib.xjtu.edu.cn/entry/login?ticket=ST-n
 * GET  https://jiaocai.lib.xjtu.edu.cn/entry/login?ticket=ST-n → 200 + Set-Cookie（教材中心的会话落下来）
 * ```
 *
 * ⚠️ 全文库（`jiaocai1.lib`）那一半的会话**不在这一步**：它的业务接口在会话没建立时返回一张
 * `var logoutUrl=…` 的跳转页，由 `:data` 的 `Jiaocai1Api.fetch` + `PortalRedirect` 自己跟完
 * （本夹具把那一跳也扮了）。所以每个用例都会看到那条链真的跑过一遍 —— 这正是它值得被测的原因。
 *
 * ## 进程级的那三个坑（与其余骨架逐条相同）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（常驻 selector + 自签证书的信任库）；
 * 2. cookie jar 与 `secureKeyValueStore` 是进程级缓存的 ⇒ 上一轮的内存态与文件都要清；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 */
internal fun withJiaocaiLogin(block: (site: SiteSession, fake: FakeCampusProxy) -> Unit) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-jiaocai-login-test").toFile()
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
            cachedRsaKey = TestRsaKey.publicKeyBase64
            register(JiaocaiSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        val site = runBlocking { manager.ensureSite(JiaocaiSession.SITE_KEY, userInitiated = true) }
        check(site.hasLogin) { "假上游上的真登录应当成功" }
        block(site, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}
