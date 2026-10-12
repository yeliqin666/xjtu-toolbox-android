package com.xjtu.toolbox.home

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.inbox.InboxData
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.nav.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 首页的**卡片模型**（`:core` 的 [HomeCards]）—— 从 `:app` 的 `main/HomeTab.kt` 里那两段内联
 * 推导搬进来时，判定一行未改，所以这里钉的就是那几条判定本身：
 *
 * - 分类卡按 [ServiceCategory.entries] 的顺序出，空分类不下发（搬迁前那句 `if (items.isEmpty()) null`）；
 * - 分类卡与 Hero 的分工：校园卡与校历**不在**分类卡里带状态（它的数在 Hero）；
 * - 考勤那一格会带上「今日人脸刷卡」（快速考勤流水不再单列，今天刷过卡就借考勤这格露出来）；
 * - 「待办与消息」那一格算的就是 `InboxRules` 那两条规则，一个数都不自己编。
 *
 * 改 `:core` 的 [HomeCards] 之前先看这里。
 */
class HomeCardsTest {

    @Test
    fun `分类卡按目录里的分类顺序出，空分类不下发`() {
        val groups = HomeCards.groups(AccountType.UNDERGRADUATE, emptyMap())
        val expected = ServiceCategory.entries.filter { category ->
            AppServices.homeFor(AccountType.UNDERGRADUATE).any { it.category == category }
        }
        assertEquals(expected, groups.map { it.category })
        assertTrue(groups.all { it.rows.isNotEmpty() }, "空分类不该出现在首页上")
    }

    @Test
    fun `每一类里的行序与目录序一致`() {
        val groups = HomeCards.groups(AccountType.UNDERGRADUATE, emptyMap())
        val catalog = AppServices.homeFor(AccountType.UNDERGRADUATE)
        groups.forEach { group ->
            assertEquals(
                catalog.filter { it.category == group.category }.map { it.route.id },
                group.rows.map { it.service.route.id },
                "${group.category.title} 那一类的行序",
            )
        }
    }

    /** 「这一端真能打开」的那张表把入口筛掉之后，整类空掉的卡也跟着消失（桌面端的宫格就靠它）。 */
    @Test
    fun `只保留本端能打开的路由，整类空掉就不画`() {
        val groups = HomeCards.groups(
            AccountType.UNDERGRADUATE,
            emptyMap(),
            onlyRouteIds = setOf(AppRoute.Library.id, AppRoute.SchoolCalendar.id),
        )
        // 顺序仍是 `ServiceCategory.entries` 的顺序（LIFE 在 STUDY 前面）
        assertEquals(listOf(ServiceCategory.LIFE, ServiceCategory.STUDY), groups.map { it.category })
        assertEquals(
            listOf(AppRoute.Library.id),
            groups.first { it.category == ServiceCategory.STUDY }.rows.map { it.service.route.id },
        )
    }

    @Test
    fun `校园卡与校历这一格不填状态 —— 它们的数在 Hero 里`() {
        val stats = mapOf(
            AppRoute.CampusCard.id to HomeStat("¥42.50", "约够 4 天"),
            AppRoute.SchoolCalendar.id to HomeStat("第5周"),
        )
        assertNull(HomeCards.statOf(AppRoute.CampusCard.id, stats))
        assertNull(HomeCards.statOf(AppRoute.SchoolCalendar.id, stats))
    }

    @Test
    fun `考勤那一格带上今日人脸刷卡`() {
        val stats = mapOf(
            AppRoute.Attendance.id to HomeStat("98%", "本周 2 次缺勤"),
            AppRoute.Iclassface.id to HomeStat("07:52", "主楼A-101"),
        )
        val merged = HomeCards.statOf(AppRoute.Attendance.id, stats)
        assertEquals("98%", merged?.value)
        assertEquals("今日已刷 07:52 · 主楼A-101", merged?.detail)
    }

    /** `今日未刷卡` 是那条记录自己在说自己没刷 —— 它不该被当成「刷过了」露出来。 */
    @Test
    fun `没刷卡的流水不借考勤那格露出来`() {
        val stats = mapOf(
            AppRoute.Attendance.id to HomeStat("98%", "本周 2 次缺勤"),
            AppRoute.Iclassface.id to HomeStat("今日未刷卡"),
        )
        assertEquals("本周 2 次缺勤", HomeCards.statOf(AppRoute.Attendance.id, stats)?.detail)
    }

    @Test
    fun `其余功能就是查表，没有状态就退回纯入口`() {
        val stats = mapOf(AppRoute.Library.id to HomeStat("东区 12 座", "刚刚更新"))
        assertEquals("东区 12 座", HomeCards.statOf(AppRoute.Library.id, stats)?.value)
        assertNull(HomeCards.statOf(AppRoute.EmptyRoom.id, stats))
    }

    @Test
    fun `待办与消息那一格算的就是收纳那两条规则`() {
        val now = 1_800_000_000_000L
        val data = InboxData(
            messages = listOf(
                InboxItem(id = "m1", category = "教务", source = "教务", title = "通知A", time = now - 60_000),
                InboxItem(id = "m2", category = "教务", source = "教务", title = "通知A", time = now - 120_000),
            ),
            todos = mapOf(
                "作业" to listOf(
                    InboxItem(id = "t1", category = "作业", source = "思源", title = "交第三章作业", expiresAt = now + 86_400_000),
                    InboxItem(id = "t2", category = "作业", source = "思源", title = "交第四章作业", expiresAt = now + 7 * 86_400_000),
                ),
            ),
            readAt = mapOf("m1" to now, "m2" to now),
        )
        val summary = HomeCards.inboxSummary(data, now)
        assertEquals(2, summary.todoCount)
        assertEquals(0, summary.unreadCount, "两条同标题的消息算一组、而且都读过了")
        assertEquals("交第三章作业", summary.nextTodo, "快截止的排前面")
        assertTrue(!summary.isEmpty)
    }

    @Test
    fun `什么都收满了就是空的`() {
        val summary = HomeCards.inboxSummary(InboxData(), now = 1_800_000_000_000L)
        assertEquals(0, summary.todoCount)
        assertEquals(0, summary.unreadCount)
        assertNull(summary.nextTodo)
        assertTrue(summary.isEmpty)
    }
}
