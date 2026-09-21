package com.xjtu.toolbox.ui.components

/**
 * 下拉刷新四个阶段的提示文案，所有 miuix PullToRefresh 都传这一份。
 *
 * miuix 默认是英文（Pull down to refresh / Refreshing...），整个 App 都是中文，
 * 下拉时冒出一行英文很突兀。顺序固定：下拉中、可松手、刷新中、刷新完成。
 */
val AppRefreshTexts: List<String> = listOf(
    "下拉刷新",
    "松手刷新",
    "正在刷新…",
    "刷新完成",
)
