package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 列表最前面插进新项（报错条、缓存提示、新数据）时，Lazy 列表默认按 key 守住原来的首项：
 * 人在顶上也会被往下挪一格，新项藏到顶栏底下。原本在顶上就留在顶上让新项露出来，已经往下翻了照旧守住正在看的那项。
 * [topKey] 标识最前面那几项，变了才检查；requestScrollToItem 和数据变化在同一次测量里生效，不会先跳再回。
 */
@Composable
fun KeepTopOnInsert(state: LazyListState, topKey: Any?) =
    KeepTop(topKey, { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }) { state.requestScrollToItem(0) }

@Composable
fun KeepTopOnInsert(state: LazyGridState, topKey: Any?) =
    KeepTop(topKey, { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }) { state.requestScrollToItem(0) }

@Composable
fun KeepTopOnInsert(state: LazyStaggeredGridState, topKey: Any?) =
    KeepTop(topKey, { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }) { state.requestScrollToItem(0) }

/** 位置只在 [topKey] 变化那一刻读，平时滚动不触发重组。 */
@Composable
private fun KeepTop(topKey: Any?, atTop: () -> Boolean, toTop: () -> Unit) {
    val shown = remember { arrayOf(topKey) }
    if (topKey != shown[0]) {
        if (atTop()) toTop()
        shown[0] = topKey
    }
}
