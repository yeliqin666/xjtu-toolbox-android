package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.iclassface.AppIclassfaceSource
import com.xjtu.toolbox.iclassface.IclassfaceFakeUpstream
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
import kotlinx.datetime.LocalDate

/** 快速考勤流水（人脸识别签到）在桌面端的装配验收。 */
class DesktopAuthIclassfaceJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-iclassface-test").toFile()
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

    @Test
    fun `真登录之后：人脸识别签到自己走完 OAuth2 中转，记录读得出、空表不炸`() = withFakeCampus {
        val auth = newAuth()
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = LibraryFakeUpstream.PASSWORD
        assertTrue(runBlocking { auth.login() }, "用假上游的账号密码应当登得上：${auth.loginState}")

        runBlocking { auth.ensureSession(DesktopAuth.ICLASSFACE_SITE_KEY) }
        val site = auth.sessionManager.getSiteOrNull(DesktopAuth.ICLASSFACE_SITE_KEY)
        assertNotNull(site, "iclassface 站点应已注册并拿到会话")
        assertTrue(site.hasLogin, "iclassface 会话应当真的建起来")
        assertTrue(fake.iclassface.orgCallbacks.get() > 0, "应当经过开放平台 authorizesw 那一跳")

        val source = AppIclassfaceSource(site)
        val records = runBlocking { source.fetchRecords(LocalDate(2026, 6, 4)) }
        assertEquals(1, records.size, "夹具一行六格算一条（残缺行不计）")
        assertEquals(IclassfaceFakeUpstream.RECORD_LOCATION, records.single().location)
        assertEquals("2026-06-04", fake.iclassface.lastDate)

        // 当天没有记录：空表是「那天没有任何记录」，不是错误
        fake.iclassface.emptyRecords = true
        assertTrue(runBlocking { source.fetchRecords(LocalDate(2026, 6, 5)) }.isEmpty())
    }
}
