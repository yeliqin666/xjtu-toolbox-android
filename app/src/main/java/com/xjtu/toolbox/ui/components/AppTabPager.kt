package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.drop

/**
 * 和 [AppSegmentedTabs] 配套的内容区：左右滑动切栏，点标签时翻页器跟着动画过去。
 *
 * 标签行和内容区共用同一个 [selectedTabIndex]，由调用方持有——这样标签行、翻页器、
 * 页面里按栏懒加载的逻辑（「第一次翻到这一栏才去拉数据」）看的都是同一个数，
 * 不会出现手指滑过去了、标签还停在上一格的情况。
 *
 * 双向同步照搬日程页切周翻页器的写法（`ScheduleScreen` 的 `ScheduleTabContent`）：
 * - 选中项变了、用户又没在拖，就动画翻过去；
 * - 翻页停稳（`settledPage`）才回调，拖到一半不算。第一次发射是初始值，必须跳过，
 *   否则会把调用方刚设好的选中项又覆盖回去。
 *
 * **哪些页面不该用它**：标签是筛选器（校区、楼层、难度）而不是页面的，以及页面里
 * 自己有横向拖动（棋盘、座位图）的——见 plan2 §2.3。日程页的横滑留给切周，也不用。
 *
 * @param swipeEnabled 关掉以后只能点标签切栏，翻页器不响应横滑。
 */
@Composable
fun AppTabPager(
    pageCount: Int,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    content: @Composable (page: Int) -> Unit,
) {
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = selectedTabIndex.coerceIn(0, lastPage),
        pageCount = { pageCount },
    )
    // 回调可能每次重组都是新的 lambda，用 rememberUpdatedState 让下面那个长期收集的协程
    // 始终调到最新的一个，又不必因为它变了就重启收集。
    val onSelected by rememberUpdatedState(onTabSelected)

    // 选中项 → 翻页器
    LaunchedEffect(selectedTabIndex, pageCount) {
        val target = selectedTabIndex.coerceIn(0, lastPage)
        if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(target)
        }
    }

    // 翻页器 → 选中项
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { page -> onSelected(page) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = swipeEnabled,
        // 按下标做 key：各栏的 rememberSaveable（列表滚动位置等）跟着页走，切走再切回来还在。
        key = { it },
    ) { page ->
        content(page)
    }
}
