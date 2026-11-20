package com.xjtu.toolbox.widget

import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartBlock
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块的结构（`docs/smart-widget.md` §2.3）：
 *
 * **一行 = 一件事**。场景（「上课中」）和标题（课名）是两件互不相干的事，不能拼成
 * 「上课中 · 高等数学(甲)」——那会把「上课中」这整块压成一个前缀，用户要多读一遍。
 *
 * 同理，状态（「迟到」）**要么独占一行，要么这个块整个不出现**，
 * 绝不做「高等数学(甲) · 迟到」这种降级（§8.1 明确说那是错的做法）。
 */
class SmartWidgetBlockTest {

    private val course = WidgetCourse(
        name = "高等数学(甲)", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0, startSection = 3, endSection = 4,
    )

    private fun block(facts: SmartFacts): SmartBlock = SmartArbiter.card(facts).block

    @Test
    fun `场景与标题各自独立成行`() {
        val b = block(SmartFacts(nowMinute = 650, courses = listOf(course)))
        assertEquals("上课中", b.label)
        assertEquals("高等数学(甲)", b.title)
        // 谁都不是谁的前缀：拼在一起就是把两件事压成一件
        assertTrue("场景行不许包含课名", !b.label!!.contains(b.title))
        assertTrue("标题里不许混进场景词", !b.title.contains("上课中"))
    }

    @Test
    fun `状态不并进标题`() {
        val b = block(SmartFacts(nowMinute = 650, courses = listOf(course), checkedAgoMin = 1, checkedAtClock = "10:49"))
        assertEquals("迟到", b.status)
        assertTrue("标题里不许出现结论", !b.title.contains("迟到"))
        // 状态行是独立字段，渲染时才和时效拼成一句
        assertEquals("迟到 · 10:49 查过", b.statusLine)
    }

    @Test
    fun `没有状态的块不画空行`() {
        // 下一节课那一屏没有结论可说：status 与 statusLine 都必须是空的，
        // 渲染侧据此把整行 GONE（不留一条空行占 17dp）
        val b = block(SmartFacts(nowMinute = 480 + 60, courses = listOf(course)))
        assertEquals(SmartModule.NEXT_CLASS, b.module)
        assertNull(b.status)
        assertNull(b.statusLine)
    }

    @Test
    fun `一件事一个模块，钩子不与主要信息同源`() {
        val facts = SmartFacts(
            nowMinute = 650, courses = listOf(course),
            balance = "¥128.60", holiday = null,
        )
        val card = SmartArbiter.card(facts)
        assertEquals(SmartModule.ATTENDANCE, card.block.module)
        assertTrue("钩子不许重复主要信息", card.hooks.none { it.module == card.block.module })
    }

    @Test
    fun `场景行写的是场景词，不是功能名`() {
        // 「图书馆」是场景，「座位 3F-12」才是这件事；反过来说「图书馆页」就不对了
        val lib = com.xjtu.toolbox.library.LibrarySeatFact(
            seat = "3F-12", area = "兴庆三层", status = "使用中",
            actions = listOf("中途离开", "取消预约"), at = 0L,
        )
        val b = block(SmartFacts(nowMinute = 900, library = lib))
        assertEquals("图书馆", b.label)
        assertEquals("座位 3F-12", b.title)
        assertEquals("兴庆三层", b.detail)
        assertEquals("使用中", b.status)
    }
}
