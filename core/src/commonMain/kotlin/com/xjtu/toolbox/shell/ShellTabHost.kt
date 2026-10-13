package com.xjtu.toolbox.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xjtu.toolbox.ui.components.LocalPageVisible
import kotlinx.coroutines.delay

/**
 * 当前页那一层：**画哪几页由 [ShellPageHost] 说**。
 *
 * - [ShellPageHost.VISITED_TABS]（`:app` 的五格）：选中过的 tab 才进组合，切走的留在组合里只是透明
 *   （切回来不重新取数），切换带横移 + 缩放。这一段原本在 `:app` 的 `main/MainScreen.kt` 里
 *   （`TabHost`），行为逐字未改：同一个「进过组合就留着」「280ms 后记住上一次是哪个 tab」
 *   「隐藏的格子冻结测量约束」。改的只有「认出是哪一格」的判据 —— 从 `BottomTab.ordinal`
 *   换成 [ShellTabs.indexOf]（值相同）。
 * - [ShellPageHost.CURRENT_ONLY]（桌面 / web）：只画当前这一页，不常驻。当前页不属于任何一格时
 *   （[ShellNavState.selected] 为 null）即使选了常驻那一档也走这里 —— 否则会画出一张全空的页。
 *
 * ⚠️ 这一层也**不**自己判「当前页是哪一格」：[ShellPage] 里的 [ShellPage.target] 就是从
 * [ShellNavState.target] 来的，端上注入的 `page` 拿到的就是它。
 *
 * @param pageHost 见 [ShellPageHost]。端上给一次，之后不动。
 * @param modifier 挂在整层上的修饰（玻璃风格下是内容区的采样层）
 * @param tabModifier 每个格子自己的修饰（玻璃风格下按顶栏折叠进度补留白）
 * @param content 画某一页（[ShellPage] 带着「是哪一页」与「它落在哪一格上」）。**画什么由端上注入**
 *   —— 每页要的宿主面不同（`:app` 那五个分支、桌面那 43 个分支就是这种 provider）。
 */
@Composable
fun ShellTabHost(
    nav: ShellNavState,
    pageHost: ShellPageHost = ShellPageHost.VISITED_TABS,
    modifier: Modifier = Modifier,
    tabModifier: () -> Modifier = { Modifier },
    content: @Composable (ShellPage) -> Unit,
) {
    // 只有当前这一页：不常驻、不横移（横移缩放只对「同层的几格」成立）
    if (!nav.keepsVisitedTabPages(pageHost)) {
        Box(modifier.fillMaxSize().then(tabModifier())) { content(nav.currentShellPage()) }
        return
    }

    val selectedId = nav.selected?.id
    var composedIds by remember { mutableStateOf(setOfNotNull(selectedId)) }
    LaunchedEffect(selectedId) { composedIds = composedIds + setOfNotNull(selectedId) }

    val selectedIndex = nav.tabs.indexOf(selectedId)
    var previousTabIndex by rememberSaveable { mutableIntStateOf(selectedIndex) }
    val tabSwitchDirection = selectedIndex.compareTo(previousTabIndex)
    LaunchedEffect(selectedId) {
        delay(280)
        previousTabIndex = selectedIndex
    }

    val tabSlideDistance = with(LocalDensity.current) { 28.dp.toPx() }
    Box(modifier.fillMaxSize()) {
        // 别在 key {} 里 return@key：Kotlin 2.4.20 会生成 R8 无法转 dex 的方法名
        nav.tabs.tabs.filter { it.id in composedIds }.forEach { tab ->
            key(tab) {
                val isActive = selectedId == tab.id
                val tabAlpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = tween(if (isActive) 240 else 180),
                    label = "tabAlpha",
                )
                val tabOffset by animateFloatAsState(
                    targetValue = when {
                        isActive || tabSwitchDirection == 0 -> 0f
                        nav.tabs.indexOf(tab.id) < selectedIndex -> -tabSlideDistance
                        else -> tabSlideDistance
                    },
                    animationSpec = tween(260),
                    label = "tabOffset",
                )
                val tabScale by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.985f,
                    animationSpec = tween(260),
                    label = "tabScale",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        // 隐藏的 tab 冻结测量约束，侧栏动画时不跟着逐帧重排
                        .freezeLayoutWhile { !isActive && tabAlpha == 0f }
                        .then(tabModifier())
                        .zIndex(if (isActive) 1f else 0f)
                        .graphicsLayer {
                            alpha = tabAlpha
                            translationX = tabOffset
                            scaleX = tabScale
                            scaleY = tabScale
                        }
                        .pointerInput(isActive) {
                            if (!isActive) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                    // 让隐藏 tab 里的常驻动画停下
                    CompositionLocalProvider(LocalPageVisible provides isActive) {
                        content(ShellPage(tab.target, tab))
                    }
                }
            }
        }
    }
}

/** [frozen] 为真时沿用上一次的测量约束，子树不跟着父布局尺寸动画重排。 */
@Composable
private fun Modifier.freezeLayoutWhile(frozen: () -> Boolean): Modifier {
    val last = remember { arrayOfNulls<Constraints>(1) }
    return this.layout { measurable, constraints ->
        val use = if (frozen()) last[0] ?: constraints else constraints.also { last[0] = it }
        val placeable = measurable.measure(use)
        layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
            placeable.place(0, 0)
        }
    }
}

/**
 * 壳交给 [AppShell] 的 `page` lambda 的**那一页**：是哪一页（[target]）+ 它落在哪一格上（[tab]）。
 *
 * 为什么要两样都给：一端的 `page` 可能按**格**认屏（`:app` 的五格各自一个分支，判据是
 * [ShellTab.id]），也可能按**路由**认屏（桌面 / web 的 43 条分支，判据是 [target]）——
 * 只给一样就会把另一众逼回「自己再算一遍」。
 *
 * @param target 当前页（[ShellNavState.target]）。**任何一档都能拿到它**：非 tab 路由、
 *   [ShellTarget.Custom] 也一样 —— 这是「当前页不属于任何一格时也能被画出来」那条口径的落点。
 * @param tab [target] 落在的那一格；**null = 不属于任何一格**（底栏 / 侧栏一格都不高亮）。
 */
class ShellPage(
    val target: ShellTarget,
    val tab: ShellTab?,
)

/**
 * 当前页那一层**留下谁** —— [AppShell] / [ShellTabHost] 按它分派两种摆法。
 *
 * 为什么会是这样参数（而不是给某一端另写一套壳）：两端的导航模型真的不同 ——
 * `:app` 的五格是**平行的五个屏**（切回来不该重新取数，切换带横移缩放），
 * 而桌面 / web 的导航模型是**路由表**（43 条里 37 条不属于任何一格，切换是「去另一页」而不是
 * 「在同层几格之间滑」）。同壳 = 同一套底栏 / 侧栏 / 占位 / 只把「留下谁」这一条交给端上说。
 */
enum class ShellPageHost {

    /** 进过组合的 tab 常驻（`:app` 的五格）。 */
    VISITED_TABS,

    /** 只画当前这一页（桌面 / web）。 */
    CURRENT_ONLY,
}

/**
 * 这一端要不要走「tab 常驻」那一档 —— 纯函数，好单测（壳的语义，不是端的事）。
 *
 * 当前页**不属于任何一格**时一律 false：tab 宿主那时候没一格是 active，
 * 画出来就是一张全空的页（可安静地错成白屏）⇒ 退回「只画当前这一页」。
 */
fun ShellNavState.keepsVisitedTabPages(pageHost: ShellPageHost): Boolean =
    pageHost == ShellPageHost.VISITED_TABS && selected != null

/**
 * 只画当前这一页时，壳交给 `page` 的那一个 [ShellPage] —— 纯函数，好单测。
 *
 * [ShellPage.tab] 可能为 null（当前页不属于任何一格），而 [ShellPage.target] **永远是当前页**：
 * 非 tab 路由、[ShellTarget.Custom] 都不例外。
 */
fun ShellNavState.currentShellPage(): ShellPage = ShellPage(target, selected)
