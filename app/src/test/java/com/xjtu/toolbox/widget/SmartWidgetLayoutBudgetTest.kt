package com.xjtu.toolbox.widget

import com.xjtu.toolbox.widget.smart.SmartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 布局预算：留白、行高、控件 id、点击区。
 *
 * 这一组测的都是「真机上会不会被切掉、会不会挤成一团、会不会按键失灵」，全能在 JVM 里算清：
 * - 行高与内边距写在布局里，也写在 [SmartLayout] 的常量里，两处必须逐条对上；
 * - 两套布局的控件 id 必须完全一致（`RemoteViews` 找不到 id 会抛 `ActionException`）；
 * - 块里两个图标 + 底部刷新都在，而且**块本身也是可点的**（点击区不互相覆盖）。
 */
class SmartWidgetLayoutBudgetTest {

    private fun layout(name: String) = File("src/main/res/layout/$name")

    private val both = listOf("widget_smart_compact.xml", "widget_smart_wide.xml")

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
        val expected = buildMap {
            put("widget_smart_label1", SmartLayout.BLOCK_LEAD_DP)
            put("widget_smart_title1", SmartLayout.BLOCK_TITLE_DP)
            put("widget_smart_status1", SmartLayout.BLOCK_STATUS_DP)
            put("widget_smart_detail1", SmartLayout.BLOCK_DETAIL_DP)
            put("widget_smart_detail1b", SmartLayout.BLOCK_DETAIL_DP)
            put("widget_smart_label2", SmartLayout.BLOCK_LEAD_DP)
            put("widget_smart_title2", SmartLayout.BLOCK_TITLE_DP)
            put("widget_smart_status2", SmartLayout.BLOCK_STATUS_DP)
            put("widget_smart_detail2", SmartLayout.BLOCK_DETAIL_DP)
            put("widget_smart_detail2b", SmartLayout.BLOCK_DETAIL_DP)
            put("widget_smart_key1a", SmartLayout.KEY_ICON_DP)
            put("widget_smart_key1b", SmartLayout.KEY_ICON_DP)
            put("widget_smart_key2a", SmartLayout.KEY_ICON_DP)
            put("widget_smart_key2b", SmartLayout.KEY_ICON_DP)
            put("widget_smart_refresh", SmartLayout.REFRESH_DP)
        }
        for (name in both) {
            val actual = rowHeights(layout(name))
            expected.forEach { (id, dp) -> assertEquals("$name 的 $id 行高", dp, actual[id]) }
        }
    }

    @Test
    fun `两块靠底板和间距分开，不画分隔线`() {
        for (name in both) {
            val text = layout(name).readText()
            assertTrue("$name 不该再有分隔线", !text.contains("divider"))
            assertTrue("$name 块 2 要留 ${SmartLayout.TILE_GAP_DP}dp 间距", text.contains("\"${SmartLayout.TILE_GAP_DP}dp\""))
        }
    }

    @Test
    fun `单块的短标题放大成主数字，长标题和矮卡不放大`() {
        fun card(title: String) = com.xjtu.toolbox.widget.smart.SmartCard(
            blocks = listOf(
                com.xjtu.toolbox.widget.smart.SmartBlock(
                    module = com.xjtu.toolbox.widget.smart.SmartModule.ATTENDANCE,
                    label = "校园卡",
                    title = title,
                    status = "更新于 08:05",
                    route = com.xjtu.toolbox.nav.AppRoute.Attendance,
                ),
            ),
        )
        assertTrue(SmartLayout.plan(card("¥128.60"), SmartLayout.Form.COMPACT, 160, 172).hero)
        assertTrue(!SmartLayout.plan(card("高等数学(甲)与工程应用"), SmartLayout.Form.COMPACT, 160, 172).hero)
        assertTrue("110dp 放不下主数字", !SmartLayout.plan(card("¥128.60"), SmartLayout.Form.COMPACT, 110, 172).hero)
    }

    @Test
    fun `四周留够安全距离`() {
        // 背景是 16dp 圆角的形状：贴边的内容会被圆角切掉
        for (name in both) {
            val f = layout(name)
            assertEquals("$name 的左右内边距", "${SmartLayout.PAD_H_DP}dp", attr(f, "paddingHorizontal"))
            assertEquals("$name 的上下内边距", "${SmartLayout.PAD_V_DP}dp", attr(f, "paddingVertical"))
        }
    }

    @Test
    fun `块区用 weight 撑满，卡片里不留大块空白`() {
        // 「一个块占满 / 两块各一半」是靠 weight 实现的；没有 weight 就会像以前那样
        // 内容挤在上半部分、下半部分空着
        for (name in both) {
            val text = layout(name).readText()
            assertTrue("$name 的块要 0dp + weight", text.contains("android:layout_weight=\"1\""))
            assertTrue("$name 的块要 0dp", text.contains("android:layout_height=\"0dp\""))
        }
    }

    @Test
    fun `块里有自己的快捷键，底部还有刷新`() {
        for (name in both) {
            val text = layout(name).readText()
            assertTrue("$name 块 1 的两个键", text.contains("@+id/widget_smart_key1a") && text.contains("@+id/widget_smart_key1b"))
            assertTrue("$name 块 2 的两个键", text.contains("@+id/widget_smart_key2a") && text.contains("@+id/widget_smart_key2b"))
            assertTrue("$name 底部有刷新", text.contains("@+id/widget_smart_refresh"))
            assertTrue("$name 块本身要是可点的容器", text.contains("@+id/widget_smart_block1"))
        }
    }

    @Test
    fun `两套布局的控件 id 完全一致`() {
        assertEquals(ids(layout("widget_smart_compact.xml")), ids(layout("widget_smart_wide.xml")))
    }

    @Test
    fun `块里的图标按钮在最窄的卡里也排得下`() {
        val content = SmartLayout.DEFAULT_WIDTH_DP - SmartLayout.PAD_H_DP * 2
        assertTrue(SmartLayout.keysWidthDp(SmartLayout.MAX_KEYS) < content)
    }

    @Test
    fun `最紧的实例也在预算内`() {
        val chrome = SmartLayout.PAD_V_DP * 2 + SmartLayout.BOTTOM_GAP_DP + SmartLayout.BOTTOM_DP
        val oneBlock = SmartLayout.BLOCK_LEAD_DP + SmartLayout.BLOCK_TITLE_DP +
            SmartLayout.BLOCK_GAP_DP + SmartLayout.BLOCK_STATUS_DP
        assertTrue("一个块（三行）+ 底部条要能塞进 110dp", chrome + oneBlock <= SmartLayout.DEFAULT_HEIGHT_DP)
        // 四行（带细节）在 110dp 里就放不下了 —— 所以 plan() 要按高度决定细节行
        val four = oneBlock + SmartLayout.BLOCK_GAP_DP + SmartLayout.BLOCK_DETAIL_DP
        assertTrue("四行应当放不进最紧的卡", chrome + four > SmartLayout.DEFAULT_HEIGHT_DP)
    }

    @Test
    fun `按宽度收字，够就不动、不够才截`() {
        assertEquals("高等数学(甲)", SmartLayout.clampWidth("高等数学(甲)", 144, SmartLayout.SIZE_TITLE))
        assertEquals("高等数…", SmartLayout.clampWidth("高等数学(甲)", 82, SmartLayout.SIZE_TITLE))
        // 数字和冒号按窄字符算：「迟到 · 08:05 查过」里的时刻保得住
        assertEquals(
            "迟到 · 08:05 查过",
            SmartLayout.clampWidth("迟到 · 08:05 查过", 144, SmartLayout.SIZE_STATUS),
        )
    }

    @Test
    fun `空标题也不许炸`() {
        val block = com.xjtu.toolbox.widget.smart.SmartBlock(
            module = com.xjtu.toolbox.widget.smart.SmartModule.ATTENDANCE,
            label = null,
            title = "",
            route = com.xjtu.toolbox.nav.AppRoute.Attendance,
        )
        val plan = SmartLayout.plan(
            com.xjtu.toolbox.widget.smart.SmartCard(blocks = listOf(block)),
            SmartLayout.Form.COMPACT,
        )
        assertEquals("", plan.blocks.first().block.title)
    }
}
