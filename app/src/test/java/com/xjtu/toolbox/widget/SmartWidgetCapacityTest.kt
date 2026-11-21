package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量：一个块占满还是两块各一半、细节行留不留、底部提示写得下几条。
 *
 * 全部按**实例的真实尺寸**算出来的，所以这一组直接把同一屏内容摆在几个典型尺寸上：
 *
 * | 实例 | 结果 |
 * |---|---|
 * | 110×172（最紧的 2×2） | 一个块三行，没有细节；提示一条 |
 * | 140×172（常见的 2×2） | 一个块四行（含细节）；提示一条 |
 * | 160×172（2×2 撑高） | 两个块各三行 |
 * | 140×344（4×2） | 两个块并排，各四行 |
 */
class SmartWidgetCapacityTest {

    private val course = WidgetCourse(
        name = "高等数学(甲)", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0, startSection = 3, endSection = 4,
    )

    private fun todo(minutesLeft: Long) = InboxItem(
        id = "lms-1", category = InboxCategories.LMS, source = "思源学堂",
        title = "实验报告", expiresAt = 1_000_000_000L + minutesLeft * 60_000L,
    )

    private fun facts(
        nowMinute: Int = 650,
        todos: List<InboxItem> = emptyList(),
        balance: String? = null,
        library: LibrarySeatFact? = null,
        notice: String? = null,
    ) = SmartFacts(
        nowMinute = nowMinute,
        nowMs = 1_000_000_000L,
        courses = listOf(course),
        date = "2026-11-17",
        todos = todos,
        balance = balance,
        library = library,
        notice = notice,
        checkedAgoMin = 1,
        checkedAtClock = "10:49",
    )

    /** 上课 + 两小时内截止的待办：两块都够格。 */
    private fun busy() = SmartArbiter.card(facts(todos = listOf(todo(60)), balance = "¥128.60"))

    @Test
    fun `最紧的实例只放一个块，细节行让位`() {
        val plan = SmartLayout.plan(busy(), SmartLayout.Form.COMPACT, 110, 172)
        assertEquals(1, plan.blocks.size)
        assertTrue(plan.blocks.first().block.detail == null)
        assertTrue("110dp 放不下两个块", !plan.twoBlocks)
    }

    @Test
    fun `常见的 2×2 能放下一个完整的块`() {
        val plan = SmartLayout.plan(busy(), SmartLayout.Form.COMPACT, 140, 172)
        assertEquals(1, plan.blocks.size)
        assertTrue("细节行回来了", plan.blocks.first().withDetail)
    }

    @Test
    fun `撑高之后两块各占一半`() {
        val plan = SmartLayout.plan(busy(), SmartLayout.Form.COMPACT, 160, 172)
        assertEquals(2, plan.blocks.size)
        assertTrue(plan.twoBlocks)
        // 两块的预算只有 56dp，只放得下「场景 + 标题 + 状态」
        assertFalse(plan.blocks[0].withDetail)
        assertFalse(plan.blocks[1].withDetail)
    }

    @Test
    fun `宽卡两块并排，各拿满高`() {
        val plan = SmartLayout.plan(busy(), SmartLayout.Form.WIDE, 140, 344)
        assertEquals(2, plan.blocks.size)
        assertTrue("并排时各自的高度都是满高，细节行留得住", plan.blocks.all { it.withDetail })
    }

    @Test
    fun `块里只排得下两个键`() {
        val lib = LibrarySeatFact(
            seat = "3F-12", area = "兴庆三层", status = "使用中",
            actions = listOf("入馆签到", "取消预约"), at = 1_000_000_000L,
        )
        val card = SmartArbiter.card(facts(nowMinute = 612, library = lib))
        val block = card.blocks.first { it.module == com.xjtu.toolbox.widget.smart.SmartModule.LIBRARY }
        assertEquals(2, block.keys.size)
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT, 160, 172)
        assertTrue(plan.blocks.all { it.block.keys.size <= SmartLayout.MAX_KEYS })
    }

    @Test
    fun `底部那条提示按宽度拼，装不下就少一条`() {
        val many = SmartArbiter.card(
            facts(todos = listOf(todo(1500)), balance = "¥128.60", notice = "教务 · 新通知"),
        )
        val wide = SmartLayout.plan(many, SmartLayout.Form.WIDE, 140, 344)
        val narrow = SmartLayout.plan(many, SmartLayout.Form.COMPACT, 140, 110)
        assertTrue("宽卡能拼更多", wide.hint != null)
        assertTrue("窄卡至少留一条", narrow.hint != null)
        assertTrue(
            "提示不许长过一行",
            (narrow.hint ?: "").length <= (wide.hint ?: "").length,
        )
    }

    @Test
    fun `没有次要信息时底部只剩刷新`() {
        val plan = SmartLayout.plan(SmartArbiter.card(facts()), SmartLayout.Form.COMPACT, 140, 172)
        assertTrue(plan.hint == null)
    }

    @Test
    fun `形态按实例宽度选，尺寸单位不统一也要认对`() {
        assertEquals(SmartLayout.Form.COMPACT, SmartLayout.formOf(110))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(220))
        assertEquals(110, SmartWidgetUpdater.optionDp(110))
        assertEquals(110, SmartWidgetUpdater.optionDp(28160))
        assertEquals(0, SmartWidgetUpdater.optionDp(0))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(SmartWidgetUpdater.optionDp(250 * 256)))
    }
}
