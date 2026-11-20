package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.widget.smart.NavKey
import com.xjtu.toolbox.widget.smart.RefreshKey
import com.xjtu.toolbox.widget.smart.SeatKey
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartBlock
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartKey
import com.xjtu.toolbox.widget.smart.SmartKeyKind
import com.xjtu.toolbox.widget.smart.SmartModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏快捷键（`docs/smart-widget.md` §5.7）。
 *
 * 三条规矩，一条都不许破：
 * 1. **只有三个位置，全在底栏**，刷新固定占最右；
 * 2. **动作键只能执行预约当前真的有这个按钮的那个动作** —— 按钮集合来自最近一次查到的
 *    `actionUrls.keys`，写死一个学校那边已经没了的按钮，点下去只会静默失败；
 * 3. 动作键优先于导航键（它省掉的是「进 App → 找按钮 → 点确认」三步）。
 */
class SmartWidgetButtonTest {

    private val course = WidgetCourse(
        name = "高等数学", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0, startSection = 3, endSection = 4,
    )

    private fun todo(minutesLeft: Long) = InboxItem(
        id = "lms-1", category = InboxCategories.LMS, source = "思源学堂",
        title = "实验报告", expiresAt = 1_000_000_000L + minutesLeft * 60_000L,
        route = AppRoute.Attendance.id,
    )

    private fun seat(actions: List<String>, status: String = "已预约") = LibrarySeatFact(
        seat = "3F-12", area = "兴庆三层", status = status,
        actions = actions, at = 1_000_000_000L,
    )

    private fun keysOf(nowMinute: Int, facts: SmartFacts = SmartFacts(nowMinute = nowMinute)): List<SmartKey> {
        val card = SmartArbiter.card(facts.copy(nowMinute = nowMinute))
        return card.keys
    }

    private fun kinds(keys: List<SmartKey>) = keys.map { it.kind }

    @Test
    fun `上课那一屏给考勤、扫码、刷新`() {
        val keys = keysOf(650, SmartFacts(nowMinute = 650, courses = listOf(course), checkedAgoMin = 1))
        assertEquals(
            listOf(SmartKeyKind.ATTENDANCE, SmartKeyKind.QR, SmartKeyKind.REFRESH),
            kinds(keys),
        )
        // 刷新固定在最右
        assertTrue(keys.last() is RefreshKey)
    }

    @Test
    fun `饭点那一屏给付款码与校园卡`() {
        val keys = keysOf(
            11 * 60 + 30,
            SmartFacts(nowMinute = 11 * 60 + 30, courses = emptyList(), balance = "¥12.30"),
        )
        assertEquals(listOf(SmartKeyKind.PAYMENT, SmartKeyKind.CARD, SmartKeyKind.REFRESH), kinds(keys))
        assertEquals(AppRoute.PaymentCode, (keys.first() as NavKey).route)
    }

    @Test
    fun `待办那一屏的「去交」直接落到那条待办自己的页面`() {
        val keys = keysOf(900, SmartFacts(nowMinute = 900, nowMs = 1_000_000_000L, todos = listOf(todo(59))))
        assertEquals(listOf(SmartKeyKind.TASK, SmartKeyKind.INBOX, SmartKeyKind.REFRESH), kinds(keys))
        assertEquals(AppRoute.Attendance, (keys.first() as NavKey).route)
    }

    @Test
    fun `放假那一屏给校历`() {
        val keys = keysOf(15 * 60, SmartFacts(nowMinute = 15 * 60, holiday = "校庆"))
        assertEquals(listOf(SmartKeyKind.SCHOOL_CALENDAR, SmartKeyKind.REFRESH), kinds(keys))
    }

    @Test
    fun `座位动作跟着最近一次查到的按钮集合走`() {
        // 待入馆签到：签到 + 取消
        val checkin = SmartArbiter.card(
            SmartFacts(nowMinute = 612, courses = listOf(course), library = seat(listOf("入馆签到", "取消预约"))),
        )
        assertEquals(listOf(SmartKeyKind.CHECKIN, SmartKeyKind.CANCEL, SmartKeyKind.REFRESH), kinds(checkin.keys))
        assertTrue("动作键要带动作名", (checkin.keys.first() as SeatKey).action == "入馆签到")

        // 暂离中：返回 + 取消
        val back = SmartArbiter.card(
            SmartFacts(nowMinute = 900, library = seat(listOf("中途返回", "取消预约"), status = "暂离")),
        )
        assertEquals(listOf(SmartKeyKind.RETURN, SmartKeyKind.CANCEL, SmartKeyKind.REFRESH), kinds(back.keys))

        // 在馆内：离开 + 取消
        val inside = SmartArbiter.card(
            SmartFacts(nowMinute = 900, library = seat(listOf("中途离开", "取消预约"), status = "使用中")),
        )
        assertEquals(listOf(SmartKeyKind.LEAVE, SmartKeyKind.CANCEL, SmartKeyKind.REFRESH), kinds(inside.keys))
    }

    @Test
    fun `学校那边没有的动作绝不出现`() {
        // 只剩「取消预约」：不许凭空画一个「入馆签到」出来
        val block = SmartBlock(module = SmartModule.LIBRARY, label = "图书馆", title = "座位 3F-12", route = AppRoute.Library)
        val keys = SmartArbiter.keysOf(
            SmartFacts(nowMinute = 900, library = seat(listOf("取消预约"))),
            block,
        )
        assertEquals(listOf(SmartKeyKind.CANCEL, SmartKeyKind.REFRESH), kinds(keys))
        assertFalse("不该出现签到键", keys.any { it.kind == SmartKeyKind.CHECKIN })
    }

    @Test
    fun `没约座时扫码与选座是两个并列入口`() {
        // §5.8：猜错主次的代价是「点错按钮」，并排放的代价是零 —— 不做主次取舍
        val block = SmartBlock(module = SmartModule.LIBRARY, label = "图书馆", title = "空档", route = AppRoute.Library)
        val keys = SmartArbiter.keysOf(SmartFacts(nowMinute = 900), block)
        assertEquals(listOf(SmartKeyKind.QR, SmartKeyKind.SEAT, SmartKeyKind.REFRESH), kinds(keys))
        assertEquals(AppRoute.QrScan, (keys[0] as NavKey).route)
        assertEquals(AppRoute.Library, (keys[1] as NavKey).route)
    }

    @Test
    fun `任何场景都不超过三个键，且最后一个永远是刷新`() {
        val scenes = listOf(
            SmartFacts(nowMinute = 650, courses = listOf(course), checkedAgoMin = 1),
            SmartFacts(nowMinute = 612, courses = listOf(course), library = seat(listOf("入馆签到", "取消预约"))),
            SmartFacts(nowMinute = 900, library = seat(listOf("中途返回", "取消预约"), status = "暂离")),
            SmartFacts(nowMinute = 11 * 60 + 30, balance = "¥1.00"),
            SmartFacts(nowMinute = 900, nowMs = 1_000_000_000L, todos = listOf(todo(59))),
            SmartFacts(nowMinute = 15 * 60, holiday = "校庆"),
            SmartFacts(nowMinute = 3 * 60, courses = listOf(course)),
        )
        val layout = com.xjtu.toolbox.widget.smart.SmartLayout
        scenes.forEach { facts ->
            val card = SmartArbiter.card(facts)
            assertTrue("${card.block.label} 的键超过三个", card.keys.size <= layout.MAX_KEYS)
            assertTrue("${card.block.label} 的最后一个键必须是刷新", card.keys.last() is RefreshKey)
        }
    }

    @Test
    fun `键的文案就是动作名，无障碍要读得出来`() {
        val checkin = SmartArbiter.card(
            SmartFacts(nowMinute = 612, courses = listOf(course), library = seat(listOf("入馆签到", "取消预约"))),
        )
        assertEquals("入馆签到", checkin.keys.first().label)
        assertEquals("取消预约", checkin.keys[1].label)
        assertEquals("刷新", checkin.keys.last().label)
    }
}
