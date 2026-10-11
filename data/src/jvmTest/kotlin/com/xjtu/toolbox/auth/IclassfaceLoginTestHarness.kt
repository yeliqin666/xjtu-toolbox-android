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
 * 人脸识别签到（`iclassface.xjtu.edu.cn`）那条链的**钉住测试**用的登录骨架 —— 第 20 条真数据路由。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 与 `withLmsLogin` / `withJwappLogin` 逐条同由：那条链扎在 [SiteSession.executeWithReAuth] 上
 * （`final`，打不了桩）。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的 [FakeCampusProxy]，
 * 按 host + `redirect_uri` 分派 `login.xjtu.edu.cn`(CAS) / `org.xjtu.edu.cn`(开放平台 authorizesw) /
 * `iclassface.xjtu.edu.cn`(业务站)）+ 真 [SessionManager] + 真 [IclassfaceSession]/`IclassfaceLogin`：
 *
 * ```
 * GET  https://login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=1543&redirect_uri=<org authorizesw>
 *        →（没 TGC）302 /cas/login?service=…（登录页在 LibraryFakeUpstream）
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 authorize?ticket=ST-n → 签 code
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorizesw?redirect_uri=<iclassface 回调>&code=…
 *        → 302 https://iclassface.xjtu.edu.cn/oauth/callback/xjtu_new?code=…
 * GET  https://iclassface.xjtu.edu.cn/oauth/callback/xjtu_new?code=… → 种 PHPSESSID → 302 /face/detect
 * GET  https://iclassface.xjtu.edu.cn/face/detect → 200（`IclassfaceLogin.postLogin` 判「落在本站」）
 * ```
 *
 * `mustUseWebVpn = true` 的站点在桌面上也是这么登的（真机上校外走 WebVPN 网关，测试里
 * `AccessMode` 是 NORMAL —— 与考勤那条链同一口径，见 `IclassfaceFakeUpstream` 的 KDoc）。
 *
 * ## 进程级的那三个坑（与其余骨架逐条相同）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（`installFakeUpstreams()`：常驻 selector +
 *    自签证书的信任库）；
 * 2. cookie jar 与 `secureKeyValueStore` 是**进程级**缓存的 ⇒ 必须把上一轮的内存态与文件一起清；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 */
internal fun withIclassfaceLogin(block: (site: SiteSession, fake: FakeCampusProxy) -> Unit) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-iclassface-login-test").toFile()
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
            register(IclassfaceSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        val site = runBlocking { manager.ensureSite(IclassfaceSession.SITE_KEY, userInitiated = true) }
        check(site.hasLogin) { "假上游上的真登录应当成功" }
        block(site, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}
