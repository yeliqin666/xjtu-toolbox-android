package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.jiaocai1.jiaocai1CacheRoot
import com.xjtu.toolbox.jiaocai1.AppJiaocai1Source
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.BOOK_TITLE
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream.Companion.SSNO
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.jiaocai1.Jiaocai1ViewModel
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 教材全文库（`jiaocai1`）在桌面端的**装配验收**（与 feedback 同一批的真数据路由）：
 * 桌面用自己的 [DesktopAuth] 真登一次，走 `DesktopSiteGate` 那条路把**教材中心**的会话建起来
 * （全文库借它的会话），再用自己的 `AppJiaocai1Source` 读出夹具那本书、取一页的字节；以及
 * 书架在桌面这份落盘实现上的往返。
 *
 * 与 `:data` 的 `Jiaocai1ApiJvmTest` 的分工：那边验字段解析与页字节的缓存口径；这里验**桌面这一端
 * 的装配** —— `DesktopAuth` 注册了教材中心站点、`JIAOCAI_SITE_KEY` 进 `SESSION_SITE_KEYS`、
 * 缓存根目录走 XDG（证据/测试里跟着 `dataRootOverride`）、书架是那份 0600 的 JSON 落盘。
 *
 * 手法同 `DesktopAuthLmsJvmTest`：`:testkit` 假上游（`FakeCampusProxy` 一个本地代理按 host 分派）
 * + URL 一个字符不改；`:app` 测试里那条「RSA 公钥预置」的口径照抄（假上游给不了
 * `https://login.xjtu.edu.cn/cas/jwt/publicKey`，见那个类的注释）。
 */
class DesktopAuthJiaocai1JvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-jiaocai1-test").toFile()
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        wipeSecureStore("jiaocai1_shelf")
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
    fun `真登录之后：教材中心会话建起来，桌面自己能检索、能打开一本书、能取到页字节`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")
        assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")

        // 路由进门的那条路（ToolboxDesktopApp 的 DesktopSiteGate）：教材中心会话按需建
        runBlocking { auth.ensureSession(DesktopAuth.JIAOCAI_SITE_KEY) }
        val site = assertNotNull(
            auth.sessionManager.getSiteOrNull(DesktopAuth.JIAOCAI_SITE_KEY),
            "教材中心站点应已注册并拿到会话（站点 key = `jiaocai`）",
        )
        assertTrue(site.hasLogin, "教材中心会话应当真的建起来")
        assertEquals("jiaocai", DesktopAuth.JIAOCAI_SITE_KEY, "站点 key 就是教材中心那一个")

        // 桌面这份装配：取数 + 页字节（缓存根目录走 XDG，测试里跟着临时数据根）
        val source = AppJiaocai1Source(site, jiaocai1CacheRoot())
        val result = runBlocking { source.search(keyword = "电路", field = com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField.BOOK_NAME, cls = "", page = 1) }
        assertEquals(BOOK_TITLE, result.books.single().title)

        val handle = assertNotNull(runBlocking { source.openBook(SSNO) }, "应当打开那本书")
        assertEquals(34, handle.pages.size)
        val bytes = assertNotNull(runBlocking { source.pageBytes(handle, handle.pages.first()) })
        assertTrue(bytes.size > 8 && bytes[0] == 0xFF.toByte(), "页字节是 JPEG")
        assertTrue(
            java.io.File(jiaocai1CacheRoot(), "jiaocai1/$SSNO/cov001.img").isFile,
            "页图应当落在 `${jiaocai1CacheRoot()}` 下面（不碰用户真实缓存）",
        )
        source.closePages()
    }

    @Test
    fun `书架：桌面这份落盘实现能往返（新增、改进度、排序、删除）`() {
        val store = DesktopJiaocai1ShelfStore(keyValueStore("jiaocai1_shelf_unit_test"))
        runBlocking {
            store.upsert(
                Jiaocai1ShelfRecord(
                    ssno = "A", title = "示例教材甲", author = "示例甲", coverUrl = "",
                    totalPages = 10, lastReadIndex = 0, lastReadAt = 100L, addedAt = 100L,
                ),
            )
            store.upsert(
                Jiaocai1ShelfRecord(
                    ssno = "B", title = "示例教材乙", author = "示例乙", coverUrl = "",
                    totalPages = 20, lastReadIndex = 3, lastReadAt = 200L, addedAt = 200L,
                ),
            )
            // 最近读的在前（与 Android 那条 `ORDER BY lastReadAt DESC` 同口径）
            assertEquals(listOf("B", "A"), store.observeAll().first().map { it.ssno })

            store.updateProgress("A", index = 4, at = 300L, totalPages = 10)
            val a = assertNotNull(store.get("A"))
            assertEquals(4, a.lastReadIndex)
            assertEquals(300L, a.lastReadAt)
            assertEquals(listOf("A", "B"), store.observeAll().first().map { it.ssno })

            store.delete("B")
            assertEquals(listOf("A"), store.observeAll().first().map { it.ssno })
        }
    }

    @Test
    fun `ViewModel：分类树取回来后缓存端口为 null 也不影响（桌面没有 DataCache）`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "真登录应当成功")
        runBlocking { auth.ensureSession(DesktopAuth.JIAOCAI_SITE_KEY) }
        val site = assertNotNull(auth.sessionManager.getSiteOrNull(DesktopAuth.JIAOCAI_SITE_KEY))

        val source = AppJiaocai1Source(site, jiaocai1CacheRoot())
        val shelf = DesktopJiaocai1ShelfStore(keyValueStore("jiaocai1_shelf_vm_test"))
        // cache = null：与 ToolboxDesktopApp 里那条路由的装配一致
        val vm = Jiaocai1ViewModel(source, shelf, cache = null)
        vm.bind()
        vm.loadCategories()
        // 屏上那条路是「加载完再读 categoryRoots」：VM 的请求跑在 viewModelScope（EDT）上，
        // 单测里没有主线程循环 ⇒ 直接等状态（这与 RenderScreens 的帧循环是同一条口径）。
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.categoryRoots.isEmpty() && vm.categoryError == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(
            Jiaocai1FakeUpstream.ROOT_CATEGORY_NAME,
            vm.categoryRoots.single().name,
            "分类树应当从夹具取回来了：error=${vm.categoryError}",
        )
        source.closePages()
    }
}
