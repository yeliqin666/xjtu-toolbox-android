package com.xjtu.toolbox.widget

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartCard
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量与降级。
 *
 * 卡片的高度是**按实例算**的（`OPTION_APPWIDGET_MIN_HEIGHT`），所以这一组把同一屏内容
 * 摆在 110 / 124 / 152 / 167dp 四个高度上，看它分别留下什么：
 *
 * | 高度 | 留下 |
 * |---|---|
 * | 110（最紧的 2×2） | 场景 + 标题 + 状态 + 底栏 |
 * | 124 | 再加细节行 |
 * | 152 | 再加一条钩子 |
 * | 167 | 两条钩子（紧凑卡上限） |
 *
 * 砍的顺序永远一样：**多余的钩子 → 细节行 → 状态行 → 场景行**；标题和底栏永不砍。
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

    /** 下午三点：今天已经没课，一屏只剩待办 —— 主要信息是待办，座位/余额/通知才能当钩子。 */
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

    /** 有三条钩子可挑的一屏（待办占块，座位 + 余额 + 通知当钩子）。 */
    private fun busy(): SmartCard = SmartArbiter.card(
        offClass(todos = listOf(todo(30)), balance = "¥128.60", library = seat, notice = "教务 · 新通知"),
    )

    private fun plan(height: Int) = SmartLayout.plan(busy(), SmartLayout.Form.COMPACT, height)

    @Test
    fun `最紧的 110dp 只留一个块和底栏`() {
        val p = plan(110)
        assertEquals("实验报告", p.block.title)
        assertNotNull("状态行不该被砍", p.block.statusLine)
        assertNull("110dp 放不下细节行", p.block.detail)
        assertTrue("110dp 放不下钩子", p.hooks.isEmpty())
        assertTrue(p.heightDp <= SmartLayout.DEFAULT_HEIGHT_DP)
    }

    @Test
    fun `多 14dp 就能把细节行放回来`() {
        val p = plan(124)
        assertEquals("思源学堂", p.block.detail)
        assertTrue(p.hooks.isEmpty())
        assertEquals(124, p.heightDp)
    }

    @Test
    fun `再宽裕一点才开始写钩子`() {
        assertEquals(1, plan(152).hooks.size)
        assertEquals(2, plan(167).hooks.size)
        // 紧凑卡最多两条，再多也不会挤进来
        assertEquals(SmartLayout.COMPACT_MAX_HOOKS, plan(400).hooks.size)
    }

    @Test
    fun `砍的顺序是钩子、细节、状态，标题永不砍`() {
        // 从最宽裕一路收紧，逐档检查「谁还在」
        val rich = plan(400)
        assertEquals(2, rich.hooks.size)
        assertNotNull(rich.block.detail)

        val mid = plan(124)
        assertTrue("钩子先没", mid.hooks.isEmpty())
        assertNotNull("细节还在", mid.block.detail)

        val tight = plan(110)
        assertNull("然后才砍细节", tight.block.detail)
        assertNotNull("状态行最后动", tight.block.statusLine)
        assertEquals("标题永远在", "实验报告", tight.block.title)
    }

    @Test
    fun `宽卡右栏不算竖向预算，钩子照样排得下`() {
        // 4×2 的实例大约 344×140dp；纵向只有 140，但钩子在右栏
        val p = SmartLayout.plan(busy(), SmartLayout.Form.WIDE, 140, 344)
        assertTrue("宽卡把三条钩子都留下", p.hooks.size >= 3)
        assertEquals(124, p.heightDp)
        assertTrue("宽卡才显示左右分栏", p.showRailDivider)
        assertTrue("宽卡的钩子在右栏，不需要横线", !p.showHookDivider)
    }

    @Test
    fun `紧凑卡的钩子区有自己的横线`() {
        assertTrue(plan(152).showHookDivider)
        assertTrue("没有钩子就不画横线", !plan(110).showHookDivider)
    }

    @Test
    fun `底栏位置按宽度算：紧凑两个、宽卡三个`() {
        // 图书馆那一屏天然有三个键（签到 + 取消 + 刷新）
        val lib = LibrarySeatFact(
            seat = "3F-12", area = "兴庆三层", status = "使用中",
            actions = listOf("入馆签到", "取消预约"), at = 1_000_000_000L,
        )
        val card = SmartArbiter.card(SmartFacts(nowMinute = 612, courses = listOf(course), library = lib))
        assertEquals(3, card.keys.size)

        val compact = SmartLayout.plan(card, SmartLayout.Form.COMPACT, 110, 172)
        assertEquals(SmartLayout.COMPACT_MAX_KEYS, compact.keys.size)
        // 砍的是中间那个（取消），最右的刷新必须保住
        assertEquals(com.xjtu.toolbox.widget.smart.SmartKeyKind.CHECKIN, compact.keys.first().kind)
        assertTrue(compact.keys.last() is com.xjtu.toolbox.widget.smart.RefreshKey)

        val wide = SmartLayout.plan(card, SmartLayout.Form.WIDE, 140, 344)
        assertEquals(3, wide.keys.size)
    }

    @Test
    fun `形态按实例宽度选`() {
        assertEquals(SmartLayout.Form.COMPACT, SmartLayout.formOf(110))
        assertEquals(SmartLayout.Form.COMPACT, SmartLayout.formOf(219))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(220))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(320))
    }

    @Test
    fun `选项里的尺寸单位不统一也要认对`() {
        // 同一个 110dp：有的桌面给 110，有的按 1/256 dp 给 28160
        assertEquals(110, SmartWidgetUpdater.optionDp(110))
        assertEquals(110, SmartWidgetUpdater.optionDp(28160))
        assertEquals(0, SmartWidgetUpdater.optionDp(0))
        assertEquals(SmartLayout.Form.COMPACT, SmartLayout.formOf(SmartWidgetUpdater.optionDp(28160)))
        assertEquals(SmartLayout.Form.WIDE, SmartLayout.formOf(SmartWidgetUpdater.optionDp(250 * 256)))
    }
}
