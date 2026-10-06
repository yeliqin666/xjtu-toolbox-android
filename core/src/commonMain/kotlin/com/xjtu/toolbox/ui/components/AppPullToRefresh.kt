package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState

/**
 * miuix 下拉刷新，带中文提示。[scrollBehavior] 传了就让顶栏先展开大标题、展开完才算下拉；
 * [topPadding] 是指示器让出的顶栏高度。
 */
@Composable
fun AppPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    topPadding: Dp = 0.dp,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable () -> Unit,
) {
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        pullToRefreshState = state,
        contentPadding = PaddingValues(top = topPadding),
        topAppBarScrollBehavior = scrollBehavior,
        refreshTexts = RefreshTexts,
        content = content,
    )
}

// miuix 默认是英文；顺序固定：下拉中、可松手、刷新中、刷新完成
private val RefreshTexts = listOf("下拉刷新", "松手刷新", "正在刷新…", "都是最新的了")
