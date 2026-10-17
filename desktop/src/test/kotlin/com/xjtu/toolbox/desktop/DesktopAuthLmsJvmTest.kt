package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.desktop.lms.DesktopLmsSaver
import com.xjtu.toolbox.lms.AppLmsSource
import com.xjtu.toolbox.lms.LmsDownloadResult
import com.xjtu.toolbox.lms.LmsFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 思源学堂在桌面端的装配验收。 */
class DesktopAuthLmsJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-lms-test").toFile()
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
    fun `真登录之后：思源学堂自己走完 CAS 回跳，课程与活动读出夹具样本`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")
        assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")

        // 路由进门的那条路（ToolboxDesktopApp 的 DesktopSiteGate）：lms 会话按需建
        runBlocking { auth.ensureSession(DesktopAuth.LMS_SITE_KEY) }
        val site = auth.sessionManager.getSiteOrNull(DesktopAuth.LMS_SITE_KEY)
        assertNotNull(site, "lms 站点应已注册并拿到会话")
        assertTrue(site.hasLogin, "lms 会话应当真的建起来")

        // 桌面那份装配：取数 + 落盘槽位
        val source = AppLmsSource(site, DesktopLmsSaver)
        assertEquals("~/Downloads/", source.publicPathLabel())
        assertFalse(source.supportsVideoQueue, "桌面没有回放下载队列")

        val courses = runBlocking { source.myCourses() }
        val course = courses.firstOrNull { it.name == LmsFakeUpstream.COURSE_NAME }
        assertNotNull(course, "课程列表应当读出夹具那门课")
        assertEquals(LmsFakeUpstream.COURSE_CODE, course.courseCode)

        val activities = runBlocking { source.courseActivities(course.id) }
        val homework = activities.firstOrNull { it.id == LmsFakeUpstream.HOMEWORK_ID }
        assertNotNull(homework, "活动列表应当读出夹具那条作业")
        assertEquals(LmsFakeUpstream.HOMEWORK_DEADLINE, homework.deadline)

        val detail = runBlocking { source.activityDetail(homework.id, homework) }
        assertEquals(LmsFakeUpstream.HOMEWORK_DEADLINE, detail.deadline)
        assertEquals(1, detail.userSubmitCount)
        assertNotNull(detail.submissionList)
    }

    @Test
    fun `桌面落盘：写进用户下载目录并按活动分文件夹，失败不留半成品`() {
        // 落盘不依赖会话：把 user.home 指到临时目录，写一份再读回来
        val oldHome = System.getProperty("user.home")
        val root = Files.createTempDirectory("xjtu-desktop-lms-save").toFile()
        System.setProperty("user.home", root.absolutePath)
        try {
            val ok = runBlocking {
                DesktopLmsSaver.save(
                    name = "作业附件.pdf",
                    mimeType = "",
                    folder = "数据结构（示例课）/第十三次作业",
                ) { out ->
                    out.write("PDF".toByteArray())
                    LmsDownloadResult.Ok
                }
            }
            assertEquals(LmsDownloadResult.Ok, ok)
            val saved = File(root, "Downloads/数据结构（示例课）/第十三次作业/作业附件.pdf")
            assertTrue(saved.exists(), "应当落在 $saved")
            assertEquals("PDF", saved.readText())

            // 已结束活动（403 → Forbidden）：不留半成品文件（与 Android 的 resolver.delete 同句语义）
            val closed = runBlocking {
                DesktopLmsSaver.save("半成品.pdf", "", "数据结构（示例课）") { LmsDownloadResult.Forbidden }
            }
            assertEquals(LmsDownloadResult.Forbidden, closed)
            assertFalse(File(root, "Downloads/数据结构（示例课）/半成品.pdf").exists())
        } finally {
            System.setProperty("user.home", oldHome)
        }
    }
}