package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量与降级（`docs/smart-widget.md` §2.5 / §8.1）：
 *
 * - 一个块占多行，砍的时候按顺序整行砍：**多余的钩子 → 钩子全砍 → 细节行 → 状态行**；
 * - **标题、场景行、底栏永远不砍**；
 * - 放不下时高度必须落到 110dp 以内 —— 「不挤出」这件事得算得出来，不能靠真机看。
 */
class SmartWidgetCapacityTest {

    private val course = WidgetCourse(
        name = "高等数学(甲)", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0, startSection = 3, endSection = 4,
    )

    /** 一条 `minutesLeft` 分钟后截止的待办。source 留空时块就没有细节行。 */
    private fun todo(minutesLeft: Long, source: String = "思源学堂") = InboxItem(
        id = "lms-1", category = InboxCategories.LMS, source = source,
        title = "实验报告", expiresAt = 1_000_000_000L + minutesLeft * 60_000L,
    )

    /** 已约上的座位（没有「入馆签到」，所以不会抢主要信息，只会当钩子）。 */
    private val seat = LibrarySeatFact(
        seat = "3F-12", area = "兴庆三层", status = "使用中",
        actions = listOf("中途离开", "取消预约"), at = 1_000_000_000L,
    )

    private fun facts(
        nowMinute: Int = 650,
        todos: List<InboxItem> = emptyList(),
        balance: String? = null,
        library: LibrarySeatFact? = null,
        notice: String? = null,
        courses: List<WidgetCourse> = listOf(course),
        ago: Int? = 1,
    ) = SmartFacts(
        nowMinute = nowMinute,
        nowMs = 1_000_000_000L,
        courses = courses,
        date = "2026-11-17",
        todos = todos,
        balance = balance,
        library = library,
        notice = notice,
        checkedAgoMin = ago,
        checkedAtClock = "10:49",
    )

    /**
     * 下午三点：今天已经没课，一屏只剩待办 —— 这样主要信息是待办，
     * 座位 / 余额 / 通知才有机会当钩子（有课时「已查证的迟到」会先占块，见仲裁表第 1 条）。
     */
    private fun offClass(
        todos: List<InboxItem> = emptyList(),
        balance: String? = null,
        library: LibrarySeatFact? = null,
        notice: String? = null,
    ) = SmartFacts(
        nowMinute = 15 * 60,
        nowMs = 1_000_000_000L,
        courses = listOf(course),
        date = "2026-11-17",
        todos = todos,
        balance = balance,
        library = library,
        notice = notice,
        checkedAgoMin = 60,
        checkedAtClock = "10:49",
    )

    @Test
    fun `紧凑卡放不下三条钩子时先砍钩子`() {
        val card = SmartArbiter.card(
            offClass(todos = listOf(todo(30)), balance = "¥128.60", library = seat, notice = "教务 · 新通知"),
        )
        assertEquals("三条钩子都该被挑出来", 3, card.hooks.size)
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        assertEquals("110dp 里只剩得下一条钩子", 1, plan.hooks.size)
        assertEquals(SmartLayout.CARD_HEIGHT_DP, plan.heightDp)
    }

    @Test
    fun `砍的时候状态行与标题永不被砍`() {
        val card = SmartArbiter.card(
            offClass(todos = listOf(todo(30)), balance = "¥128.60", library = seat, notice = "教务 · 新通知"),
        )
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        assertEquals("实验报告", plan.block.title)
        assertNotNull("状态行不该被砍", plan.block.statusLine)
        assertTrue(plan.heightDp <= SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `宽卡右栏能把三条钩子都留下而高度不变`() {
        val card = SmartArbiter.card(
            offClass(todos = listOf(todo(30)), balance = "¥128.60", library = seat, notice = "教务 · 新通知"),
        )
        val compact = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        val wide = SmartLayout.plan(card, SmartLayout.Form.WIDE)
        assertEquals("宽卡右栏竖着排得下", card.hooks.size, wide.hooks.size)
        assertTrue("宽卡比紧凑卡能多放", wide.hooks.size > compact.hooks.size)
        // 钩子在右栏，不占竖向预算
        assertTrue(wide.heightDp <= SmartLayout.CARD_HEIGHT_DP)
        assertTrue(compact.heightDp <= SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `块四行加一条钩子正好装满一百一十`() {
        val card = SmartArbiter.card(facts(todos = listOf(todo(1500)), balance = "¥128.60"))
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        assertEquals(1, plan.hooks.size)
        assertEquals(SmartLayout.CARD_HEIGHT_DP, plan.heightDp)
    }

    @Test
    fun `没有钩子的屏高度更矮，仍然合规`() {
        val card = SmartArbiter.card(facts())
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        assertTrue(plan.hooks.isEmpty())
        assertTrue(plan.heightDp < SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `细节行是可选的那个，可以整行没有`() {
        // 待办那一屏只有在知道来源时才有细节行；没有就是没有，不塞占位
        val card = SmartArbiter.card(offClass(todos = listOf(todo(30, source = "")), balance = "¥9.90"))
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        assertNull("没有来源就没有细节行", plan.block.detail)
        assertTrue(plan.heightDp <= SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `两种形态都只取三个键`() {
        val card = SmartArbiter.card(facts())
        for (form in SmartLayout.Form.values()) {
            val plan = SmartLayout.plan(card, form)
            assertTrue("底栏只有三个位置", plan.keys.size <= SmartLayout.MAX_KEYS)
        }
    }

    @Test
    fun `形态按实例宽度选`() {
        assertEquals(SmartLayout.Form.COMPACT, SmartLayout.formOf(110))
        assertEquals(SmartLayout.Form.COMPACT, SmartLayout.formOf(219))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(220))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(320))
    }
}
