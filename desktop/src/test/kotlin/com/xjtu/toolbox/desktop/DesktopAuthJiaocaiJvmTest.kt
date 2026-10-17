package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.jiaocai.DesktopJiaocaiTermTextbooks
import com.xjtu.toolbox.jiaocai.AppJiaocaiSource
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
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

/** 教材查询（`jiaocai`）在桌面端的装配验收。 */
class DesktopAuthJiaocaiJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-jiaocai-test").toFile()
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
    fun `真登录之后：桌面自己能查教材，进详情能补出全文库的 ssno`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")

        // 路由进门的那条路（ToolboxDesktopApp 的 DesktopSiteGate）：教材中心会话按需建
        runBlocking { auth.ensureSession(DesktopAuth.JIAOCAI_SITE_KEY) }
        val site = assertNotNull(
            auth.sessionManager.getSiteOrNull(DesktopAuth.JIAOCAI_SITE_KEY),
            "教材中心站点应已注册并拿到会话（站点 key = `jiaocai`）",
        )

        val source = AppJiaocaiSource(site)
        val books = runBlocking { source.search("高等数学") }
        val book = assertNotNull(books.singleOrNull(), "假上游给一行书目")
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE, book.title, "书名取 `title` 里的富文本（标签已剥）")
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_AUTHOR, book.author)
        assertEquals("高等数学", fake.jiaocai1.lastJiaocaiKeyword, "关键词原样进 `keyWord=`")

        // 列表里的 `content` 只有「获取方式一：本地全文」这句文字（与真站一致）
        assertTrue(book.hasFullText, "有那条文字 ⇒ 屏上会亮「本地全文」")
        assertEquals(null, book.ssno, "列表接口不给 ssno")

        // 进详情才补 ssno（`PortalRedirect` 那一套在这条链上同样跑得通）
        assertEquals(
            Jiaocai1FakeUpstream.SSNO,
            runBlocking { source.fetchSsno(book) },
            "详情页里的 `本地全文` 链接带着 jiaocai1 的 ssno",
        )
        assertTrue(fake.jiaocai1.jiaocaiSearchCalls.get() > 0, "检索真打过上游")
        assertTrue(fake.jiaocai1.jiaocaiDetailCalls.get() > 0, "详情真打过上游")
    }

    @Test
    fun `本学期我的教材：这一端还没接课表缓存 ⇒ 端口如实回空表`() = runBlocking {
        assertTrue(
            DesktopJiaocaiTermTextbooks.currentTerm().isEmpty(),
            "桌面这一端有课表缓存了（`schedule_cache<账号后缀>`），但本对象还没接那条缓存 " +
                "⇒ 那一段整段不显示（如实降级，不是漏做；见 DesktopJiaocaiTermTextbooks 的 TODO）",
        )
    }
}
