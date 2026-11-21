package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.widget.smart.NavKey
import com.xjtu.toolbox.widget.smart.SeatKey
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartKey
import com.xjtu.toolbox.widget.smart.SmartKeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块里的快捷键，以及「谁点了去哪儿」。
 *
 * 第一原则是**不重复**：点块能到的地方，块里不许再放一个图标。
 * 第二条是**点击区不能互相覆盖** —— 这块以前真的错过：块和块内第一个图标共用了同一个
 * `PendingIntent` 请求码，`FLAG_UPDATE_CURRENT` 让后者的 extras 盖掉前者，
 * 表现出来就是「点块跳到了图标的页面」。所以这一组还要断言请求码互不相同。
 */
class SmartWidgetButtonTest {

    private val course = WidgetCourse(
        name = "高等数学", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0, startSection = 3, endSection = 4,
    )

    private fun todo(minutesLeft: Long, route: String? = AppRoute.Attendance.id) = InboxItem(
        id = "lms-1", category = InboxCategories.LMS, source = "思源学堂",
        title = "实验报告", expiresAt = 1_000_000_000L + minutesLeft * 60_000L, route = route,
    )

    private fun seat(actions: List<String>, status: String = "已预约") = LibrarySeatFact(
        seat = "3F-12", area = "兴庆三层", status = status,
        actions = actions, at = 1_000_000_000L,
    )

    private fun facts(
        nowMinute: Int = 650,
        todos: List<InboxItem> = emptyList(),
        balance: String? = null,
        library: LibrarySeatFact? = null,
        courses: List<WidgetCourse> = listOf(course),
        scheduleDiff: String? = null,
        evidenceAgo: Int? = 1,
    ) = SmartFacts(
        nowMinute = nowMinute,
        nowMs = 1_000_000_000L,
        courses = courses,
        date = "2026-11-17",
        todos = todos,
        balance = balance,
        library = library,
        scheduleDiff = scheduleDiff,
        checkedAgoMin = evidenceAgo,
        checkedAtClock = "10:49",
    )

    private fun firstKeys(f: SmartFacts): List<SmartKey> = SmartArbiter.card(f).blocks.first().keys

    private fun kinds(keys: List<SmartKey>) = keys.map { it.kind }

    @Test
    fun `上课那个块只补扫码，不重复一个「考勤」图标`() {
        // 点块就进考勤页了，所以块里只有「扫码」这个到不了的地方
        assertEquals(listOf(SmartKeyKind.QR), kinds(firstKeys(facts())))
    }

    @Test
    fun `下一节那个块补的是考勤，不是日程`() {
        val f = facts(nowMinute = 9 * 60 + 30)
        val keys = firstKeys(f)
        assertEquals(listOf(SmartKeyKind.ATTENDANCE), kinds(keys))
        assertEquals(AppRoute.Attendance, (keys.first() as NavKey).route)
    }

    @Test
    fun `饭点那个块补校园卡，兜底那个块补付款码`() {
        val meal = firstKeys(facts(nowMinute = 11 * 60 + 30, courses = emptyList(), balance = "¥12.30"))
        assertEquals(listOf(SmartKeyKind.CARD), kinds(meal))
        assertEquals(AppRoute.CampusCard, (meal.first() as NavKey).route)

        val done = firstKeys(facts(nowMinute = 15 * 60, balance = "¥8.00"))
        assertEquals(listOf(SmartKeyKind.PAYMENT), kinds(done))
        assertEquals(AppRoute.PaymentCode, (done.first() as NavKey).route)
    }

    @Test
    fun `待办那个块补收纳，不重复那条待办自己的入口`() {
        val keys = firstKeys(facts(nowMinute = 900, todos = listOf(todo(59))))
        assertEquals(listOf(SmartKeyKind.INBOX), kinds(keys))

        // 待办没有指定页面时块本身就落在收纳，那就没得补了
        assertTrue(firstKeys(facts(nowMinute = 900, todos = listOf(todo(59, route = null)))).isEmpty())
    }

    @Test
    fun `课表变动那个块里没有键`() {
        // 点块进的就是日程页，日程那一屏里没有它到不了的地方
        val diff = SmartArbiter.card(facts(nowMinute = 15 * 60, scheduleDiff = "高等数学换教室")).blocks.first()
        assertEquals(com.xjtu.toolbox.widget.smart.SmartModule.SCHEDULE_DIFF, diff.module)
        assertTrue(diff.keys.isEmpty())
    }

    @Test
    fun `座位动作跟着最近一次查到的按钮集合走`() {
        val card = SmartArbiter.card(facts(nowMinute = 612, library = seat(listOf("入馆签到", "取消预约"))))
        val lib = card.blocks.first { it.module == com.xjtu.toolbox.widget.smart.SmartModule.LIBRARY }
        assertEquals(listOf(SmartKeyKind.CHECKIN, SmartKeyKind.CANCEL), kinds(lib.keys))
        assertEquals("入馆签到", (lib.keys.first() as SeatKey).action)

        val back = SmartArbiter.card(facts(nowMinute = 900, library = seat(listOf("中途返回", "取消预约"), status = "暂离")))
        val libBack = back.blocks.first { it.module == com.xjtu.toolbox.widget.smart.SmartModule.LIBRARY }
        assertEquals(listOf(SmartKeyKind.RETURN, SmartKeyKind.CANCEL), kinds(libBack.keys))

        val inside = SmartArbiter.card(facts(nowMinute = 900, library = seat(listOf("中途离开", "取消预约"), status = "使用中")))
        val libIn = inside.blocks.first { it.module == com.xjtu.toolbox.widget.smart.SmartModule.LIBRARY }
        assertEquals(listOf(SmartKeyKind.LEAVE, SmartKeyKind.CANCEL), kinds(libIn.keys))
    }

    @Test
    fun `学校那边没有的动作绝不出现`() {
        val card = SmartArbiter.card(facts(nowMinute = 900, library = seat(listOf("取消预约"), status = "使用中")))
        val lib = card.blocks.first { it.module == com.xjtu.toolbox.widget.smart.SmartModule.LIBRARY }
        assertEquals(listOf(SmartKeyKind.CANCEL), kinds(lib.keys))
    }

    @Test
    fun `块与块内图标的请求码互不相同`() {
        // 这条就是「点块跳到图标页面」那个 bug 的回归防线
        val codes = SmartWidgetUpdater.requestCodesOf(widgetId = 23)
        assertEquals("六个位置就该有六个不同的请求码", 6, codes.toSet().size)
        assertTrue("实例之间也不能撞", codes.intersect(SmartWidgetUpdater.requestCodesOf(24).toSet()).isEmpty())
    }
}
