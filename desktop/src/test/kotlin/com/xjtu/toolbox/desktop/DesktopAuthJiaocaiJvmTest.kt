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

/**
 * 教材查询（`jiaocai`）在桌面端的**装配验收**（第 24 条真数据路由）：
 * 桌面用自己的 [DesktopAuth] 真登一次，走 `DesktopSiteGate` 那条路把**教材中心**的会话建起来，
 * 再用自己的 `AppJiaocaiSource` 检索、进详情补 `ssno`。
 *
 * 与 `:data` 的 `JiaocaiApiJvmTest` 的分工：那边验字段解析（`content` 里的键值对、`ssno` 的
 * 正则口径）；这里验**桌面这一端的装配** —— `DesktopAuth` 注册了教材中心站点、会话能建起来、
 * 取数实现（`:data` 的 `AppJiaocaiSource`，两端共用）在桌面真的能跑通。
 *
 * 另一条钉住的是**如实降级**：[DesktopJiaocaiTermTextbooks] 回空表（桌面还没有教务课表缓存，
 * 「本学期我的教材」那一段在桌面上不显示）。这条断言就是那个降级的形状 —— 哪天桌面接上了课表
 * 缓存，它会红，提醒把这一段补上。
 *
 * 手法同 `DesktopAuthJiaocai1JvmTest`：`:testkit` 假上游 + URL 一个字符不改。
 */
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
    fun `本学期我的教材：桌面没有教务课表缓存 ⇒ 端口如实回空表`() = runBlocking {
        assertTrue(
            DesktopJiaocaiTermTextbooks.currentTerm().isEmpty(),
            "桌面这一端还没有课表缓存 ⇒ 那一段整段不显示（如实降级，不是漏做）",
        )
    }
}
