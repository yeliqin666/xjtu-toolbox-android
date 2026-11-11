package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI

object CouiCollapsingAppBarDefaults {
    val titleHeight = 116.dp
    val titleOnlyHeight = 110.dp
    val collapsedTitleHeight = 52.dp
    val expandedTitleSize = 32.dp
    val collapsedTitleSize = 24.dp
    // shortcut: 独立标题使用便签紧凑布局边距；接入原厂宽屏布局时再按窗口类别切换。
    val titleOnlyStart = 16.dp
    val titleOnlyTop = 54.dp
    val titleStart = 24.dp
    val titleBottom = 18.dp
    val resultsStartOffset = 108.dp
    internal val titleOnlyBlurExtension = 8.dp
    internal val searchBlurExtension = 14.dp
    internal const val referenceBlurRadiusPx = 27f
    internal const val snapResponse = .25f
    internal val titleEasing = CubicBezierEasing(.33f, 0f, .67f, 1f)
}

/** 正向先折叠顶栏；反向在内容到顶后展开。与实际滚动列表共享同一个 nestedScrollConnection。 */
@Stable
class CouiCollapsingAppBarState internal constructor(rangePx: Float, initialFraction: Float,
    restoreFraction: Float = initialFraction, initialSearching: Boolean = false) {
    internal var rangePx by mutableFloatStateOf(rangePx)
    internal var offsetPx by mutableFloatStateOf((if (initialSearching) 1f else initialFraction).coerceIn(0f, 1f) * rangePx)
    internal var contentOffsetPx by mutableFloatStateOf(offsetPx)
        private set
    private var logicalOffset = offsetPx
    internal var searchLocked = false
    internal var canScroll: () -> Boolean = { true }
    private var snap: SpringAnimation? = null
    private var contentSnap: SpringAnimation? = null
    private var searching = initialSearching
    private var hasSearchBar = true
    private var expandedHeight = CouiCollapsingAppBarDefaults.titleOnlyHeight
    private var collapsedHeight = CouiCollapsingAppBarDefaults.collapsedTitleHeight
    private var restoreOffset = restoreFraction * rangePx
    val collapsedFraction: Float get() = (offsetPx / rangePx).coerceIn(0f, 1f)
    /** 像素；加到使用顶栏测量 padding 的内容层。列表跟随逻辑滚动，搜索栏跟随独立弹簧。 */
    val contentTranslationY: Float get() = offsetPx - contentOffsetPx

    /**
     * 用逻辑滚动位置修正内容的顶部留白；底部安全区及滚动视口保持不动。
     * 将结果用于滚动内容的 padding，并消费 Scaffold 原始 padding；不能再平移整个视口。
     */
    fun contentPadding(padding: PaddingValues, density: Density): PaddingValues = object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection) = padding.calculateLeftPadding(layoutDirection)
        override fun calculateRightPadding(layoutDirection: LayoutDirection) = padding.calculateRightPadding(layoutDirection)
        override fun calculateTopPadding() =
            (padding.calculateTopPadding() + with(density) { contentTranslationY.toDp() }).coerceAtLeast(0.dp)
        override fun calculateBottomPadding() = padding.calculateBottomPadding()
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (searchLocked || available.y >= 0f) return Offset.Zero
            return Offset(0f, consume(available.y))
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (searchLocked || available.y <= 0f) return Offset.Zero
            return Offset(0f, consume(available.y))
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!searchLocked) {
                if (hasSearchBar && !canScroll()) animateOffset(0f, animateContent = true)
                else if (logicalOffset > 0f && logicalOffset < rangePx) settle()
            }
            return Velocity.Zero
        }
    }

    private fun consume(delta: Float): Float {
        val previous = logicalOffset
        val target = (previous - delta).coerceIn(0f, rangePx)
        // ViewOffsetHelper 在 offsetTop 未变化时直接返回；内容继续滚动不能反复重定向顶栏。
        if (target == previous) return 0f
        // 原厂分别持有滚动目标与实际 spring offset，消费量不能由滞后的可见位置计算。
        animateOffset(target, if (delta < 0f) .2f else .1f)
        return previous - logicalOffset
    }

    private fun settle() {
        val target = if (logicalOffset < rangePx * .5f) 0f else rangePx
        animateOffset(target, animateContent = true)
    }

    private fun animateOffset(target: Float, response: Float = CouiCollapsingAppBarDefaults.snapResponse,
        animateContent: Boolean = false) {
        logicalOffset = target
        // CoordinatorScrollingViewAnimator：直接滚动时列表立即消费目标；吸附才单独动画。
        if (animateContent) {
            val child = contentSnap ?: SpringAnimation(FloatValueHolder(contentOffsetPx)).apply {
                spring = SpringForce(target).apply { dampingRatio = 1f }
                addUpdateListener { _, value, _ -> contentOffsetPx = (-Math.round(-value)).toFloat().coerceIn(0f, rangePx) }
            }.also { contentSnap = it }
            child.spring.stiffness = ((2 * PI / response).let { it * it }).toFloat()
            child.setStartValue(contentOffsetPx)
            child.animateToFinalPosition(target)
        } else {
            contentSnap?.cancel()
            // 内容改为直接跟随滚动后，下一次吸附不能继承旧 pending 目标或速度。
            contentSnap = null
            contentOffsetPx = target
        }
        val animation = snap ?: SpringAnimation(FloatValueHolder(offsetPx)).apply {
            spring = SpringForce(target).apply {
                dampingRatio = 1f
            }
            setMinimumVisibleChange(1f)
            addUpdateListener { _, value, _ -> offsetPx = (-Math.round(-value)).toFloat().coerceIn(0f, rangePx) }
        }.also { snap = it }
        animation.spring.stiffness = ((2 * PI / response).let { it * it }).toFloat()
        // COUIViewOffsetHelper 每次重定向都把可见的整数位置设回 start，保留运行速度。
        animation.setStartValue(offsetPx)
        animation.animateToFinalPosition(target)
    }

    internal fun updateSearch(expanded: Boolean, active: Boolean) {
        searchLocked = active
        if (searching == expanded) return
        searching = expanded
        if (expanded) restoreOffset = if (offsetPx >= rangePx) rangePx else 0f
        animateOffset(if (expanded) rangePx else restoreOffset, animateContent = true)
    }

    internal fun updateRange(density: Density, hasSearchBar: Boolean = this.hasSearchBar,
        expandedHeight: Dp = this.expandedHeight, collapsedHeight: Dp = this.collapsedHeight) {
        this.hasSearchBar = hasSearchBar
        this.expandedHeight = expandedHeight
        this.collapsedHeight = collapsedHeight
        val range = with(density) {
            if (hasSearchBar) CouiCollapsingAppBarDefaults.titleHeight.roundToPx().toFloat()
            else (expandedHeight.roundToPx() - collapsedHeight.roundToPx()).toFloat().coerceAtLeast(1f)
        }
        if (range == rangePx) return
        val fraction = logicalOffset / rangePx
        val restore = restoreOffset / rangePx
        dispose()
        rangePx = range
        // 配置重测后采用已接受的逻辑位置，不能保留已取消弹簧的中间坐标。
        offsetPx = fraction * range
        logicalOffset = offsetPx
        contentOffsetPx = offsetPx
        restoreOffset = restore * range
    }
    internal fun saveState() = listOf(logicalOffset / rangePx, restoreOffset / rangePx, if (searching) 1f else 0f)
    internal fun dispose() { snap?.cancel(); snap = null; contentSnap?.cancel(); contentSnap = null }
}

@Composable
fun rememberCouiCollapsingAppBarState(canScroll: () -> Boolean = { true }): CouiCollapsingAppBarState {
    val density = LocalDensity.current
    val range = with(density) { CouiCollapsingAppBarDefaults.titleHeight.roundToPx().toFloat() }
    val saver = listSaver<CouiCollapsingAppBarState, Float>(save = { it.saveState() },
        restore = { CouiCollapsingAppBarState(range, it[0], it[1], it[2] > 0f) })
    val state = rememberSaveable(saver = saver) { CouiCollapsingAppBarState(range, 0f) }
    SideEffect { state.updateRange(density); state.canScroll = canScroll }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
