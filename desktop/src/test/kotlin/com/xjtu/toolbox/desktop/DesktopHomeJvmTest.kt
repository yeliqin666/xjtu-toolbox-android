package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.bulletin.BulletinStore
import com.xjtu.toolbox.home.EXAM_KEY
import com.xjtu.toolbox.home.HomeCard
import com.xjtu.toolbox.home.HomeCards
import com.xjtu.toolbox.home.HomeHero
import com.xjtu.toolbox.home.HomeScheduleStats
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.TextbookItem
import com.xjtu.toolbox.util.todayInSystemZone
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * 首页（`AppRoute.Main`）在桌面端的**装配验收** —— 这一端不另造一套壳，首页就是底栏的第一格，
 * 屏是 `:core` 的 `HomeScreen`，这里验的是**这一端交出来的那一份数据与那一份声明**。
 *
 * 三条钉子：
 *  1. **真出数**：把一份夹具课表/考试/教材写进本端的课表缓存（键值文件，与日程页同一份），
 *     首页那几格（教学周 / 下一项安排 / 考试倒计时 / 课数 / 教材册数）就得从它算出来 ——
 *     算的是 `:core` 的 `HomeHero` 与 `HomeScheduleStats`（本轮从 `:app` 的 `HomeStats` 搬过来的）。
 *  2. **如实声明**：[DesktopHomeHost.honoredCards] 只列它真会照着办的八块（`QUICK_ACTIONS`
 *     不在其中 —— 桌面没有点击记录），拿不到的槽位是 `null`/空表，宫格按
 *     [DESKTOP_OPENABLE_ROUTE_IDS] 把「点了会落占位页」的入口**不画**。
 *  3. **公告**：`DesktopBulletins` 走的是共享层那三件（`BulletinStore` / `BulletinRules` /
 *     `BulletinFeed`）—— 冷启动挑中哪一条弹、关掉一条按等级怎么记账，都在这里钉住。
 *
 * 它**一个网络请求都不发**：课表缓存是本机键值文件，公告那一枪注入夹具（与 `RenderScreens`
 * 同一条口径 —— 证据/测试不该去打真站点，更不该把真公告画进图里）。
 */
class DesktopHomeJvmTest {

    private val term = "2026-2027-1"
    private val account = "2021000001"

    /** 一个不拉网络的公告宿主面（首页宿主面要它，但这一批用例只关心首页那几格）。 */
    private fun offlineBulletins(): DesktopBulletins =
        DesktopBulletins(
            scope = CoroutineScope(Dispatchers.Unconfined),
            versionName = FIXTURE_VERSION,
            fetch = { null },
        )

    /** 一份夹具公告（值都是编的），走**真的解析那条路**（`BulletinRules.parsePayload`）。 */
    private fun fixtureFetch(level: String, id: String, title: String): BulletinApi.FetchResult {
        val json = """{"bulletins":[{"id":"$id","level":"$level","title":"$title",""" +
            """"body":"值都是编的","url":"https://example.invalid/fixture"}]}"""
        return BulletinApi.FetchResult(BulletinRules.parsePayload(json), json)
    }

    private fun withTempDataRoot(block: () -> Unit) {
        dataRootOverride = Files.createTempDirectory("xjtu-desktop-home-test").toFile()
        val suffix = AccountContext.suffixFor(account)
        for (name in listOf(
            DesktopScheduleHost.SCHEDULE_CACHE_STORE + "_default",
            DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix,
            DesktopScheduleHost.CUSTOM_COURSE_STORE,
        )) {
            wipeSecureStore(name)
        }
        AccountContext.activeAccountId = null
        try {
            block()
        } finally {
            dataRootOverride = null
        }
    }

    @Test
    fun `真出数：首页那几格从本端的课表缓存里算出来`() = withTempDataRoot {
        val auth = DesktopAuth()
        AccountContext.activeAccountId = account
        val cache = DesktopScheduleHost(auth).cache(account)

        val today = todayInSystemZone()
        // 开学日期 = 三周前：于是「第 N 周」稳定是 4（不锚周一那一条算式也一样，见 HomeScheduleStats）
        val start = LocalDate.fromEpochDays(today.toEpochDays() - 21)
        val course = CourseItem(
            courseName = "示例课程（夹具）",
            teacher = "示例老师（夹具）",
            location = "主楼A-101",
            weekBits = "1".repeat(20),
            dayOfWeek = today.dayOfWeek.ordinal + 1,
            startSection = 1,
            endSection = 2,
            courseCode = "FIXTURE-0001",
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 10 * 60 + 35,
        )
        val examDay = LocalDate.fromEpochDays(today.toEpochDays() + 5)
        ScheduleCache.writeCurrentTerm(cache, term)
        ScheduleCache.writeStartDate(cache, term, start, weeks = 20)
        ScheduleCache.writeOptimizedCourses(cache, term, listOf(course))
        ScheduleCache.writeExams(
            cache,
            term,
            listOf(
                ExamItem(
                    courseName = "示例考试（夹具）",
                    examDate = examDay.toString(),
                    examTime = "09:00-11:00",
                    location = "主楼B-201",
                ),
            ),
        )
        ScheduleCache.writeTextbooks(
            cache,
            term,
            listOf(
                TextbookItem(courseName = "示例课程（夹具）", textbookName = "示例教材（夹具）"),
                TextbookItem(courseName = "另一门（夹具）", textbookName = "另一本（夹具）"),
            ),
        )

        // ── ① 各功能此刻的状态（`:core` 的 HomeScheduleStats，键名与 `:app` 同一批）──
        val stats = HomeScheduleStats.of(cache, term, today)
        assertEquals("第 4 周", stats[AppRoute.SchoolCalendar.id]?.value, "教学周由开学日期本地推算")
        assertEquals(term + " 学期", stats[AppRoute.SchoolCalendar.id]?.detail)
        val exam = assertNotNull(stats[EXAM_KEY], "缓存里有考试表，首页就该有考试倒计时")
        assertEquals("示例考试（夹具）", exam.value)
        assertEquals("还有 5 天 · 主楼B-201", exam.detail)
        assertEquals("1 门课", stats[AppRoute.Schedule.id]?.value)
        assertEquals("2 本", stats[AppRoute.Jiaocai.id]?.value)

        // ── ② 下一项安排（同一段 15 天扫描，`:core` 的 HomeHero）──
        val next = assertNotNull(
            HomeHero.find(
                courses = listOf(course),
                startOfTerm = start,
                holidayDates = emptySet(),
                now = LocalDateTime(today, LocalTime(8, 0)),
                route = AppRoute.Schedule,
            ),
            "今天 09:00 那节课在 08:00 这一刻就是「下一项」",
        )
        assertEquals("示例课程（夹具）", next.title)
        assertEquals("09:00", next.timeLabel)
        assertEquals("至 10:35", next.timeCaption)
        assertEquals("主楼A-101", next.detail)
        assertEquals(AppRoute.Schedule, next.route)

        // ── ③ 宿主面把同一份数据交出去（屏读的就是它）──
        val state = DesktopHomeState().apply {
            this.nextUp = next
            isLoaded = true
            weekNumber = HomeHero.weekNumber(start, today)
            this.stats = stats
        }
        val host = DesktopHomeHost(
            bulletinHost = offlineBulletins(),
            state = state,
            inboxSummary = null,
            today = { today },
            onNavigate = {},
        )
        assertEquals(4, host.weekNumber)
        assertEquals("示例课程（夹具）", host.nextUp?.title)
        assertTrue(
            host.dateLabel.startsWith("${today.month.ordinal + 1}月${today.day}日"),
            "日期那一行是「10月12日 · 周一」那种说法：${host.dateLabel}",
        )
        // 速览格：社区 + 考试（没有校园卡缓存，所以只有这两格）
        assertEquals(listOf("社区", "还有 5 天"), host.glances.map { it.label })
        assertEquals("还有 5 天", host.glances.last().label, "考试那一格的标题取的是 detail 的前半句")
        assertEquals("示例考试（夹具）", host.glances.last().value)
        assertEquals("$term 学期", host.stats[AppRoute.SchoolCalendar.id]?.detail)

        // 缓存真落到了本端的键值文件里（与日程页写的是同一份）
        assertEquals(term, ScheduleCache.readCurrentTerm(DesktopScheduleHost(auth).cache(account)))
    }

    @Test
    fun `如实声明：这一端有哪几块、打不开的入口不画`() = withTempDataRoot {
        val state = DesktopHomeState()
        val host = DesktopHomeHost(
            bulletinHost = offlineBulletins(),
            state = state,
            inboxSummary = null,
            today = { todayInSystemZone() },
            onNavigate = {},
        )

        assertEquals(
            setOf(
                HomeCard.BULLETIN_BAR,
                HomeCard.GREETING,
                HomeCard.WEEK_NUMBER,
                HomeCard.NEXT_UP,
                HomeCard.GLANCE,
                HomeCard.INBOX,
                HomeCard.CATEGORY_STATS,
                HomeCard.SERVICE_GRID,
            ),
            host.honoredCards,
            "桌面首页画这八块（见 DesktopHomeHost 的 KDoc）—— 改这里就等于屏上多画一块，" +
                "先确认那一块的宿主面真接上了",
        )
        assertFalse(HomeCard.QUICK_ACTIONS in host.honoredCards, "桌面没有点击记录 ⇒ 常用功能那一排不画")
        assertNull(host.statusLine, "桌面没有校内外判定 ⇒ 那一行状态不画")
        assertNull(host.heroArt, "桌面没有校区插画那张资源（不拿别的东西冒充）")
        assertEquals(emptyList(), host.quickRoutes, "常用入口那一排没接")
        assertEquals(listOf("社区"), host.glances.map { it.label }, "没有校园卡缓存、也没有考试表 ⇒ 只剩社区那一格")

        // 「说不出来」的三种说法分开（与 `:app` 的四分支同一套口径）
        assertEquals("正在读取本机课表…", host.nextUp?.title)
        state.isLoaded = true
        state.scheduleDataMissing = true
        assertEquals("课表还没同步", host.nextUp?.title)
        assertEquals(AppRoute.Schedule, host.nextUp?.route, "点这一格去同步课表")
        state.scheduleDataMissing = false
        assertEquals("接下来两周都没课", host.nextUp?.title)

        // 宫格按「这一端真能打开的路由」筛：打不开的入口**不画**
        val all = HomeCards.groups(AccountType.UNDERGRADUATE, emptyMap())
        val shown = HomeCards.groups(AccountType.UNDERGRADUATE, emptyMap(), host.openableRouteIds)
        assertTrue(
            all.flatMap { it.rows }.any { it.key == AppRoute.Agent.id },
            "服务目录里有「屁岱」—— 先确认这条断言的前提还成立",
        )
        // 屁岱这一格：`AppRoute.Agent` 点亮之后，它就该出现在「真能打开」的宫里。
        assertTrue(
            shown.flatMap { it.rows }.any { it.key == AppRoute.Agent.id },
            "桌面已有屁岱那一屏（`DESKTOP_SUPPORTED_ROUTES` 里那条）⇒ 这一格该画出来",
        )
        assertTrue(shown.flatMap { it.rows }.any { it.key == AppRoute.Schedule.id }, "我的课表这一端真能打开")
        assertTrue(shown.flatMap { it.rows }.all { it.key in DESKTOP_OPENABLE_ROUTE_IDS })
        assertTrue(shown.all { it.rows.isNotEmpty() }, "筛完为空的分类不下发（与 :app 同一条）")
        assertTrue(DESKTOP_SUPPORTED_ROUTES.any { it.first == AppRoute.Main }, "首页本身也在「真能用」那张表里")
    }

    @Test
    fun `公告：冷启动挑中要弹的那一条，关掉一条按等级记账`() {
        val infoId = "fixture-info-1"
        val info = DesktopBulletins(
            scope = CoroutineScope(Dispatchers.Unconfined),
            versionName = FIXTURE_VERSION,
            fetch = { fixtureFetch("info", infoId, "首页公告条（夹具）") },
        )
        runBlocking { info.check() }
        assertEquals(listOf("首页公告条（夹具）"), info.heroBulletins.map { it.title })
        assertNull(info.launchBulletin, "INFO 那一条只在公告条上露面，不弹框")
        assertFalse(info.showLaunchDialog.value)

        info.dismissHero(info.heroBulletins.single())
        assertTrue(info.heroBulletins.isEmpty(), "关掉的公告不该还挂在条上")
        assertTrue(BulletinStore().dismissedIds.contains(infoId), "INFO 的记账是「已关闭」")

        // CRITICAL：冷启动要弹一条，而弹框上的「稍后」**不**算已确认（`:app` 同一条）
        val criticalId = "fixture-critical-1"
        val critical = DesktopBulletins(
            scope = CoroutineScope(Dispatchers.Unconfined),
            versionName = FIXTURE_VERSION,
            fetch = { fixtureFetch("critical", criticalId, "重要通知（夹具）") },
        )
        runBlocking { critical.check() }
        assertEquals(criticalId, critical.launchBulletin?.id, "冷启动只弹一条，挑的是等级最高的那条")
        assertTrue(critical.showLaunchDialog.value)
        critical.dismissDialog(critical.launchBulletin!!)
        assertFalse(critical.showLaunchDialog.value)
        assertNull(critical.launchBulletin)
        assertFalse(BulletinStore().ackedIds.contains(criticalId), "CRITICAL 的「稍后」不记已确认")

        // FORCE_UPDATE：「稍后」只记在**进程内**（下次冷启动照旧出现）⇒ 条上立刻消失
        val forcedId = "fixture-forced-1"
        val forced = DesktopBulletins(
            scope = CoroutineScope(Dispatchers.Unconfined),
            versionName = FIXTURE_VERSION,
            fetch = { fixtureFetch("force_update", forcedId, "必须更新（夹具）") },
        )
        runBlocking { forced.check() }
        assertEquals(forcedId, forced.launchBulletin?.id)
        forced.dismissDialog(forced.launchBulletin!!)
        assertTrue(forced.heroBulletins.none { it.id == forcedId }, "推迟之后条上不该还挂着它")
        assertTrue(BulletinStore.sessionSnoozedIds().contains(forcedId), "推迟只记在进程内，不是永久关闭")
        assertFalse(BulletinStore().dismissedIds.contains(forcedId), "「必须升级」不该被一个 ✕ 永久消掉")
    }

    private companion object {
        /** 公告那一枪的版本号（夹具值；桌面包的真实版本是 `DesktopBuildConfig.VERSION_NAME`）。 */
        const val FIXTURE_VERSION = "9.9.9-fixture"
    }
}
