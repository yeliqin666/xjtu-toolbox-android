package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.schedule.CustomCourse
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCapability
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 我的课表在桌面端的**装配验收**（第 30 条真数据路由）：
 * 桌面用自己的 `DesktopAuth` 真登一次，走 `DesktopSiteGate` 那条路把**教务**会话建起来
 * （与全校课表 / 成绩同一个站点），再用本端的 `DesktopScheduleHost` 取课表。
 *
 * 与 `:data` 的 `JwxtScheduleApiJvmTest` 的分工：那边验取数口径（`rows()` 的排课原样、
 * `schedule()` 合过调停补课、`termStart()` 的周数）；这里验**桌面这一端的装配** ——
 * 宿主槽位接对了、会话按需建起来了、课表真取到了、**自建日程真落盘**。
 *
 * 三条钉子：
 *  1. **真登录链**跑出课表：夹具那门课（`高等数学（上）`）与教室（`主楼A-101`）都在取回的结果里；
 *  2. **真落盘**：课表写进本端的键值文件（`schedule_cache<账号后缀>.properties`），
 *     文件里真的出现那个学期的键 —— 不是只躺在内存里；
 *  3. **自定义课落盘**：`DesktopScheduleHost.customCourses` 写一条，同一个文件里读得回来，
 *     而且 `id` 是自增分配的（`insert` 返回的就是它）。
 *
 * 另一条钉住的是**如实降级**：[DesktopScheduleHost.honored] 只列它真接的那几档
 *（自建日程、思源入口），分享面板 / 座位数 / 作业截止 / 小组件与首页信号那几档**都不列**
 *（屏上也就一个字都不画）。这条断言改了就会红，提醒把那一档的宿主面接上。
 *
 * 手法同 `DesktopAuthJiaocaiJvmTest`：`:testkit` 假上游 + URL 一个字符不改。
 */
class DesktopScheduleJvmTest {

    private lateinit var fake: FakeCampusProxy

    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-schedule-test").toFile()
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        // 这一端新落的两份存储也要清：课表缓存与自建日程（跑两次应当是同一种起点）。
        for (name in listOf(
            DesktopScheduleHost.SCHEDULE_CACHE_STORE + "_default",
            DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix,
            DesktopScheduleHost.CUSTOM_COURSE_STORE,
        )) {
            wipeSecureStore(name)
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
    fun `真登录之后：桌面自己能取到课表，而且写进本端的键值落盘`() = withFakeCampus {
        val auth = newAuth()
        assertTrue(login(auth), "用假上游的账号密码应当登得上：${auth.loginState}")

        // 路由进门的那条路（ToolboxDesktopApp 的 DesktopSiteGate）：教务会话按需建
        runBlocking { auth.ensureSession(DesktopAuth.JWXT_SITE_KEY) }

        val host = DesktopScheduleHost(auth)
        val session = host.session
        val source = assertNotNull(session.source, "教务站点应已注册并拿到会话（站点 key = `jwxt`）")
        assertTrue(session.isLoggedIn, "进了 `DesktopSiteGate` 之后教务会话应当是登录态")

        val term = "2026-2027-1"
        val served = runBlocking { session.fetchSchedule(source, term) }
        val courses = served.courses
        assertTrue(courses.isNotEmpty(), "夹具给了整学期课表，不该是空的")
        assertTrue(
            courses.any { it.courseName == JwxtFakeUpstream.SCHEDULE_1_COURSE && it.location == JwxtFakeUpstream.SCHEDULE_1_ROOM },
            "夹具那门课（${JwxtFakeUpstream.SCHEDULE_1_COURSE} @ ${JwxtFakeUpstream.SCHEDULE_1_ROOM}）应当取到：" +
                courses.map { "${it.courseName}@${it.location}" },
        )
        assertEquals("jwxt", served.sourceKey, "这一端只按教务那一条来源走（设置页里没有别的可选）")

        // 落盘那一半：日程页写的就是这份缓存（`DesktopScheduleHost.cache`）
        val cache = host.cache(AccountContext.activeAccountId)
        ScheduleCache.writeRawCourses(cache, term, courses)
        ScheduleCache.writeCurrentTerm(cache, term)
        assertEquals(
            courses.size,
            assertNotNull(ScheduleCache.readRawCourses(cache, term), "写进去的应当读得回来").size,
        )

        // **真落盘**：读的是 0600 的键值文件，文件里真的出现那个学期的键
        val cacheFile = File(requireNotNull(dataRootOverride), "${cacheFileName(AccountContext.activeAccountId)}.properties")
        assertTrue(cacheFile.exists(), "课表缓存文件不存在：${cacheFile.absolutePath}")
        val text = cacheFile.readText()
        assertTrue(
            text.contains("schedule_$term"),
            "缓存文件里没有 `schedule_$term` 这个键 —— 说明只写在内存里：\n${text.take(400)}",
        )
    }

    @Test
    fun `自建日程：桌面上真加得进去、真落盘、id 是自增分配的`() = withFakeCampus {
        val host = DesktopScheduleHost(newAuth())
        val store = host.customCourses
        val account = "2021000001"
        AccountContext.activeAccountId = account

        val id = runBlocking {
            store.insert(
                CustomCourse(
                    accountId = account,
                    courseName = "自习（夹具）",
                    weekBits = "11110000000000000000",
                    dayOfWeek = 3,
                    startSection = 9,
                    endSection = 10,
                    termCode = "2026-2027-1",
                    note = "值都是编的",
                ),
            )
        }
        assertTrue(id > 0L, "新增应当拿到自增主键，实际是 $id")

        val back = runBlocking { store.getByTerm(account, "2026-2027-1") }
        assertEquals(listOf("自习（夹具）"), back.map { it.courseName })
        assertEquals(id, back.single().id, "落库回来的 id 就是 insert 返回的那个")

        // 另一个学期不该混进来（同一条 SQL 条件：accountId + termCode）
        assertEquals(emptyList(), runBlocking { store.getByTerm(account, "2026-2027-2") })

        // 粗筛那条冲突查询：同一天、节次有交集才回来（与 Room 那句 SQL 同一个判据）
        assertEquals(1, runBlocking { store.getConflicts(account, "2026-2027-1", 3, 9, 10) }.size)
        assertEquals(0, runBlocking { store.getConflicts(account, "2026-2027-1", 4, 9, 10) }.size)

        // **真落盘**：键值文件的写入是同步的（`FileKeyValueStore.putString` 返回时已经落盘），
        // 所以「文件里有这一行」就是「写进磁盘了」的判据。`secureKeyValueStore` 对同名返回同一个
        // 实例（进程内一份），所以这里不靠「另开一个 store」来证明，而是直接看文件。
        val file = File(requireNotNull(dataRootOverride), "${DesktopScheduleHost.CUSTOM_COURSE_STORE}.properties")
        assertTrue(file.exists(), "自建日程的落盘文件不存在：${file.absolutePath}")
        assertTrue(file.readText().contains("custom_courses"), "文件里应当有那个键")
        // ⚠️ `Properties.store` 把非 ASCII 转成 `\uXXXX`，所以不能直接 findText 课名 ——
        // 按这条缝自己的读法（Properties.load）把它读回来再比。
        assertTrue(
            readStoreValue(file, "custom_courses").contains("自习（夹具）"),
            "文件里应当有刚写进去的那条日程：\n${readStoreValue(file, "custom_courses").take(400)}",
        )

        // 删除也要真落盘
        runBlocking { store.delete(back.single()) }
        assertEquals(emptyList(), runBlocking { store.getByTerm(account, "2026-2027-1") })
        assertFalse(
            readStoreValue(file, "custom_courses").contains("自习（夹具）"),
            "删掉之后文件里不该还留着那一条",
        )
    }

    @Test
    fun `如实降级：这一端只声明它真接得上的那几档能力`() = withFakeCampus {
        val host = DesktopScheduleHost(newAuth())
        assertEquals(
            setOf(
                ScheduleCapability.CUSTOM_COURSE_EDIT,
                ScheduleCapability.LMS_LINK,
                // 课表落地后要给首页发信号：这一端现在有首页了（`AppRoute.Main`），
                // 它盯的就是 `DesktopHomeSignals.scheduleVersion`。
                ScheduleCapability.HOME_SIGNALS,
            ),
            host.honored,
            "桌面只有「自建日程」与「思源入口」这两档（见 DesktopScheduleHost 的表与 TODO）—— " +
                "改这里就等于屏上多画一档，先确认那一档的宿主面真接上了",
        )
        // 首页信号那一档要在行为上真的会响：课表落地 → 版本号加一（首页据此重读缓存）。
        // `WIDGET_REFRESH` 则相反 —— 桌面没有桌面小组件，所以它既不在 honored 里、
        // `onScheduleSynced()` 也只做首页那件事。
        assertFalse(ScheduleCapability.WIDGET_REFRESH in host.honored, "桌面没有桌面小组件")
        val before = DesktopHomeSignals.scheduleVersion
        host.onScheduleSynced()
        assertEquals(before + 1, DesktopHomeSignals.scheduleVersion, "课表落地应当叫醒首页")
        // 没接的那几档要在行为上也是「没有」，不能只是没列进集合
        assertFalse(ScheduleCapability.SHARE_FILE in host.honored)
        assertEquals(false, host.shareFile("BEGIN:VCALENDAR", "x.ics", "text/calendar"), "桌面没有分享面板")
        assertEquals(null, runBlocking { host.roomSeatCount("主楼A-101") }, "座位数那一档还没接")
        assertEquals(emptyList(), runBlocking { host.dueHomeworks("2021000001") }, "作业截止那一档还没接")
    }
}

/** 按落盘那条缝自己的读法把一个键读回来（`Properties.load`，与 `FileKeyValueStore` 同一个口径）。 */
private fun readStoreValue(file: File, key: String): String {
    val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
    return props.getProperty(key).orEmpty()
}

/** `secureKeyValueStore` 的落盘文件名（`SecureStore.jvm` 的口径：`<name>.properties`）。 */
private fun cacheFileName(accountId: String?): String =
    DesktopScheduleHost.SCHEDULE_CACHE_STORE + AccountContext.suffixFor(accountId)
