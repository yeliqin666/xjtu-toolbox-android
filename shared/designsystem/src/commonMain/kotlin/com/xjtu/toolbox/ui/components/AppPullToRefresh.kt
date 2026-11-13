package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * 下拉要先把顶栏展开到底，展开完才算下拉。[topPadding] 是指示器让出的顶栏高度。
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
    ) {
        // 里面的列表据此把实际滚动位置报给顶栏（见 LocalCouiScrollReporter）
        androidx.compose.runtime.CompositionLocalProvider(
            com.xjtu.toolbox.ui.glass.LocalCouiScrollReporter provides scrollBehavior,
            content = content,
        )
    }
}

/**
 * miuix `PullToRefresh` 不读顶栏状态，只调用 behavior 的 `nestedScrollConnection`：
 * 下拉时先交给它（展开顶栏），剩下的才算下拉。所以把 COUI 的连接原样交给它，state 只是占位。
 */
@Composable
private fun rememberMiuixBridge(coui: CouiTopBarScrollBehavior): ScrollBehavior = remember(coui) {
    object : ScrollBehavior {
        override val state = TopAppBarState(-1f, 0f, 0f)
        override val isPinned: Boolean get() = false
        override val snapAnimationSpec = spring<Float>()
        override val flingAnimationSpec = exponentialDecay<Float>()
        override val nestedScrollConnection: NestedScrollConnection get() = coui.nestedScrollConnection
    }
}

// miuix 默认是英文；顺序固定：下拉中、可松手、刷新中、刷新完成
private val RefreshTexts = listOf("下拉刷新", "松手刷新", "正在刷新…", "都是最新的了")
