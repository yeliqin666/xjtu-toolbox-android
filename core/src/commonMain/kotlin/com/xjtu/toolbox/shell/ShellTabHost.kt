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

/** 当前页那一层：画哪几页由 [ShellPageHost] 说。 */
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

/** 壳交给 [AppShell] 的 `page` lambda 的那一页：是哪一页（[target]）+ 它落在哪一格上（[tab]）。 */
class ShellPage(
    val target: ShellTarget,
    val tab: ShellTab?,
)

/** 当前页那一层留下谁 —— [AppShell] / [ShellTabHost] 按它分派两种摆法。 */
enum class ShellPageHost {

    /** 进过组合的 tab 常驻（`:app` 的五格）。 */
    VISITED_TABS,

    /** 只画当前这一页（桌面 / web）。 */
    CURRENT_ONLY,
}

/** 这一端要不要走「tab 常驻」那一档 —— 纯函数，好单测（壳的语义，不是端的事）。 */
fun ShellNavState.keepsVisitedTabPages(pageHost: ShellPageHost): Boolean =
    pageHost == ShellPageHost.VISITED_TABS && selected != null

/** 只画当前这一页时，壳交给 `page` 的那一个 [ShellPage] —— 纯函数，好单测。 */
fun ShellNavState.currentShellPage(): ShellPage = ShellPage(target, selected)
