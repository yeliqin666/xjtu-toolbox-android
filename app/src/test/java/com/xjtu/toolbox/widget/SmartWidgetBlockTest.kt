package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartLayout
import com.xjtu.toolbox.widget.smart.SmartModule
import com.xjtu.toolbox.widget.smart.SmartRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块的结构与「一屏放几个块」。
 *
 * 两条规矩：
 * 1. **一行一件事**：场景、标题、状态各自独立成行；状态绝不和标题挤一行。
 * 2. **一个块还是两个看「够不够要紧」**：前两件事都算要紧（P0/P1）才放两个各占一半；
 *    背景信息（P2：余额、考试、放假）只配当底部那条提示。
 */
class SmartWidgetBlockTest {

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
        checkedAgoMin = ago,
        checkedAtClock = "10:49",
    )

    @Test
    fun `场景与标题各自独立成行`() {
        val b = SmartArbiter.card(facts(nowMinute = 650)).blocks.first()
        assertEquals("上课中", b.label)
        assertEquals("高等数学(甲)", b.title)
        assertTrue("场景行不许包含课名", !b.label!!.contains(b.title))
        assertTrue("标题里不许混进场景词", !b.title.contains("上课中"))
    }

    @Test
    fun `状态不并进标题`() {
        val b = SmartArbiter.card(facts(nowMinute = 650)).blocks.first()
        assertEquals("迟到", b.status)
        assertTrue("标题里不许出现结论", !b.title.contains("迟到"))
        assertEquals("迟到 · 10:49 查过", b.statusLine)
    }

    @Test
    fun `两件都算要紧的事各占一半`() {
        // 上课中（P1）+ 两小时内截止的待办（P0）：都够格，放两个块
        val card = SmartArbiter.card(facts(nowMinute = 650, todos = listOf(todo(60))))
        assertEquals(2, card.blocks.size)
        assertEquals(SmartModule.ATTENDANCE, card.blocks[0].module)
        assertEquals(SmartModule.TODO, card.blocks[1].module)
        assertTrue(card.blocks.all { it.rank != SmartRank.P2 })
    }

    @Test
    fun `背景信息只当底部提示，不占半张卡`() {
        // 上课中（P1）+ 余额（P2）：余额不配占块，只写底部那条
        val card = SmartArbiter.card(facts(nowMinute = 650, balance = "¥128.60"))
        assertEquals(1, card.blocks.size)
        assertEquals(SmartModule.ATTENDANCE, card.blocks[0].module)
        assertTrue("余额降级为底部提示", card.hints.any { it.module == SmartModule.CAMPUS_CARD })
        assertFalse("提示不与块同源", card.hints.any { it.module == card.blocks[0].module })
    }

    @Test
    fun `同源的东西不会既占块又当提示`() {
        val lib = LibrarySeatFact(
            seat = "3F-12", area = "兴庆三层", status = "使用中",
            actions = listOf("中途离开", "取消预约"), at = 1_000_000_000L,
        )
        val card = SmartArbiter.card(facts(nowMinute = 612, library = lib, ago = null))
        assertTrue(card.blocks.any { it.module == SmartModule.LIBRARY })
        assertTrue("座位占了块就不再重复提示", card.hints.none { it.module == SmartModule.LIBRARY })
    }

    @Test
    fun `一块占满时把细节写满，不留大片空白`() {
        val card = SmartArbiter.card(facts(nowMinute = 650))
        // 110dp：块预算 69dp，只放得下三行（场景 + 标题 + 状态）
        assertEquals(0, SmartLayout.plan(card, SmartLayout.Form.COMPACT, 110, 172).blocks.first().details)
        // 140dp：块预算 99dp，五行（含两行细节）都放得下 —— 卡片被填满
        val roomy = SmartLayout.plan(card, SmartLayout.Form.COMPACT, 140, 172).blocks.first()
        assertEquals(2, roomy.details)
        assertEquals("还剩 70 分钟", roomy.block.detail)
        assertEquals("10:10–12:00", roomy.block.detail2)
    }

    @Test
    fun `两个块的判定看重要程度是否相近`() {
        // 上课中（P1）+ 待办（P0/P1）：相近 ⇒ 两个块
        assertEquals(2, SmartArbiter.card(facts(nowMinute = 650, todos = listOf(todo(60)))).blocks.size)
        // 已查证的迟到（P0）+ 余额（P2）：差两档 ⇒ 只一个块，细节交给它
        val late = SmartArbiter.card(facts(nowMinute = 650, balance = "¥128.60"))
        assertEquals(1, late.blocks.size)
        assertTrue("余额降级成底部提示", late.hints.any { it.module == SmartModule.CAMPUS_CARD })
    }
}
