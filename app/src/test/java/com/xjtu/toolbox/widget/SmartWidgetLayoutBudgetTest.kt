package com.xjtu.toolbox.widget

import com.xjtu.toolbox.widget.smart.SmartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 布局预算（`docs/smart-widget.md` §7.3 / §5.6）。
 *
 * 这一组测的是**能不能装下**，而不是好不好看：
 * - 各行的行高写在布局里，也写在 [SmartLayout] 的常量里，两处必须逐条对上 ——
 *   对不上的话预算就是假的，真机上会重新挤出卡片；
 * - 两套布局的控件 id 必须完全一致：`RemoteViews` 找不到 id 会抛 `ActionException`，
 *   整张卡片会变成「加载失败」占位；
 * - 底栏三个 20dp 图标是 110dp 的卡放得下的上限，四个就超了。
 */
class SmartWidgetLayoutBudgetTest {

    private fun layout(name: String) = File("src/main/res/layout/$name")

    /** `android:id` → 那一行写死的行高（dp）。 */
    private fun rowHeights(file: File): Map<String, Int> {
        val element = Regex("""<(\w+)\s+android:id="@\+id/(widget_smart_\w+)"([^>]*)>""")
        return element.findAll(file.readText()).mapNotNull { m ->
            val height = Regex("""android:layout_height="(\d+)dp"""").find(m.groupValues[3])
                ?.groupValues?.get(1)?.toInt()
            height?.let { m.groupValues[2] to it }
        }.toMap()
    }

    private fun ids(file: File): Set<String> =
        Regex("""android:id="@\+id/(widget_smart_\w+)"""")
            .findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `布局里写死的行高与预算常量逐条一致`() {
        val expected = mapOf(
            "widget_smart_label" to SmartLayout.ROW_LABEL_DP,
            "widget_smart_title" to SmartLayout.ROW_TITLE_DP,
            "widget_smart_detail" to SmartLayout.ROW_DETAIL_DP,
            "widget_smart_status" to SmartLayout.ROW_STATUS_DP,
            "widget_smart_hook1" to SmartLayout.ROW_HOOK_DP,
            "widget_smart_hook2" to SmartLayout.ROW_HOOK_DP,
            "widget_smart_hook3" to SmartLayout.ROW_HOOK_DP,
            "widget_smart_hook4" to SmartLayout.ROW_HOOK_DP,
        )
        for (name in listOf("widget_smart_compact.xml", "widget_smart_wide.xml")) {
            val actual = rowHeights(layout(name))
            expected.forEach { (id, dp) ->
                assertEquals("$name 的 $id 行高", dp, actual[id])
            }
        }
    }

    @Test
    fun `两套布局的控件 id 完全一致`() {
        // 形态差异只能体现在排版上；id 不一致会让某一形态直接「加载失败」
        assertEquals(
            ids(layout("widget_smart_compact.xml")),
            ids(layout("widget_smart_wide.xml")),
        )
    }

    @Test
    fun `主区锚底，底栏不会被内容挤出去`() {
        // 6.0.1 的 #408 就是主区全 wrap_content、没有一处 weight，内容一多就把底栏顶出卡片
        for (name in listOf("widget_smart_compact.xml", "widget_smart_wide.xml")) {
            val text = layout(name).readText()
            assertTrue("$name 的主区要 0dp + weight=1", text.contains("android:layout_height=\"0dp\""))
            assertTrue("$name 的主区要 weight", text.contains("android:layout_weight=\"1\""))
            assertTrue(
                "$name 的底栏行高要与预算常量一致",
                text.contains("android:layout_height=\"${SmartLayout.ROW_KEYS_DP}dp\""),
            )
        }
    }

    @Test
    fun `块四行加一条钩子不超过卡片最小高度`() {
        val h = SmartLayout.heightDp(
            form = SmartLayout.Form.COMPACT,
            hasLabel = true, hasDetail = true, hasStatus = true, hooks = 1,
        )
        assertTrue("$h dp 超了 ${SmartLayout.CARD_HEIGHT_DP}dp", h <= SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `底栏三个图标放得下，四个放不下`() {
        // 卡的最小宽度也是 110dp：扣掉左右内边距剩 102dp 可用
        val availableWidth = SmartLayout.CARD_HEIGHT_DP - SmartLayout.PAD_DP * 2
        assertTrue("三个图标要放得下", SmartLayout.bottomBarWidthDp(3) <= availableWidth)
        assertTrue("四个图标就超了，所以底栏只有三个位置", SmartLayout.bottomBarWidthDp(4) > availableWidth)
    }

    @Test
    fun `最坏情况也不超预算`() {
        // 块四行 + 三条钩子：超了就得靠降级，降级后必须回到预算内
        val worst = SmartLayout.heightDp(SmartLayout.Form.COMPACT, true, true, true, SmartLayout.COMPACT_MAX_HOOKS)
        assertTrue("不降级时会超，所以才需要 plan()", worst > SmartLayout.CARD_HEIGHT_DP)

        val fits = SmartLayout.heightDp(SmartLayout.Form.COMPACT, true, true, true, 1)
        assertEquals(SmartLayout.CARD_HEIGHT_DP, fits)

        val wide = SmartLayout.heightDp(SmartLayout.Form.WIDE, true, true, true, SmartLayout.WIDE_MAX_HOOKS)
        assertTrue("宽卡的钩子在右栏，不占竖向预算", wide <= SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `长文案在 Kotlin 侧就收住，不指望 ROM 的省略号`() {
        // 不同 ROM 的 ellipsize 行为不一致，一行溢出会把下面那行顶掉
        assertEquals("短标题", SmartLayout.clamp("短标题", SmartLayout.TITLE_MAX))
        assertNull(SmartLayout.clamp("   ", SmartLayout.TITLE_MAX))
        val long = "高等数学（甲）与大学物理（乙）联合实验课" + "再加几个字"
        val clamped = SmartLayout.clamp(long, SmartLayout.TITLE_MAX)!!
        assertEquals(SmartLayout.TITLE_MAX, clamped.length)
        assertTrue("截断了就要让人看出来", clamped.endsWith("…"))
    }

    @Test
    fun `空标题也不许炸`() {
        // 教务偶尔给空课名之类的脏数据：卡片进程里抛出去就是「加载失败」，这里只许退化成空行
        val block = com.xjtu.toolbox.widget.smart.SmartBlock(
            module = com.xjtu.toolbox.widget.smart.SmartModule.ATTENDANCE,
            label = null,
            title = "",
            route = com.xjtu.toolbox.nav.AppRoute.Attendance,
        )
        val plan = SmartLayout.plan(com.xjtu.toolbox.widget.smart.SmartCard(block = block), SmartLayout.Form.COMPACT)
        assertEquals("", plan.block.title)
        assertTrue(plan.heightDp <= SmartLayout.CARD_HEIGHT_DP)
    }

    @Test
    fun `状态行按整句收，时效不会被单独截掉`() {
        val block = com.xjtu.toolbox.widget.smart.SmartBlock(
            module = com.xjtu.toolbox.widget.smart.SmartModule.ATTENDANCE,
            label = "上课中",
            title = "高等数学",
            status = "可能迟到",
            meta = "上次查于 08:05",
            route = com.xjtu.toolbox.nav.AppRoute.Attendance,
        )
        val card = com.xjtu.toolbox.widget.smart.SmartCard(block = block)
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT)
        assertEquals("可能迟到 · 上次查于 08:05", plan.block.statusLine)
    }
}
