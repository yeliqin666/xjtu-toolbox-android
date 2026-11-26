package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 当前页顶栏的折叠行为，页内列表把**实际滚动位置**报给它（顶栏的按钮轮廓、折叠状态都按这个来）。
 * `AppPullToRefresh`、社区外壳这类包着列表的容器会提供；共用列表（AdaptiveCardGrid 等）自动读它。
 */
val LocalCouiScrollReporter = staticCompositionLocalOf<CouiTopBarScrollBehavior?> { null }

/**
 * 这一屏列表是不是当前看得见的那一页。横滑分栏里相邻页也在组合里，只有选中那页能报位置，
 * 否则停在顶部的邻页会把正在看的那页盖掉。`AppTabPager` 按页提供。
 */
val LocalScrollReportActive = compositionLocalOf { true }

/** 正文滚过这么多才算压到顶栏下面。 */
private val ScrolledUnderThreshold = 24.dp

/** 把 [scrolled]（正文是否已压到顶栏下面）报给 [behavior]，值变了才报。 */
@Composable
fun ReportScrollPosition(
    scrolled: () -> Boolean,
    behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current,
) {
    if (behavior == null) return
    val active = LocalScrollReportActive.current
    val latest by rememberUpdatedState(scrolled)
    LaunchedEffect(behavior, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { latest() }.distinctUntilChanged().collect { behavior.reportScrolled(it) }
    }
    // 这一屏不再是看得见的那页（切到别的分栏、换成加载中 / 空白）：先把顶栏摆回「在顶部」。
    // onDispose 在组合提交时同步执行，早于接手那一页的上报协程，所以接手的列表报的位置一定后到、以它为准；
    // 接手的是没有列表的状态（加载中、空白、没接上报的页）时，顶栏就停在顶部，不会留着上一页的轮廓。
    if (active) {
        DisposableEffect(behavior) {
            onDispose { behavior.reportScrolled(false) }
        }
    }
}

@Composable
fun ReportScrollPosition(state: LazyListState, behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current) {
    val px = with(LocalDensity.current) { ScrolledUnderThreshold.toPx() }
    ReportScrollPosition({ state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > px }, behavior)
}

@Composable
fun ReportScrollPosition(state: LazyGridState, behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current) {
    val px = with(LocalDensity.current) { ScrolledUnderThreshold.toPx() }
    ReportScrollPosition({ state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > px }, behavior)
}

@Composable
fun ReportScrollPosition(state: LazyStaggeredGridState, behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current) {
    val px = with(LocalDensity.current) { ScrolledUnderThreshold.toPx() }
    ReportScrollPosition({ state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > px }, behavior)
}

@Composable
fun ReportScrollPosition(state: ScrollState, behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current) {
    val px = with(LocalDensity.current) { ScrolledUnderThreshold.toPx() }
    ReportScrollPosition({ state.value > px }, behavior)
}

/** 页面正文用的滚动状态，位置顺手报给顶栏 [behavior]；等价于 `rememberScrollState()` + [ReportScrollPosition]。 */
@Composable
fun rememberReportedScrollState(behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current): ScrollState =
    androidx.compose.foundation.rememberScrollState().also { ReportScrollPosition(it, behavior) }

/** 同 [rememberReportedScrollState]，给 `LazyColumn` 用。 */
@Composable
fun rememberReportedLazyListState(behavior: CouiTopBarScrollBehavior? = LocalCouiScrollReporter.current): LazyListState =
    androidx.compose.foundation.lazy.rememberLazyListState().also { ReportScrollPosition(it, behavior) }

/**
 * 把 `verticalScroll` 的滚动量转给折叠顶栏，**只在内容真的能滚时才转**。
 *
 * 内容不足一屏时 [ScrollState.maxValue] 是 0，此时上拉/下拉只是回弹；
 * 直接把 `nestedScrollConnection` 挂上去，大标题会跟着回弹缩起来——页面上明明滚不动。
 */
@Composable
fun Modifier.reportScrollToTopBar(
    state: ScrollState,
    behavior: CouiTopBarScrollBehavior?,
): Modifier {
    if (behavior == null) return this
    val connection = remember(state, behavior) {
        val inner = behavior.nestedScrollConnection
        object : NestedScrollConnection {
            private val scrollable: Boolean get() = state.maxValue > 0

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (scrollable) inner.onPreScroll(available, source) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (scrollable) inner.onPostScroll(consumed, available, source) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (scrollable) inner.onPreFling(available) else Velocity.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (scrollable) inner.onPostFling(consumed, available) else Velocity.Zero
        }
    }
    return this.nestedScroll(connection)
}
