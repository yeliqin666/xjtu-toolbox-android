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
 * 智慧教室平台（`js.xjtu.edu.cn`）的**钉住测试**用的登录骨架 —— 空闲教室「实时状态」那一档要它。
 *
 * ## 为什么是「真登录」而不是给会话打桩
 *
 * 那条链扎在 [SiteSession.executeWithReAuth] 上（它被 CAS 状态机、cookie 罐、重认证重放包着），
 * 而它是 `final` 的 —— 打不了桩。所以这里走**与生产完全同一条**路：假上游（`:testkit` 的
 * [FakeCampusProxy]，按 host 分派 `js.xjtu.edu.cn`）+ 真 [SessionManager] + 真
 * [JsSession]/`JsLogin`。请求 URL 一个字符都不改，走的是完整 CAS：CAS 入口页（service 指向
 * `https://js.xjtu.edu.cn/`）→ 表单 POST（密码 RSA 加密）→ 种 TGC → 签 ticket → 回跳 js 入口
 * → `POST /server/cas/loginCas` 用票换 `TOKEN-AUTH`。站点也是 **https**（假上游的 `HTTPS_HOSTS`
 * 里有它，走 CONNECT + 自签证书）。
 *
 * 与 [withCampusCardLogin] / [withJwxtLogin] 是同一套骨架，差别只有注册的站点与临时目录名。
 *
 * ## 与桌面端那条验收的关系（不是重复）
 *
 * `:desktop:test` 验的是**桌面端的装配**（窗口里输凭据 → `DesktopAuth` → `AppEmptyRoomSource`
 * 自己 `ensureSite("js")`）；这里验的是**取数本身**：`LiveRoomApi` 对着夹具的字段级口径
 * （楼顺序、四种状态、请求里那两个头）。夹具是同一份，断言各管一段。
 *
 * ## 进程级的那三个坑（照 [withJwxtLogin] 写）
 *
 * 1. 两件进程级前置必须**在碰 `HttpClients` 之前**装（`installFakeUpstreams()`：常驻 selector +
 *    自签证书的信任库 —— `OkHttpClient.Builder.build()` 那一刻就把它俩抄进客户端了）；
 * 2. cookie jar 与 `secureKeyValueStore` 是**进程级**缓存的 ⇒ 必须把上一轮的内存态与文件一起清，
 *    否则上一条测试留下的 cookie 会把「真登录」变成 SSO 直通；
 * 3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）。
 *
 * 传进去的 [FakeCampusProxy] 是给断言用的（请求原文、各类计数都在它身上）。
 */
internal fun withJsLogin(block: (site: SiteSession, fake: FakeCampusProxy) -> Unit) {
    val fake = FakeCampusProxy(casEnabled = true)

    dataRootOverride = Files.createTempDirectory("xjtu-js-login-test").toFile()
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
            // `https://login.xjtu.edu.cn/cas/jwt/publicKey` 取，而那个路径不在夹具里。
            cachedRsaKey = TestRsaKey.publicKeyBase64
            register(JsSession())
            setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        val site = runBlocking { manager.ensureSite(JsSession.SITE_KEY, userInitiated = true) }
        check(site.hasLogin) { "假上游上的真登录应当成功" }
        block(site, fake)
    } finally {
        fake.close()
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }
}
