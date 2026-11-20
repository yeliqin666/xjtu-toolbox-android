package com.xjtu.toolbox.widget

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.attendanceMarksOf
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
 * 底栏快捷键。
 *
 * 第一原则是**不重复**：点内容块能到的地方，底栏不许再放一个图标。
 * 以前上课那一屏同时有「点标题进考勤页」和一颗「考勤」图标、下一节那一屏同时有
 * 「点标题进日程」和一颗「日程」图标 —— 一个目的地两个入口，白占地方。
 *
 * 所以这一组除了「有几个键」，更要断言「**哪个键不该有**」。
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

    private fun normalMarks() = attendanceMarksOf(
        listOf(
            AttendanceWaterRecord(
                sbh = "1", startTime = 3, endTime = 4, week = 6, date = "2026-11-17",
                courseName = "高等数学", courseCode = "", status = WaterType.NORMAL,
            ),
        ),
    )

    private fun keys(facts: SmartFacts): List<SmartKey> = SmartArbiter.card(facts).keys

    private fun kinds(keys: List<SmartKey>) = keys.map { it.kind }

    @Test
    fun `上课那一屏只补扫码，不再重复一个「考勤」图标`() {
        val ks = keys(SmartFacts(nowMinute = 650, courses = listOf(course), checkedAgoMin = 1))
        // 点标题就进考勤页了，所以这里只有「扫码」这个到不了的地方 + 刷新
        assertEquals(listOf(SmartKeyKind.QR, SmartKeyKind.REFRESH), kinds(ks))
        assertTrue("最右永远是刷新", ks.last() is RefreshKey)
    }

    @Test
    fun `考勤已经是定论时连刷新都不用`() {
        val ks = keys(
            SmartFacts(
                nowMinute = 650, courses = listOf(course), date = "2026-11-17",
                attendance = normalMarks(),
            ),
        )
        // 学校已经写下「正常」，再点刷新也刷不出新东西，那个位置干脆空着
        assertEquals(listOf(SmartKeyKind.QR), kinds(ks))
    }

    @Test
    fun `下一节那一屏补的是考勤，不是日程`() {
        val ks = keys(SmartFacts(nowMinute = 9 * 60 + 30, courses = listOf(course)))
        assertEquals(listOf(SmartKeyKind.ATTENDANCE, SmartKeyKind.REFRESH), kinds(ks))
        assertEquals(AppRoute.Attendance, (ks.first() as NavKey).route)
    }

    @Test
    fun `饭点补校园卡，兜底补付款码`() {
        val meal = keys(SmartFacts(nowMinute = 11 * 60 + 30, courses = emptyList(), balance = "¥12.30"))
        assertEquals(listOf(SmartKeyKind.CARD, SmartKeyKind.REFRESH), kinds(meal))
        assertEquals(AppRoute.CampusCard, (meal.first() as NavKey).route)

        val done = keys(SmartFacts(nowMinute = 15 * 60, courses = listOf(course), balance = "¥8.00"))
        assertEquals(listOf(SmartKeyKind.PAYMENT, SmartKeyKind.REFRESH), kinds(done))
        assertEquals(AppRoute.PaymentCode, (done.first() as NavKey).route)
    }

    @Test
    fun `待办那一屏补收纳，不重复那条待办自己的入口`() {
        val ks = keys(SmartFacts(nowMinute = 900, nowMs = 1_000_000_000L, todos = listOf(todo(59))))
        assertEquals(listOf(SmartKeyKind.INBOX, SmartKeyKind.REFRESH), kinds(ks))

        // 待办没有指定页面时，块本身就落在收纳，那就不用再补收纳
        val noRoute = keys(
            SmartFacts(nowMinute = 900, nowMs = 1_000_000_000L, todos = listOf(todo(59, route = null))),
        )
        assertEquals(listOf(SmartKeyKind.REFRESH), kinds(noRoute))
    }

    @Test
    fun `考试与课表变动那一屏只剩刷新`() {
        val diff = keys(SmartFacts(nowMinute = 15 * 60, scheduleDiff = "高等数学换教室"))
        assertEquals(listOf(SmartKeyKind.REFRESH), kinds(diff))
    }

    @Test
    fun `放假补日程`() {
        val ks = keys(SmartFacts(nowMinute = 15 * 60, courses = emptyList(), holiday = "校庆"))
        assertEquals(listOf(SmartKeyKind.CALENDAR, SmartKeyKind.REFRESH), kinds(ks))
    }

    @Test
    fun `座位动作跟着最近一次查到的按钮集合走`() {
        val checkin = SmartArbiter.card(
            SmartFacts(nowMinute = 612, courses = listOf(course), library = seat(listOf("入馆签到", "取消预约"))),
        )
        assertEquals(
            listOf(SmartKeyKind.CHECKIN, SmartKeyKind.CANCEL, SmartKeyKind.REFRESH),
            kinds(checkin.keys),
        )
        assertEquals("入馆签到", (checkin.keys.first() as SeatKey).action)

        val back = SmartArbiter.card(
            SmartFacts(nowMinute = 900, library = seat(listOf("中途返回", "取消预约"), status = "暂离")),
        )
        assertEquals(
            listOf(SmartKeyKind.RETURN, SmartKeyKind.CANCEL, SmartKeyKind.REFRESH),
            kinds(back.keys),
        )

        val inside = SmartArbiter.card(
            SmartFacts(nowMinute = 900, library = seat(listOf("中途离开", "取消预约"), status = "使用中")),
        )
        assertEquals(
            listOf(SmartKeyKind.LEAVE, SmartKeyKind.CANCEL, SmartKeyKind.REFRESH),
            kinds(inside.keys),
        )
    }

    @Test
    fun `学校那边没有的动作绝不出现`() {
        // 只剩「取消预约」：不许凭空画一个「入馆签到」出来
        val block = SmartBlock(
            module = SmartModule.LIBRARY, label = "图书馆", title = "座位 3F-12",
            route = AppRoute.Library,
        )
        val ks = SmartArbiter.keysOf(
            SmartFacts(nowMinute = 900, library = seat(listOf("取消预约"))),
            block,
        )
        assertEquals(listOf(SmartKeyKind.CANCEL, SmartKeyKind.REFRESH), kinds(ks))
        assertFalse("不该出现签到键", ks.any { it.kind == SmartKeyKind.CHECKIN })
    }

    @Test
    fun `空档期只补扫码，选座就是点块本身`() {
        val block = SmartBlock(
            module = SmartModule.LIBRARY, label = "图书馆", title = "空档",
            route = AppRoute.Library,
        )
        val ks = SmartArbiter.keysOf(SmartFacts(nowMinute = 900), block)
        assertEquals(listOf(SmartKeyKind.QR, SmartKeyKind.REFRESH), kinds(ks))
        assertEquals(AppRoute.QrScan, (ks.first() as NavKey).route)
    }

    @Test
    fun `任何场景都不超过三个键，且刷新总在最右`() {
        val scenes = listOf(
            SmartFacts(nowMinute = 650, courses = listOf(course), checkedAgoMin = 1),
            SmartFacts(nowMinute = 612, courses = listOf(course), library = seat(listOf("入馆签到", "取消预约"))),
            SmartFacts(nowMinute = 900, library = seat(listOf("中途返回", "取消预约"), status = "暂离")),
            SmartFacts(nowMinute = 11 * 60 + 30, balance = "¥1.00"),
            SmartFacts(nowMinute = 900, nowMs = 1_000_000_000L, todos = listOf(todo(59))),
            SmartFacts(nowMinute = 15 * 60, holiday = "校庆"),
            SmartFacts(nowMinute = 900, nowMs = 1_000_000_000L, exam = null),
            SmartFacts(nowMinute = 3 * 60, courses = listOf(course)),
        )
        scenes.forEach { facts ->
            val card = SmartArbiter.card(facts)
            assertTrue(
                "${card.block.label} 的键超过三个",
                card.keys.size <= com.xjtu.toolbox.widget.smart.SmartLayout.MAX_KEYS,
            )
            assertTrue("${card.block.label} 的最后一个键必须是刷新", card.keys.last() is RefreshKey)
        }
    }

    @Test
    fun `键的文案就是动作名，无障碍读得出来`() {
        val checkin = SmartArbiter.card(
            SmartFacts(nowMinute = 612, courses = listOf(course), library = seat(listOf("入馆签到", "取消预约"))),
        )
        assertEquals("入馆签到", checkin.keys.first().label)
        assertEquals("取消预约", checkin.keys[1].label)
        assertEquals("刷新", checkin.keys.last().label)
    }
}
