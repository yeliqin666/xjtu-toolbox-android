package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/**
 * 列表滚动位置的进程内记忆。
 *
 * 解决的是同一类反复出现的体验问题：从列表点进详情、看完返回，列表却回到了顶部。
 * 原因有两种，[rememberRetainedLazyListState] 对两种都有效：
 * - 页内切换（如思源学堂的课程列表 → 活动列表）：子页面 composition 被销毁重建；
 * - 跨路由返回（如教师主页 → 内置浏览器 → 返回）：整个目的地被销毁重建。
 *
 * `rememberSaveable` 在第二种情况下也救不了——目的地离开返回栈后
 * SaveableStateRegistry 里的记录随之清除，所以这里用进程内 map 兜底。
 *
 * 只活在本次进程内，不做持久化：冷启动回到顶部是符合预期的。
 */
object ScrollMemory {
    private val positions = mutableMapOf<String, Pair<Int, Int>>()

    fun get(key: String): Pair<Int, Int> = positions[key] ?: (0 to 0)

    fun put(key: String, index: Int, offset: Int) {
        positions[key] = index to offset
    }

    /** 数据源整体变了（如换了账号）时清掉，避免把新列表滚到无意义的位置 */
    fun clear(keyPrefix: String) {
        positions.keys.filter { it.startsWith(keyPrefix) }.forEach { positions.remove(it) }
    }
}

/**
 * 带记忆的 [LazyListState]。
 *
 * [key] 要能唯一标识"这一份列表"——同一个页面下不同数据集必须用不同 key，
 * 否则会把 A 列表的位置套到 B 列表上。例如活动列表应带上课程 id。
 */
@Composable
fun rememberRetainedLazyListState(key: String): LazyListState {
    val initial = remember(key) { ScrollMemory.get(key) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initial.first,
        initialFirstVisibleItemScrollOffset = initial.second,
    )
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> ScrollMemory.put(key, index, offset) }
    }
    return state
}
