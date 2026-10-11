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
 * 思源学堂站点（`lms.xjtu.edu.cn`）的**钉住测试**用的登录骨架 —— 第 18 条真数据路由
 * （桌面端要自己登 lms、自己取课程/活动/详情）。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 与 `withSsnLogin` / `withCouponLogin` 逐条同由：那条链扎在 [SiteSession.executeWithReAuth]
 * 上（`final`，打不了桩）。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的
 * [FakeCampusProxy]，按 host 分派 `login.xjtu.edu.cn`(CAS) / `lms.xjtu.edu.cn`(思源学堂)）
 * + 真 [SessionManager] + 真 [LmsSession]/`LmsLogin`：
 *
 * ```
 * GET  https://lms.xjtu.edu.cn/  → 302 http://login.xjtu.edu.cn/cas/login?service=https://lms.xjtu.edu.cn/（登录页扮演：LibraryFakeUpstream）
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 https://lms.xjtu.edu.cn/?ticket=ST-n
 * GET  https://lms.xjtu.edu.cn/?ticket=ST-n → 200 + Set-Cookie LMS_SESSION（`LmsLogin.postLogin` 判「最终停在 lms 上」）
 * ```
 *
 * 与 dwzp/coupon 不同，lms **没有自己的独立登录页**：入口就是它自己的根路径，直接 CAS 回跳
 *（与教务同型）。这条省略在这里就是事实本体，不是简化。
 *
 * ## 进程级的那三个坑（与其余骨架逐条相同）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（`installFakeUpstreams()`：常驻 selector +
 *    自签证书的信任库）；
 * 2. cookie jar 与 `secureKeyValueStore` 是**进程级**缓存的 ⇒ 必须把上一轮的内存态与文件一起清；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 */
internal fun withLmsLogin(block: (site: SiteSession, fake: FakeCampusProxy) -> Unit) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-lms-login-test").toFile()
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
            register(LmsSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        val site = runBlocking { manager.ensureSite(LmsSession.SITE_KEY, userInitiated = true) }
        check(site.hasLogin) { "假上游上的真登录应当成功" }
        block(site, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}