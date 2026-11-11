package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.ui.glass.coui.CouiTopAppBarButton
import com.xjtu.toolbox.ui.glass.coui.CouiTopAppBarSurface
import com.xjtu.toolbox.ui.glass.coui.LocalCouiDarkTheme
import com.xjtu.toolbox.ui.glass.coui.LocalCouiToolbarBackdrop
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * 首页自定义顶栏（头像 + 三颗按钮）的 COUI 渐变模糊底。
 *
 * 语义和 COUI `TopAppBarSurface` 一样：把「模糊 + 渐隐色」录一层，按钮通过
 * `LocalCouiToolbarBackdrop` 采它。与 COUI 的区别只在内容槽——首页那排不是 COUI 的 AppBar
 * 版式，所以在外面单独包一层，暂时不换组件本身。
 *
 * [pageBackdrop] 为 null（非 Android / 没有采样源）时退回实色底。
 */
@Composable
actual fun CouiHomeTopBarSurface(
    pageBackdrop: CouiPageBackdrop?,
    gradientColor: Color,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = (pageBackdrop as? AndroidCouiPageBackdrop)?.backdrop
    if (backdrop == null) {
        // 经典风格：没有页面采样源。按钮仍然需要一颗 `LocalCouiToolbarBackdrop`
        // （COUI 原版 `TopAppBarButton` 会 checkNotNull），这里录一层实色当采样源：
        // 材质照原版画，只是采到的是这层底色而不是背后的画面。
        val solidBackdrop = rememberCouiBackdrop()
        Box(modifier.fillMaxSize().couiBackdrop(solidBackdrop).background(gradientColor)) {
            CompositionLocalProvider(LocalCouiToolbarBackdrop provides solidBackdrop, content = content)
        }
        return
    }
    CompositionLocalProvider(LocalCouiDarkTheme provides LocalIsDarkTheme.current) {
        CouiTopAppBarSurface(
            modifier = modifier,
            backdrop = backdrop,
            gradientColor = gradientColor,
            dark = LocalIsDarkTheme.current,
            content = content,
        )
    }
}

/**
 * 各页顶栏动作按钮的 COUI 材质入口。
 *
 * 位于 [CouiHomeTopBarSurface] / `CouiTopAppBarSurface` 作用域内时，采样源
 * `LocalCouiToolbarBackdrop` 有值，走 COUI 原版 `TopAppBarButton`：AGSL 材质、边缘光、
 * 内光晕、跟手、按压全部来自原厂，[enabled] 也照原版传下去。
 *
 * 没有采样源时不抛错，退回半透明圆钮（[CouiToolbarActionButtonFallback]）：
 * 经典风格、仍是 MIUIX 旧顶栏的页面、以及未来漏了顶栏表面的地方都用它兜底。
 */
@Composable
actual fun CouiToolbarActionButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalCouiToolbarBackdrop.current == null) {
        CouiToolbarActionButtonFallback(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            content = content,
        )
        return
    }
    CompositionLocalProvider(LocalCouiDarkTheme provides LocalIsDarkTheme.current) {
        CouiTopAppBarButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            content = content,
        )
    }
}
