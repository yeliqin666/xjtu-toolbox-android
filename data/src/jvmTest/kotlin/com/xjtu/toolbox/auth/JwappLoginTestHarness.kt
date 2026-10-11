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
 * 移动教务（`jwapp.xjtu.edu.cn`）成绩那条链的**钉住测试**用的登录骨架 —— 第 19 条真数据路由。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 与 `withLmsLogin` / `withJwxtLogin` 逐条同由：那条链扎在 [SiteSession.executeWithReAuth] 上
 * （`final`，打不了桩）。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的 [FakeCampusProxy]，
 * 按 host/appId 分派 `org.xjtu.edu.cn`(开放平台) / `login.xjtu.edu.cn`(CAS) / `jwapp.xjtu.edu.cn`
 * (移动教务) / `jwxt.xjtu.edu.cn`(教务)）+ 真 [SessionManager] + 真 [JwappSession]/`JwappLogin`：
 *
 * ```
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=…  → 302 到统一认证
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 authorize?ticket=ST-n
 * GET  …/authorize?…&ticket=ST-n → 302 http://jwapp.xjtu.edu.cn/app/index?token=<令牌>
 * GET  https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time → 200（`validateLogin` 那一枪）
 * ```
 *
 * ## 为什么同时把**教务**也登起来
 *
 * 成绩屏有两台上游：`jwapp`（学期粗分）与 `jwxt`（`xscjcx.do` 的精确总评）。两个站点各有一份会话，
 * 屏上的编排（`JwappScoreViewModel.fetch`）正是「两个都拿到才做替换」，所以这套骨架两个都登 ——
 * 与真机上 `AppRoute.JwappScore.loginType` 只要求 jwapp、而教务由别处保证的情形**不完全一样**，
 * 这里是为了让编排那一半也能在真登录之上验（装配层面的差异见 `:desktop` 那条验收）。
 *
 * ## 进程级的那三个坑（与其余骨架逐条相同）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（`installFakeUpstreams()`：常驻 selector +
 *    自签证书的信任库）；
 * 2. cookie jar 与 `secureKeyValueStore` 是**进程级**缓存的 ⇒ 必须把上一轮的内存态与文件一起清；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 */
internal fun withJwappLogin(
    block: (jwapp: SiteSession, jwxt: SiteSession, fake: FakeCampusProxy) -> Unit,
) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-jwapp-login-test").toFile()
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
            register(JwappSession())
            register(JwxtSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        val jwapp = runBlocking { manager.ensureSite(JwappSession.SITE_KEY, userInitiated = true) }
        check(jwapp.hasLogin) { "假上游上的移动教务真登录应当成功" }
        val jwxt = runBlocking { manager.ensureSite("jwxt", userInitiated = true) }
        check(jwxt.hasLogin) { "假上游上的教务真登录应当成功" }
        block(jwapp, jwxt, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}
