package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiNavigationItem
import com.xjtu.toolbox.ui.glass.coui.LocalCouiDarkTheme
import com.xjtu.toolbox.ui.glass.coui.NavigationBar
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

actual fun couiGlassBarPlatformSupported(): Boolean = true

private class AndroidCouiBackdrop(val backdrop: CouiBackdrop) : CouiGlassBarBackdrop {
    override fun recordInto(modifier: Modifier): Modifier = modifier.couiBackdrop(backdrop)
}

@Composable
actual fun rememberCouiGlassBarBackdrop(): CouiGlassBarBackdrop? =
    AndroidCouiBackdrop(rememberCouiBackdrop())

/** [CouiNavigationItem.icon] 不允许空；走了 [itemContent] 的格子不会读它，这里只是占位。 */
private val PlaceholderIcon: ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()

/**
 * COUI-Kit `NavigationBar` 原样上车：三主体融球渲染器、Skia 材质滤镜、网格投影、
 * 跟手形变与按压弹簧、24dp 内距 / 70dp 每格 / 8dp 间距 / selectableGroup / 三条松手规则。
 * 本 App 没有左右附件，不传 leftIcon/rightIcon —— 与 COUI 宿主不启用附件时同一条代码路径。
 */
@Composable
actual fun CouiGlassBottomBar(
    backdrop: CouiGlassBarBackdrop,
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    // COUI 的宿主接法：App 有独立深浅开关时，在同一作用域提供实际的 darkTheme
    CompositionLocalProvider(LocalCouiDarkTheme provides LocalIsDarkTheme.current) {
        NavigationBar(
            modifier = modifier,
            backdrop = (backdrop as AndroidCouiBackdrop).backdrop,
            items = tabs.map { CouiNavigationItem(it.title, it.icon ?: PlaceholderIcon) },
            selectedIndex = selectedIndex,
            itemContent = { index, selected -> tabs[index].content(this, selected) },
            onSelection = onTabSelected,
        )
    }
}
