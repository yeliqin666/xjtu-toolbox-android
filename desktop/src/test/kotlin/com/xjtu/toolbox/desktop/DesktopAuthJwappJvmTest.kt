package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.jwapp.AppJwappScoreSource
import com.xjtu.toolbox.jwapp.JwappFakeUpstream
import com.xjtu.toolbox.jwapp.ScoreSource
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 移动教务成绩在桌面端的装配验收。 */
class DesktopAuthJwappJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-jwapp-test").toFile()
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
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
            block()
        } finally {
            fake.close()
            dataRootOverride = null
        }
    }

    private fun newAuth(): DesktopAuth {
        val store = JvmCredentialStore()
        store.rsaPublicKey = TestRsaKey.publicKeyBase64
        return DesktopAuth(store)
    }

    private fun login(auth: DesktopAuth): Boolean {
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = LibraryFakeUpstream.PASSWORD
        return runBlocking { auth.login() }
    }

    @Test
    fun `真登录之后：移动教务自己走完开放平台那一跳，成绩与精确成绩都读得出`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")
        assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")

        // 路由进门的那条路（ToolboxDesktopApp 的 DesktopSiteGate）：jwapp 会话按需建
        runBlocking { auth.ensureSession(DesktopAuth.JWAPP_SITE_KEY) }
        val site = auth.sessionManager.getSiteOrNull(DesktopAuth.JWAPP_SITE_KEY)
        assertNotNull(site, "jwapp 站点应已注册并拿到会话")
        assertTrue(site.hasLogin, "jwapp 会话应当真的建起来")

        // 成绩屏的两台上游：jwapp 那份粗分 + 教务的精确总评（两个站点各一份会话）
        val source = AppJwappScoreSource(auth.sessionManager)
        val terms = runBlocking { source.getGrade(null) }
        val coarse = terms.single().scoreList.single()
        assertEquals(JwappFakeUpstream.COURSE_NAME, coarse.courseName)
        assertEquals(JwappFakeUpstream.COURSE_SCORE.toDouble(), coarse.scoreValue, "jwapp 给的是粗分")
        assertEquals(ScoreSource.JWAPP, coarse.source)
        // 业务请求带的是回跳 URL 里抠出来的那枚令牌（登录落点那一枪不带 Authorization，所以这里才判）
        assertEquals(JwappFakeUpstream.TOKEN, fake.jwapp.lastAuthorization, "业务请求要带回调 URL 里那枚令牌")

        val precise = runBlocking { source.preciseScores() }
        val exact = precise.first { it.courseName == JwappFakeUpstream.COURSE_NAME }
        assertEquals(JwxtFakeUpstream.CJCX_ZCJ, exact.zcj, "教务给的是精确总评")
        assertEquals(JwxtFakeUpstream.CJCX_XFJD, exact.xfjd)
        // ⚠️ 桌面这一屏 `cache` 传 null（没有按账号分文件的 `DataCache`）—— 由
        // `ToolboxDesktopApp` 那一条路由传，这里验的是**取数与两个站点**而不是屏的装配。
    }
}
