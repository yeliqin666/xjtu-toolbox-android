package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState

/**
 * miuix 下拉刷新，带中文提示。[scrollBehavior] 是本页 COUI 顶栏的折叠行为：
 * 下拉要先把顶栏展开到底，展开完才算下拉。miuix 的 `PullToRefresh` 只认自己的 `ScrollBehavior`，
 * 所以这里用 [rememberMiuixBridge] 把 COUI 的折叠比例同步成一个 miuix 状态交给它。
 * [topPadding] 是指示器让出的顶栏高度。
 */
@Composable
fun AppPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: CouiTopBarScrollBehavior? = null,
    topPadding: Dp = 0.dp,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable () -> Unit,
) {
    val bridge = scrollBehavior?.let { rememberMiuixBridge(it) }
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        pullToRefreshState = state,
        contentPadding = PaddingValues(top = topPadding),
        topAppBarScrollBehavior = bridge,
        refreshTexts = RefreshTexts,
        content = content,
    )
}

/**
 * COUI 折叠比例 → miuix 的 `TopAppBarState`（miuix 语义：0 = 全展开，-1 = 全收起，比例 = offset / limit）。
 * 只同步状态，不接手任何滚动连接：滚动仍由 COUI 顶栏自己处理。
 */
@Composable
private fun rememberMiuixBridge(coui: CouiTopBarScrollBehavior): ScrollBehavior {
    val miuixState = remember { TopAppBarState(-1f, 0f, 0f) }
    val bridge = remember(miuixState) {
        object : ScrollBehavior {
            override val state: TopAppBarState get() = miuixState
            override val isPinned: Boolean get() = false
            override val snapAnimationSpec = spring<Float>()
            override val flingAnimationSpec = exponentialDecay<Float>()
            override val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {}
        }
    }
    LaunchedEffect(coui) {
        snapshotFlow { coui.collapsedFraction }.collect { miuixState.heightOffset = -it }
    }
    return bridge
}

// miuix 默认是英文；顺序固定：下拉中、可松手、刷新中、刷新完成
private val RefreshTexts = listOf("下拉刷新", "松手刷新", "正在刷新…", "都是最新的了")
