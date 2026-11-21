package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仲裁表与联网边界。
 *
 * 仲裁是**有序短路取前两件**：靠前的是主要信息，靠后的那条如果也够要紧就占另一半。
 * 联网那边判的是「这一屏还欠不欠一次查询」—— 一节课两个点、无课日零次，不许多问一句。
 */
class SmartWidgetBoundaryTest {

    /** 第 3-4 节 10:10–12:00。 */
    private val morning = WidgetCourse(
        name = "高等数学", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0, startSection = 3, endSection = 4,
    )

    /** 第 5 节 14:00–14:50，用来当「下一节」。 */
    private val afternoon = WidgetCourse(
        name = "大学物理", location = "主楼B-201",
        startMinute = 840, endMinute = 890, color = 0, startSection = 5, endSection = 5,
    )

    private fun todo(minutesLeft: Long) = InboxItem(
        id = "lms-1", category = InboxCategories.LMS, source = "思源学堂",
        title = "实验报告", expiresAt = 1_000_000_000L + minutesLeft * 60_000L,
    )

    private fun seat(actions: List<String>) = LibrarySeatFact(
        seat = "3F-12", area = "兴庆三层", status = "已预约",
        actions = actions, at = 1_000_000_000L,
    )

    private fun facts(
        nowMinute: Int,
        courses: List<WidgetCourse> = listOf(morning, afternoon),
        todos: List<InboxItem> = emptyList(),
        library: LibrarySeatFact? = null,
        balance: String? = null,
        holiday: String? = null,
        scheduleDiff: String? = null,
        ago: Int? = 1,
        lastCheckAtMs: Long? = null,
    ) = SmartFacts(
        nowMinute = nowMinute,
        nowMs = 1_000_000_000L,
        courses = courses,
        date = "2026-11-17",
        todos = todos,
        library = library,
        balance = balance,
        holiday = holiday,
        scheduleDiff = scheduleDiff,
        checkedAgoMin = ago,
        checkedAtClock = "10:49",
        lastCheckAtMs = lastCheckAtMs,
    )

    private fun first(f: SmartFacts) = SmartArbiter.card(f).blocks.first()

    // ══════ 仲裁表 ══════

    @Test
    fun `课优先于饭点`() {
        // 11:10-12:00 的第 4 节与午饭窗口重叠：缺勤改不了，饭可以晚十分钟吃
        val lunch = WidgetCourse(
            name = "大学物理", location = "主楼B-201",
            startMinute = 670, endMinute = 720, color = 0, startSection = 4, endSection = 4,
        )
        val card = SmartArbiter.card(facts(nowMinute = 11 * 60 + 30, courses = listOf(lunch), balance = "¥128.60"))
        assertEquals(SmartModule.ATTENDANCE, card.blocks.first().module)
        assertEquals("上课中", card.blocks.first().label)
        // 饭点降为底部提示：余额还在，只是不占块
        assertTrue("余额该降级成提示", card.hints.any { it.module == SmartModule.CAMPUS_CARD })
    }

    @Test
    fun `待入馆签到与当前课并列，签到在前`() {
        // 座位丢了就是丢了，缺一节课还能补 —— 硬窗口排在前面
        val card = SmartArbiter.card(facts(nowMinute = 612, library = seat(listOf("入馆签到", "取消预约"))))
        assertEquals(SmartModule.LIBRARY, card.blocks.first().module)
        assertEquals("座位 3F-12", card.blocks.first().title)
    }

    @Test
    fun `两小时内截止的待办是 P0`() {
        val card = SmartArbiter.card(facts(nowMinute = 900, todos = listOf(todo(59))))
        assertEquals(SmartModule.TODO, card.blocks.first().module)
        assertEquals("还有 59 分钟", card.blocks.first().status)
    }

    @Test
    fun `没课的时候按下一节、饭点、课表变动、放假、兜底依次退让`() {
        // 9:30：早饭窗口已过，下一节 10:10 在 120 分钟内 ⇒ 报下一节
        assertEquals(SmartModule.NEXT_CLASS, first(facts(nowMinute = 9 * 60 + 30)).module)
        // 11:30：下一节 14:00 还在 120 分钟之外 ⇒ 饭点占块
        assertEquals(
            SmartModule.CAMPUS_CARD,
            first(facts(nowMinute = 11 * 60 + 30, courses = listOf(afternoon), balance = "¥12.30")).module,
        )
        // 15:00 且有课表变动
        assertEquals(
            SmartModule.SCHEDULE_DIFF,
            first(facts(nowMinute = 15 * 60, scheduleDiff = "高等数学换教室")).module,
        )
        // 15:00 且今天一门课都没有：放假 / 今天没课
        assertEquals("放假", first(facts(nowMinute = 15 * 60, courses = emptyList(), holiday = "校庆")).label)
        // 15:00 课上完了：兜底报余额
        val done = first(facts(nowMinute = 15 * 60, courses = listOf(morning), balance = "¥8.00"))
        assertEquals("今天的课上完了", done.label)
    }

    @Test
    fun `拿不到的东西不上屏`() {
        // 什么都没有：不画占位符，也不假装「今天没课」，只报一句能解释的话
        val card = SmartArbiter.card(SmartFacts(nowMinute = 3 * 60, courses = emptyList()))
        assertEquals("今天没课", card.blocks.first().label)
        assertEquals("余额未同步", card.blocks.first().title)
    }

    // ══════ 联网边界 ══════

    @Test
    fun `没有课就一次联网都不排`() {
        assertEquals(SmartArbiter.CheckKind.NONE, SmartArbiter.checkNeeded(facts(650, courses = emptyList(), ago = null)))
    }

    @Test
    fun `上课前不排查询，上课那一刻才欠一次`() {
        assertEquals(SmartArbiter.CheckKind.NONE, SmartArbiter.checkNeeded(facts(600, ago = null)))
        assertEquals(SmartArbiter.CheckKind.DUE, SmartArbiter.checkNeeded(facts(610, ago = null)))
    }

    @Test
    fun `打卡窗口关的那一刻再欠一次`() {
        val nowMinute = 616
        val dayStart = 1_000_000_000L - nowMinute * 60_000L
        // 10:12 问过一次（Q1 之后、Q2 之前）：窗口关的那一刻还得再问
        assertEquals(
            SmartArbiter.CheckKind.DUE,
            SmartArbiter.checkNeeded(facts(nowMinute, ago = 4, lastCheckAtMs = dayStart + 612 * 60_000L)),
        )
        // 10:15 正点问过：不欠了，而且刚查过 ⇒ 也不用保鲜
        assertEquals(
            SmartArbiter.CheckKind.NONE,
            SmartArbiter.checkNeeded(facts(nowMinute, ago = 1, lastCheckAtMs = dayStart + 615 * 60_000L)),
        )
    }

    @Test
    fun `结论放超过十分钟就补查一次，措辞同时降级`() {
        val nowMinute = 650
        val dayStart = 1_000_000_000L - nowMinute * 60_000L
        val f = facts(nowMinute, ago = 30, lastCheckAtMs = dayStart + 620 * 60_000L)
        assertEquals(SmartArbiter.CheckKind.REFRESH, SmartArbiter.checkNeeded(f))
        assertEquals("可能迟到", SmartArbiter.card(f).blocks.first().status)
    }
}
