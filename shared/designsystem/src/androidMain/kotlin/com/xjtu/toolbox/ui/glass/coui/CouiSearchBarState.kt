package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.pow

/** 查询和展开目标由宿主控制；各动画独立持有通道，随创建 state 的组合释放。 */
@Stable
class CouiSearchBarState internal constructor(expanded: Boolean) {
    var expanded by mutableStateOf(expanded)
        private set
    private val width = CouiSpring(if (expanded) 1000f else 0f, .001f)
    private val cancel = CouiSpring(if (expanded) 1000f else 0f, .001f)
    private val page = CouiSpring(if (expanded) 1000f else 0f, .001f)
    private val results = CouiSpring(if (expanded) 1000f else 0f, .001f)
    internal val widthProgress: Float get() = width.value / 1000f
    internal val cancelProgress: Float get() = cancel.value / 1000f
    internal val pageProgress: Float get() = page.value / 1000f
    internal var running by mutableStateOf(false)
    val active: Boolean get() = expanded || running || widthProgress > 0f
    /** 原首页在进入时使用立方淡出，退出使用线性淡入。 */
    val contentAlpha: Float get() = (1f - pageProgress).coerceIn(0f, 1f).let { if (expanded) it.pow(3) else it }
    val resultsAlpha: Float get() = (results.value / 1000f).coerceIn(0f, 1f)

    internal fun updateTarget(target: Boolean) {
        if (expanded == target) return
        expanded = target
        running = true
        val end = if (target) 1000f else 0f
        // COUISearchBar 的宽度/取消路径运行中只修改目标，保留当前位置和速度。
        width.animateTo(end, .5f, .25f, onEnd = ::updateRunning)
        cancel.animateTo(end, .5f, .25f, onEnd = ::updateRunning)
        // 页面/结果保留共享弹簧适配；OEM setStartValue 的运行中语义及默认阈值尚缺实现材料。
        page.animateTo(end, .35f, onEnd = ::updateRunning)
        results.animateTo(end, if (target) .35f else .1f, onEnd = ::updateRunning)
    }

    internal fun dispose() {
        val end = if (expanded) 1000f else 0f
        width.snapTo(end)
        cancel.snapTo(end)
        page.snapTo(end)
        results.snapTo(end)
        running = false
    }

    private fun updateRunning() {
        running = width.isRunning || cancel.isRunning || page.isRunning || results.isRunning
    }
}

@Composable
fun rememberCouiSearchBarState(expanded: Boolean): CouiSearchBarState {
    val state = remember { CouiSearchBarState(expanded) }
    SideEffect { state.updateTarget(expanded) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
