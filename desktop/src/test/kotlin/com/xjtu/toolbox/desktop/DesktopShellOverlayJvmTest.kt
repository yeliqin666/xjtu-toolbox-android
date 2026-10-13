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

/**
 * 共享壳是**根 Scaffold** ⇒ 页里的 miuix `Overlay*` 弹窗画在整窗范围里（含底栏），页框不裁它。
 *
 * ## 为什么要钉这一条
 *
 * `shell-after-login.png` 的底部一条与旧桌面壳的基线对不上：库页那条「首次使用提示」弹窗整体
 * **下移 64px**（正好是底栏的高度），底栏那一块被压暗。当年一度被当成「页拿到了整窗高度、
 * `bottomBar` 的槽被判空」的退化 —— 这对用例就是来证伪它的：读**语义树 bounds**（底栏 / 页框）
 * 与**像素**（弹窗卡片那几行）。
 *
 * 真相在 miuix 那条契约上（`miuix-ui` 的 `overlay/OverlayDialog.kt` + `layout/DialogContentLayout.kt`）：
 *
 *  - `OverlayDialog(renderInRootScaffold = true)`（**默认值**）把弹窗挂到**最外层 Scaffold** 的
 *    `MiuixPopupHost` 上，KDoc 原文就是「When true (default), the dialog covers the full screen」；
 *  - 定位是 `Alignment.BottomCenter` + `outsideMargin.height`（`DialogDefaults` = 12dp）——
 *    于是**弹窗底 = 宿主底 − 12dp**；
 *  - 变暗那一层（`MiuixTheme.colorScheme.windowDimming`，浅色下是 30% 黑）`.fillMaxSize()` 也画在宿主里。
 *
 * 旧桌面壳（`6283fad2` 之前那一份 `ToolboxDesktopApp`）的结构是
 * `Column { Box(Modifier.weight(1f)) { 当前页 }; NavigationBar { … } }` —— **一个 Scaffold 都没有**，
 * 于是「最外层 Scaffold」落在**页自己**身上：弹窗被页框（520×836）裁在底栏上方、底栏也不在变暗范围内。
 * 共享壳 `AppShell` 本身就是一个 `Scaffold`（`:app` 的 `MainScreen.kt` 从第一天起就是这样），
 * 于是宿主变成 520×900 的整窗。
 *
 * 一句话：**变的是弹窗的宿主（页 → 壳），不是页的高度**；三端共用同一份壳，`:app` 一直是「弹窗铺满整窗」。
 *
 * ## 这一对用例怎么钉
 *
 * 同一份夹具（[FixtureLibrarySource]，`canBook = true` ⇒ 那两条提示会弹）+ 同一个页（[LibraryScreen]），
 * 只换外面那层容器：
 *
 * | 容器 | 最外层 Scaffold | 底栏那一格（语义树） | 弹窗卡片底（像素） |
 * |---|---|---|---|
 * | `AppShell`（这一端现在的壳） | 壳（520×900） | top = 836 | **888 = 900 − 12** |
 * | `Column { Box(weight 1f) { 页 }; 64dp 那一条 }`（旧壳的结构） | 页自己（836 高） | （没有底栏可读） | **824 = 836 − 12** |
 *
 * 两者相差正好 64 = 那条底栏的高度。⚠️ 「页内容到不到底」与这条差**无关**：差的那 64 是宿主的底
 * 从 836 变成 900，与页里画了多少东西无关。
 *
 * ## 为什么弹窗的位置要从像素上量
 *
 * miuix 的 `Overlay*` 画在自己的 popup 宿主里，那一层**不进离屏场景的语义树**（实测：同一个场景
 * 的 PNG 上明明有那张卡，`图书馆座位预约` 在合并树与未合并树里都找不到）⇒ 位置只能量像素
 * （见 [cardRows]）。底栏与页框那一侧仍然走语义树（[nodesOf]），两侧互为对照。
 *
 * ⚠️ 所有 Compose 调用都走 [onEdt]（与 `RenderScreens` 同一条纪律）：`:core` 的屏一律用
 * `viewModel { }` + `viewModelScope`，而它在桌面上跑 `Dispatchers.Main` = AWT 事件队列 ——
 * 不在 EDT 上渲染，取数协程不推进、`animateTo` 那条进场动画也不动，测出来会是一张空白页。
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopShellOverlayJvmTest {

    /** 经典底栏（miuix `NavigationBar`）那一条的高度：520×900 的证据图里量到的是 y=836..900。 */
    private val classicBarHeight = 64f

    /** miuix `DialogDefaults.outsideMargin.height`：弹窗底离宿主底 12dp。 */
    private val dialogOutsideMargin = 12f

    /** 库页那条「首次使用提示」的标题 —— 就是 `shell-after-login.png` 底部那条差里那张卡。 */
    private val dialogTitleLabel = "图书馆座位预约"

    /**
     * 夹具取数：一份**能预约**的 [DemoLibrarySource]（`canBook = true`）。
     *
     * `DemoLibrarySource` 自己是只读口径（`canBook = false` / `hasSeatPlan = false`），库页那两条「首次
     * 使用提示」在它上面**整个不弹**（见 `LibraryScreen`：`if (showHint && hintTips.isNotEmpty())`）。
     * 委托出去只改 `canBook`，座位数据（24 个）照旧 —— 提示要弹，这条链才动得起来。
     */
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

    /**
     * 弹窗卡片占了哪几行（闭区间；末行 + 1 就是卡片的底边）。
     *
     * 判据：某一行里有一段 **≥ 200px 的亮色连续段**（`alpha` 与三通道都 `> 230`）。弹窗卡片是屏上
     * 唯一那样的东西 —— 页面与底栏都被它那层 30% 黑压暗了（浅色主题下 247 → 172），卡片底圆角 /
     * 抗锯齿只影响最上 / 最下 1~2 行。
     */
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

    /**
     * 渲染给定的内容并量出 [Probe]：520×900（与 `Main.kt` 的默认窗口同比例 ⇒ 经典底栏那一档），
     * 渲染到「那条约 300ms 的进场 tween 停下来」为止（时基靠 `render` 的 nanoTime 推进，
     * 真实时间留给 ViewModel 的取数协程）。
     */
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

        // ① 底栏在下边那一条带里（语义树）：与证据图里量到的 y=836..900 同一条
        val bar = assertNotNull(probe.bar, "底栏第一格不在语义树里")
        assertTrue(
            bar.top >= 900f - classicBarHeight,
            "底栏第一格的顶是 ${bar.top} —— 经典底栏应当在最下面那 64px 里（836..900）",
        )
        assertTrue(bar.bottom <= 900f + 0.5f, "底栏第一格的底是 ${bar.bottom} —— 越出窗口了")

        // ② 页里的弹窗确实弹了（语义树里有它那个标题）
        val title = assertNotNull(probe.dialogTitle, "弹窗标题不在语义树里 —— 提示没弹？")

        // ③ 弹窗卡片（像素）：它**越过了页框**（页框底 = 底栏顶 = 836）
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

        // ④ 卡片底 = **窗口**底 − outsideMargin，不是页框底 − outsideMargin
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

        // 旧桌面壳的结构（`6283fad2` 之前的 `ToolboxDesktopApp`）：Column + weight(1f) 的内容 Box
        // + 下面那一条 64dp 的 NavigationBar。**一个 Scaffold 都没有** —— 页自己就是最外层那一个。
        // 那一条画成中灰：这里要的是「页框只有 836 高」这件事，不是复刻底栏的画法（顺带也不会被
        // 上面那条「亮色连续段」判据误当成弹窗卡片）。
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

/**
 * 在 AWT 事件队列上执行并等结果（与 `RenderScreens.onEdt` 同一份纪律）。
 *
 * 为什么必须有它：`:core` 的屏一律用 `viewModel { }` + `viewModelScope`，而 viewModelScope 跑在
 * `Dispatchers.Main.immediate` 上 —— 桌面端的 Main 就是 AWT 事件队列。离屏场景在**调用线程**上渲染，
 * 不在 EDT 就会出现「VM 在 EDT 上更新状态、渲染线程同时在 measure/layout」⇒ Skia 直接抛
 * `performMeasureAndLayout called during measure layout`（实测踩到，且表现为「屏上永远没有数据」）。
 */
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
