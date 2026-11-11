package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 桌面端没有 COUI 的 AGSL 渐变模糊：不提供页面采样源，顶栏走原来的 MIUIX 实现。
@Composable
actual fun rememberCouiPageBackdrop(): CouiPageBackdrop? = null

/**
 * 非 Android 的 [CouiTopBarScrollBehavior]：没有 COUI 折叠状态，包一层 MIUIX `ScrollBehavior`；
 * 回退的 MIUIX `TopAppBar` 再从里面取回原对象。
 */
private class MiuixCouiTopBarScrollBehavior(
    val scrollBehavior: ScrollBehavior,
) : CouiTopBarScrollBehavior {
    override val nestedScrollConnection: NestedScrollConnection get() = scrollBehavior.nestedScrollConnection
}

@Composable
actual fun rememberCouiTopAppBarScrollBehavior(): CouiTopBarScrollBehavior {
    val behavior = MiuixScrollBehavior(rememberTopAppBarState())
    return remember(behavior) { MiuixCouiTopBarScrollBehavior(behavior) }
}

/**
 * 非 Android 平台回退到 MIUIX 的顶栏，行为与改造前一致：
 * 大标题折叠仍交给 MIUIX 的 `TopAppBar`，钉死的 [pinned] 用小标题版。
 */
@Composable
actual fun CouiPageTopAppBar(
    title: String,
    largeTitle: String,
    modifier: Modifier,
    color: Color,
    scrollBehavior: CouiTopBarScrollBehavior?,
    pageBackdrop: CouiPageBackdrop?,
    subtitle: String?,
    titleColor: Color,
    pinned: Boolean,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    val resolvedColor = color.takeOrElse { MiuixTheme.colorScheme.surface }
    val miuixBehavior = (scrollBehavior as? MiuixCouiTopBarScrollBehavior)?.scrollBehavior
    if (pinned) {
        SmallTopAppBar(
            title = title.ifBlank { largeTitle },
            modifier = modifier,
            color = resolvedColor,
            subtitle = subtitle.orEmpty(),
            scrollBehavior = miuixBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
        return
    }
    TopAppBar(
        title = title,
        largeTitle = largeTitle,
        subtitle = subtitle.orEmpty(),
        color = resolvedColor,
        modifier = modifier,
        scrollBehavior = miuixBehavior,
        navigationIcon = navigationIcon,
        actions = actions,
        bottomContent = bottomContent,
    )
}
