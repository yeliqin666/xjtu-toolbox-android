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
 * 宿舍电费站点（`ssn.xjtu.edu.cn`）的**钉住测试**用的登录骨架 —— 第 17 条真数据路由
 * （桌面端要自己登 `ssn`、自己取缴费页入口与电量）。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 与 `withCouponLogin` / `withDzpzLogin` 逐条同由：那条链扎在 [SiteSession.executeWithReAuth]
 * 上（`final`，打不了桩）。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的
 * [FakeCampusProxy]，按 host 分派 `login.xjtu.edu.cn`(CAS) / `org.xjtu.edu.cn`(开放平台) /
 * `ssn.xjtu.edu.cn`(缴费页与接口)）+ 真 [SessionManager] + 真 [SsnSession]/`SsnLogin`：
 *
 * ```
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759&redirectUri=http://ssn.xjtu.edu.cn/cems/index/mobile/pay&…
 *       → 302 http://login.xjtu.edu.cn/cas/login?service=<authorize 地址>（登录页扮演：LibraryFakeUpstream）
 * POST http://login.xjtu.edu.cn/cas/login?service=…           → 种 TGC → 302 回 authorize?ticket=ST-n
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?…&ticket=ST-n → 302 http://ssn.xjtu.edu.cn/cems/index/mobile/pay?code=SSN-fake-1
 * GET  http://ssn.xjtu.edu.cn/cems/index/mobile/pay?code=…     → 200（HTML 里内嵌 cid，`SsnLogin.postLogin` 读它）
 * ```
 *
 * ## 进程级的那三个坑（与其余骨架逐条相同）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（`installFakeUpstreams()`：常驻 selector +
 *    自签证书的信任库）；
 * 2. cookie jar 与 `secureKeyValueStore` 是**进程级**缓存的 ⇒ 必须把上一轮的内存态与文件一起清；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 *
 * 传进去的 [FakeCampusProxy] 是给断言用的（请求原文、各类计数都在它身上）。
 */
internal fun withSsnLogin(block: (site: SiteSession, fake: FakeCampusProxy) -> Unit) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-ssn-login-test").toFile()
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
            register(SsnSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        val site = runBlocking { manager.ensureSite(SsnSession.SITE_KEY, userInitiated = true) }
        check(site.hasLogin) { "假上游上的真登录应当成功" }
        block(site, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}