package com.xjtu.toolbox.widget

import com.xjtu.toolbox.widget.smart.SmartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 布局预算（安全距离 + 装得下装不下）。
 *
 * 这一组测的都是「真机上会不会被切掉、会不会挤出去」，全是能在普通 JVM 单测里算清楚的：
 * - 四周内边距与行间距写在布局里，也写在 [SmartLayout] 的常量里，两处必须逐条对上；
 * - 两套布局的控件 id 必须完全一致（`RemoteViews` 找不到 id 会抛 `ActionException`，
 *   整张卡片变成「加载失败」）；
 * - 底栏的图标数有硬上限，算得出来；
 * - 各形态在 110dp（最紧）到 160dp（宽裕）下的高度都要落在预算内。
 */
class SmartWidgetLayoutBudgetTest {

    private fun layout(name: String) = File("src/main/res/layout/$name")

    private val both = listOf("widget_smart_compact.xml", "widget_smart_wide.xml")

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

    private fun attr(file: File, name: String): String? =
        Regex("""android:$name="([^"]+)"""").find(file.readText())?.groupValues?.get(1)

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
            "widget_smart_hook_divider" to SmartLayout.DIVIDER_DP,
            "widget_smart_keybar_divider" to SmartLayout.DIVIDER_DP,
        )
        for (name in both) {
            val actual = rowHeights(layout(name))
            expected.forEach { (id, dp) ->
                assertEquals("$name 的 $id 行高", dp, actual[id])
            }
        }
    }

    @Test
    fun `四周留够安全距离`() {
        // 背景是 16dp 圆角的形状：贴边的内容会被圆角切掉，而且 Android 12 起
        // 桌面本身还会再裁一圈。内边距必须写在布局里、且与预算常量一致。
        for (name in both) {
            val f = layout(name)
            assertEquals("$name 的左右内边距", "${SmartLayout.PAD_H_DP}dp", attr(f, "paddingHorizontal"))
            assertEquals("$name 的上下内边距", "${SmartLayout.PAD_V_DP}dp", attr(f, "paddingVertical"))
        }
    }

    @Test
    fun `行与行之间都有间距或分割线`() {
        // 光靠字号区分不开「一件事」的边界：状态行/细节行要有间距，
        // 块与钩子、内容与按键之间各有一条 1dp 分割线
        for (name in both) {
            val text = layout(name).readText()
            assertTrue("$name 的状态行要留间距", text.contains("android:layout_marginTop=\"${SmartLayout.GAP_STATUS_DP}dp\""))
            assertTrue("$name 要有钩子区横线", text.contains("@+id/widget_smart_hook_divider"))
            assertTrue("$name 要有按键区横线", text.contains("@+id/widget_smart_keybar_divider"))
            assertTrue("$name 要有左右分栏竖线", text.contains("@+id/widget_smart_vdivider"))
            assertTrue("$name 的分割线要有颜色", text.contains("android:background=\"@color/widget_divider\""))
        }
    }

    @Test
    fun `两套布局的控件 id 完全一致`() {
        // 形态差异只能体现在排版上；id 不一致会让某一形态直接「加载失败」
        assertEquals(ids(layout("widget_smart_compact.xml")), ids(layout("widget_smart_wide.xml")))
    }

    @Test
    fun `主区锚底，底栏不会被内容挤出去`() {
        for (name in both) {
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
    fun `底栏图标数按可用宽度算得出来`() {
        // 2×2 的卡宽 110dp，扣掉左右内边距剩 82dp
        val availableWidth = SmartLayout.DEFAULT_HEIGHT_DP - SmartLayout.PAD_H_DP * 2
        assertTrue("两个图标要放得下", SmartLayout.bottomBarWidthDp(2) <= availableWidth)
        assertTrue("三个就超了，所以紧凑卡只放两个", SmartLayout.bottomBarWidthDp(3) > availableWidth)
        // 宽卡至少 220dp，三个图标绰绰有余
        assertTrue(SmartLayout.bottomBarWidthDp(3) <= SmartLayout.WIDE_MIN_WIDTH_DP - SmartLayout.PAD_H_DP * 2)
    }

    @Test
    fun `最紧和最宽的实例都在预算内`() {
        // 110dp（最紧）：块放四行会超，所以那一档只留「场景 + 标题 + 状态」
        val tight = SmartLayout.heightOf(SmartLayout.Form.COMPACT, true, false, true, 0)
        assertTrue("三行 $tight dp 超过 110dp", tight <= SmartLayout.DEFAULT_HEIGHT_DP)

        // 四行（含细节行）在 110dp 里放不下 —— 所以 plan() 在这一档要砍掉细节行
        val fourRows = SmartLayout.heightOf(SmartLayout.Form.COMPACT, true, true, true, 0)
        assertTrue("四行应当放不进最紧的卡", fourRows > SmartLayout.DEFAULT_HEIGHT_DP)

        // 四行 + 两条钩子在哪一档都要靠降级，所以 plan() 不是可选项
        val worst = SmartLayout.heightOf(SmartLayout.Form.COMPACT, true, true, true, SmartLayout.COMPACT_MAX_HOOKS)
        assertTrue(worst > fourRows)

        // 宽卡的钩子在右栏，不占竖向预算：钩子再多也还是四行那个高度
        val wide = SmartLayout.heightOf(SmartLayout.Form.WIDE, true, true, true, SmartLayout.WIDE_MAX_HOOKS)
        assertEquals(fourRows, wide)
    }

    @Test
    fun `长文案在 Kotlin 侧就收住，不指望 ROM 的省略号`() {
        assertEquals("短标题", SmartLayout.clamp("短标题", SmartLayout.TITLE_MAX))
        assertEquals(null, SmartLayout.clamp("   ", SmartLayout.TITLE_MAX))
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
        assertTrue(plan.heightDp <= SmartLayout.DEFAULT_HEIGHT_DP)
    }

    @Test
    fun `按宽度收字，够就不动、不够才截`() {
        // 172dp 的 2×2 扣掉左右内边距还有 144dp：17sp 的标题放得下 6 个汉字
        assertEquals("高等数学(甲)", SmartLayout.clampWidth("高等数学(甲)", 144, SmartLayout.SIZE_TITLE))
        // 110dp 的极窄实例只放得下 3-4 个汉字（估算偏保守：真机大约 4.8 个）
        assertEquals("高等数…", SmartLayout.clampWidth("高等数学(甲)", 82, SmartLayout.SIZE_TITLE))
        // 数字和冒号按窄字符算：同一行里它们不会白占一整格，所以「查过」前面那个时刻保得住
        assertEquals(
            "迟到 · 08:05 查过",
            SmartLayout.clampWidth("迟到 · 08:05 查过", 144, SmartLayout.SIZE_STATUS),
        )
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
        // 按 172dp 的 2×2 算：这一行放得下，所以一个字都不该少
        val plan = SmartLayout.plan(card, SmartLayout.Form.COMPACT, 140, 172)
        assertEquals("可能迟到 · 上次查于 08:05", plan.block.statusLine)
    }
}
