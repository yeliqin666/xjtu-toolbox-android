package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.scoreReportSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 成绩报表（`AppRoute.ScoreReport`，第 7 条真数据路由）在桌面端的**装配验收**。
 *
 * 这条路由的屏（`:core` 的 `ScoreReportScreen`）与取数（`:data` 的 `scoreReportSource`）
 * 早就搬完了，本轮补的是它一直缺的那一半：**SWR 缓存**。搬迁前 `:app` 侧的
 * `appScoreReportCache` 是那条路由唯一的宿主依赖，桌面这一端一直是 `cache = null`
 * （没有秒显）—— 现在填上 [DesktopScoreReportCache]（与 `:app` **同一个键**）。
 *
 * 与 `:data` 的 `ScoreReportApiJvmTest` 的分工：那边验帆软报表逐页解析的字段口径（三行成课、
 * 四类噪声行被跳过）；这里验**桌面这一端的装配** —— 桌面自己登教务、拉一份成绩、把它写进
 * 缓存再读回来（同一批行、同一个键），以及那条缓存的四种边界（过期 / 坏值 / 换学号 / 空）。
 *
 * 手法同 `DesktopAuthJiaocai1JvmTest`：`:testkit` 假上游 + URL 一个字符不改。
 */
class DesktopAuthScoreReportJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-score-test").toFile()
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
            AccountContext.activeAccountId = null
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
    fun `真登录之后：桌面自己能拉成绩报表，写进缓存再读回来是同一批行`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")

        runBlocking { auth.ensureSession(DesktopAuth.JWXT_SITE_KEY) }
        val site = assertNotNull(
            auth.sessionManager.getSiteOrNull(DesktopAuth.JWXT_SITE_KEY),
            "教务会话应当真的建起来",
        )
        assertTrue(site.hasLogin, "教务会话应当真的登进去")

        val source = scoreReportSource(site, JwxtFakeUpstream.STUDENT_ID)
        val grades = runBlocking { source.grades() }
        assertEquals(3, grades.size, "夹具那两页报表里三行成课")
        assertEquals(JwxtFakeUpstream.STUDENT_ID, fake.jwxt.lastReportStudentId)

        // 桌面这份缓存：键与 `:app` 的 `appScoreReportCache` 逐字相同
        val store = keyValueStore("score_report_unit_test").apply { clear() }
        val cache = DesktopScoreReportCache(JwxtFakeUpstream.STUDENT_ID, store = store)
        assertNull(cache.read(), "还没写过 ⇒ 读不到（屏幕据此直接去拉）")

        cache.write(grades)

        assertTrue(
            store.contains(DesktopScoreReportCache.keyFor(JwxtFakeUpstream.STUDENT_ID)),
            "缓存键就是 `score_report_<学号>`（与 :app 同键）",
        )
        assertEquals(grades, cache.read(), "读回来是同一批行（同一个 AppJson 编码）")
    }

    @Test
    fun `缓存：过了 TTL 读不到并删键，坏值当没有，换学号就换键`() {
        val store = keyValueStore("score_report_unit_test").apply { clear() }
        val grades = listOf(ReportedGrade(courseName = "示例课（假）", coursePoint = 3.0, score = "92", gpa = 4.0, term = "2025-2026-1"))

        var clock = 1_000_000L
        val cache = DesktopScoreReportCache(JwxtFakeUpstream.STUDENT_ID, store = store) { clock }
        cache.write(grades)
        assertEquals(grades, cache.read())

        // 恰好到 TTL 还没过期（`:app` 的 DataCache 也是 `age > ttl` 才算过期）
        clock += DesktopScoreReportCache.TTL_MS
        assertEquals(grades, cache.read(), "刚好 7 天不该过期")

        // 超一点点就过期，且那个键被删掉（与 DataCache 过期删文件同一个行为）
        clock += 1
        assertNull(cache.read(), "过了 TTL 读不到")
        assertTrue(
            !store.contains(DesktopScoreReportCache.keyFor(JwxtFakeUpstream.STUDENT_ID)),
            "过期的那一格应当被删掉，不留着下次再解析",
        )

        // 坏值（不是 JSON）当没有，不抛
        store.putString(DesktopScoreReportCache.keyFor(JwxtFakeUpstream.STUDENT_ID), "这不是 JSON")
        assertNull(cache.read())

        // 换学号就换键：别人的成绩串不过来
        val other = DesktopScoreReportCache("2099000000", store = store) { clock }
        assertNull(other.read(), "另一个学号读的是另一个键")
        other.write(grades)
        assertTrue(
            store.contains(DesktopScoreReportCache.keyFor("2099000000")),
            "两个学号各占一格（键里带学号）",
        )
    }
}
