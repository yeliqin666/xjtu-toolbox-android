package com.xjtu.toolbox.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.AppShell
import com.xjtu.toolbox.shell.ShellNavState
import com.xjtu.toolbox.shell.ShellPageHost
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTarget
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.calculateIsWideLayout
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text

/** 共享壳在两种窗口形状下的形态：六格是画成侧栏、还是画成底栏，以及六格挤不挤得下。 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopShellRailJvmTest {

    /** 900×900 里「这一格属于侧栏」的判据：整格都在左边这条竖带里。 */
    private val railStripRight = 100f

    /** 520×900 里「这一格属于底栏」的判据：整格都在下边这条横带里。 */
    private val barStripTop = 700f

    private fun flatten(root: SemanticsNode): List<SemanticsNode> =
        listOf(root) + root.children.flatMap { flatten(it) }

    /** 合并树 + 未合并树：文字叶子（纯 Text）只在未合并树里是一枚独立的节点（宽度从它身上读）。 */
    private fun nodesOf(owner: SemanticsOwner): List<SemanticsNode> =
        flatten(owner.rootSemanticsNode) + flatten(owner.unmergedRootSemanticsNode)

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** 能点的那一层（`selectable` 会把格子里那几样合并上来，所以标签文字也在它身上）。 */
    private fun List<SemanticsNode>.clickable(label: String): SemanticsNode? =
        firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && label in it.texts() }

    /** 纯文字那一层：宽度就是那几个字真占的宽度（拿来判「有没有被挤到换行/截断」）。 */
    private fun List<SemanticsNode>.labelLeaf(label: String): SemanticsNode? =
        firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) == null && label in it.texts() }

    private fun renderScene(width: Int, height: Int, content: @Composable () -> Unit): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            XJTUToolBoxTheme { content() }
        }

    /** 把窗口形状喂给共享壳，壳自己判「底栏还是侧栏」（与 `ToolboxDesktopApp` 同型）。 */
    private fun shellScene(width: Int, height: Int, onSelect: (ShellTab) -> Unit = {}): ImageComposeScene =
        renderScene(width, height) {
            CompositionLocalProvider(LocalIsWideLayout provides calculateIsWideLayout()) {
                AppShell(
                    nav = ShellNavState(
                        tabs = desktopTabs(),
                        target = ShellTarget.App(AppRoute.Main),
                        onSelect = onSelect,
                    ),
                    pageHost = ShellPageHost.CURRENT_ONLY,
                    glassStyle = false,
                    bottomInset = 0.dp,
                    contentWindowInsets = WindowInsets(0),
                    page = { _, _ -> Box(Modifier.fillMaxSize()) { Text("当前页") } },
                )
            }
        }

    @Test
    fun `宽窗里六格画在左侧栏里（不是底栏），文字也没被挤掉`() {
        val tabs = desktopTabs()
        val scene = shellScene(width = 900, height = 900)
        try {
            scene.render(0L)
            val owner = assertNotNull(scene.semanticsOwners.firstOrNull(), "读不到语义树")
            val nodes = nodesOf(owner)

            tabs.tabs.forEach { tab ->
                val item = assertNotNull(nodes.clickable(tab.label), "宽窗里找不到「${tab.label}」那一格")
                val box = item.boundsInRoot
                assertTrue(
                    box.left >= 0f && box.right <= railStripRight,
                    "宽窗（900×900）里「${tab.label}」不在左侧栏里：left=${box.left} right=${box.right}",
                )
                val label = assertNotNull(nodes.labelLeaf(tab.label), "「${tab.label}」那格没有文字层")
                val w = label.boundsInRoot.width
                assertTrue(
                    w in 16f..70f,
                    "「${tab.label}」的文字宽度是 $w（侧栏内宽 80dp）—— 要么没画出来、要么被挤到换行/截断",
                )
            }
            // 侧栏是竖着排的：六格的纵坐标依次变大（底栏那一排六格会是同一个 y）
            val tops = tabs.tabs.map { assertNotNull(nodes.clickable(it.label), "${it.label} 不在").boundsInRoot.top }
            assertEquals(tops.sorted(), tops, "六格没有按竖序排：$tops —— 这看着像还画着底栏")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `窄窗（手机比例）里六格画在底栏里（不是侧栏）`() {
        val tabs = desktopTabs()
        val scene = shellScene(width = 520, height = 900)
        try {
            scene.render(0L)
            val owner = assertNotNull(scene.semanticsOwners.firstOrNull(), "读不到语义树")
            val nodes = nodesOf(owner)

            tabs.tabs.forEach { tab ->
                val item = assertNotNull(nodes.clickable(tab.label), "窄窗里找不到「${tab.label}」那一格")
                val box = item.boundsInRoot
                assertTrue(
                    box.top >= barStripTop && box.bottom <= 900f,
                    "窄窗（520×900）里「${tab.label}」不在底栏里（或底栏被挤到窗外了）：" +
                        "top=${box.top} bottom=${box.bottom}",
                )
                assertTrue(box.width > 40f, "底栏那一格只有 ${box.width} 宽 —— 六格挤不下?")
            }
            // 底栏是一排：六格的横坐标依次变大
            val lefts = tabs.tabs.map { assertNotNull(nodes.clickable(it.label), "${it.label} 不在").boundsInRoot.left }
            assertEquals(lefts.sorted(), lefts, "六格没有按横序排：$lefts —— 这看着像还画着侧栏")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `侧栏里点「全部」那一格，回调拿到的就是 AllPages`() {
        var picked: ShellTarget? = null
        val scene = shellScene(width = 900, height = 900, onSelect = { picked = it.target })
        try {
            scene.render(0L)
            val owner = assertNotNull(scene.semanticsOwners.firstOrNull(), "读不到语义树")
            val all = assertNotNull(nodesOf(owner).clickable("全部"), "侧栏里找不到「全部」那一格")
            val action = assertNotNull(all.config.getOrNull(SemanticsActions.OnClick), "「全部」那一格不可点")
            assertEquals(true, action.action?.invoke(), "「全部」那一格的点击没被处理")
            assertEquals(ShellTarget.AllPages, picked, "点了「全部」却没回 AllPages（这一格接错页了）")
        } finally {
            scene.close()
        }
    }
}
