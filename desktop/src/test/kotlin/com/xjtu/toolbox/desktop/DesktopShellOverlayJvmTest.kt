package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.library.LibrarySource
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.AppShell
import com.xjtu.toolbox.shell.ShellNavState
import com.xjtu.toolbox.shell.ShellPageHost
import com.xjtu.toolbox.shell.ShellTarget
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import org.jetbrains.skia.Image
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 共享壳是根 Scaffold ⇒ 页里的 miuix `Overlay*` 弹窗画在整窗范围里（含底栏），页框不裁它。 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopShellOverlayJvmTest {

    /** 经典底栏（miuix `NavigationBar`）那一条的高度：520×900 的证据图里量到的是 y=836..900。 */
    private val classicBarHeight = 64f

    /** miuix `DialogDefaults.outsideMargin.height`：弹窗底离宿主底 12dp。 */
    private val dialogOutsideMargin = 12f

    /** 库页那条「首次使用提示」的标题 —— 就是 `shell-after-login.png` 底部那条差里那张卡。 */
    private val dialogTitleLabel = "图书馆座位预约"

    /** 夹具取数：一份能预约的 [DemoLibrarySource]（`canBook = true`）。 */
    private class FixtureLibrarySource : LibrarySource by DemoLibrarySource() {
        override val canBook: Boolean get() = true
    }

    /** 一次渲染量到的东西：语义树那一侧（底栏 / 弹窗标题）+ 像素那一侧（弹窗卡片）。 */
    private class Probe(
        /** 底栏第一格那一块（`首页`）—— 它的 `top` 就是页框的底。 */
        val bar: Rect?,
        /** 弹窗标题（`图书馆座位预约`）那一块：miuix 那个 popup 宿主里的文字。 */
        val dialogTitle: Rect?,
        /** 弹窗卡片占了哪几行（像素量出来的）。 */
        val cardRows: IntRange,
        val texts: List<String>,
    )
    private fun flatten(root: SemanticsNode): List<SemanticsNode> =
        listOf(root) + root.children.flatMap { flatten(it) }

    /** 合并树 + 未合并树：纯 `Text` 只在其中一棵里是一枚独立的节点。 */
    private fun nodesOf(owner: SemanticsOwner): List<SemanticsNode> =
        flatten(owner.rootSemanticsNode) + flatten(owner.unmergedRootSemanticsNode)

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun List<SemanticsNode>.withText(label: String): SemanticsNode? =
        firstOrNull { label in it.texts() }

    /** 弹窗卡片占了哪几行（闭区间；末行 + 1 就是卡片的底边）。 */
    private fun Image.cardRows(): IntRange {
        val pixels = requireNotNull(peekPixels()) { "弹窗那一层读不回来（peekPixels 给了 null）" }
        fun brightChannel(argb: Int, shift: Int) = ((argb shr shift) and 0xFF) > 230
        var first = -1
        var last = -1
        for (y in 0 until height) {
            var run = 0
            var longest = 0
            for (x in 0 until width) {
                val argb = pixels.getColor(x, y)
                val bright = brightChannel(argb, 24) && brightChannel(argb, 16) &&
                    brightChannel(argb, 8) && brightChannel(argb, 0)
                run = if (bright) run + 1 else 0
                if (run > longest) longest = run
            }
            if (longest >= 200) {
                if (first < 0) first = y
                last = y
            }
        }
        return first..last
    }

    /** 渲染给定的内容并量出 [Probe]：520×900（与 `Main.kt` 的默认窗口同比例）。 */
    private fun probe(content: @Composable () -> Unit): Probe {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val scene = onEdt {
            ImageComposeScene(width = 520, height = 900, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalIsWideLayout provides false,
                ) {
                    XJTUToolBoxTheme { content() }
                }
            }
        }
        try {
            onEdt { scene.render(0L) }
            repeat(12) { i ->
                Thread.sleep(150)
                onEdt { scene.render((i + 1) * 300_000_000L) }
            }
            return onEdt {
                val image = scene.render(13 * 300_000_000L)
                val semantics = assertNotNull(scene.semanticsOwners.firstOrNull(), "读不到语义树")
                val nodes = nodesOf(semantics)
                Probe(
                    bar = nodes.withText("首页")?.boundsInRoot,
                    dialogTitle = nodes.withText(dialogTitleLabel)?.boundsInRoot,
                    cardRows = image.cardRows(),
                    texts = nodes.flatMap { it.texts() }.distinct(),
                )
            }
        } finally {
            onEdt { scene.close() }
        }
    }

    /** 库页本身：真屏 + 夹具取数 + 提示会弹（这一枪要看的正是那个弹窗）。 */
    @Composable
    private fun libraryPage() {
        LibraryScreen(source = FixtureLibrarySource(), onBack = {}, showFirstUseHint = true)
    }

    @Composable
    private fun shellWithLibraryPage() {
        AppShell(
            nav = ShellNavState(
                tabs = desktopTabs(),
                target = ShellTarget.App(AppRoute.Library),
                onSelect = {},
            ),
            pageHost = ShellPageHost.CURRENT_ONLY,
            glassStyle = false,
            bottomInset = 0.dp,
            contentWindowInsets = WindowInsets(0),
            page = { _, _ -> libraryPage() },
        )
    }

    @Test
    fun `页里的弹窗挂在壳这个根 Scaffold 上：铺到窗口底、压在底栏上`() {
        val probe = probe { shellWithLibraryPage() }
        println("PROBE[shell] 底栏第一格=${probe.bar} 弹窗标题=${probe.dialogTitle}")
        println("PROBE[shell] 弹窗卡片 rows=${probe.cardRows.first}..${probe.cardRows.last}")
        println("PROBE[shell] 语义树文字=${probe.texts}")

        val bar = assertNotNull(probe.bar, "底栏第一格不在语义树里")
        assertTrue(
            bar.top >= 900f - classicBarHeight,
            "底栏第一格的顶是 ${bar.top} —— 经典底栏应当在最下面那 64px 里（836..900）",
        )
        assertTrue(bar.bottom <= 900f + 0.5f, "底栏第一格的底是 ${bar.bottom} —— 越出窗口了")

        val title = assertNotNull(probe.dialogTitle, "弹窗标题不在语义树里 —— 提示没弹？")

        val card = probe.cardRows
        val cardBottom = card.last + 1f
        assertTrue(
            cardBottom > bar.top,
            "弹窗卡片底是 $cardBottom、页框底是 ${bar.top} —— 卡片没压在底栏那一条上，" +
                "说明它被页框裁了（那就不是「挂在根 Scaffold 上」）",
        )
        // 两侧互为对照：语义树里那个标题就落在像素量出来的那张卡里
        assertTrue(
            title.top >= card.first - 1f && title.bottom <= cardBottom + 1f,
            "弹窗标题 $title 不在像素量出来的那张卡（rows=${card.first}..${card.last}）里 —— " +
                "两边量的不是同一个东西？",
        )

        assertEquals(
            900f - dialogOutsideMargin,
            cardBottom,
            4f,
            "弹窗卡片底应当锚在窗口底（900−12=888），实际是 $cardBottom",
        )
    }

    @Test
    fun `旧壳那种容器（Column + 64dp 的条）里弹窗被页框裁在底栏上方，差的就是那 64`() {
        val inShell = probe { shellWithLibraryPage() }
        val inShellShape = inShell.cardRows.last + 1f

        // 模拟没有 Scaffold 的壳：内容 Box + 64dp 底栏（画成中灰，避免被误当成弹窗卡片）。
        val oldShape = probe {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { libraryPage() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(classicBarHeight.dp)
                        .background(Color(0xFF808080)),
                )
            }
        }
        val cardBottom = oldShape.cardRows.last + 1f
        val inShellTitle = assertNotNull(inShell.dialogTitle, "壳里那个弹窗标题不在语义树里")
        val oldShapeTitle = assertNotNull(oldShape.dialogTitle, "旧壳那种容器里弹窗标题不在语义树里")
        println("PROBE[old-shape] 卡片底边=$cardBottom（页框底 = 900 − 64 = 836）/ 壳里=$inShellShape")
        println("PROBE[old-shape] 标题底：旧壳那种容器=${oldShapeTitle.bottom} / 壳里=${inShellTitle.bottom}")

        assertEquals(
            836f - dialogOutsideMargin,
            cardBottom,
            4f,
            "旧壳那种容器里卡片底应当锚在**页框**底（836−12=824），实际是 $cardBottom",
        )
        assertEquals(
            classicBarHeight,
            inShellShape - cardBottom,
            1f,
            "两种容器下弹窗卡片底的差应当正好是底栏那一条（$classicBarHeight）：" +
                "壳里 $inShellShape / 旧壳那种容器 $cardBottom",
        )
        // 同样的一个 64 在**语义树**那一层再量一次（与像素互相印证）
        assertEquals(
            classicBarHeight,
            inShellTitle.bottom - oldShapeTitle.bottom,
            1f,
            "语义树上弹窗标题底的差也应当是 $classicBarHeight：" +
                "壳里 ${inShellTitle.bottom} / 旧壳那种容器 ${oldShapeTitle.bottom}",
        )
    }
}

/** 在 AWT 事件队列上执行并等结果（与 `RenderScreens.onEdt` 同一份纪律）。 */
private fun <T> onEdt(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            failure = t
        }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
