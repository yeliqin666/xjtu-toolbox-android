package com.xjtu.toolbox.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingRenderer
import com.xjtu.toolbox.ui.glass.coui.LocalCouiToolbarBackdrop
import com.xjtu.toolbox.ui.glass.coui.SegmentedControl
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * Android 上就是 COUI 原版分段选择器：顶栏里采顶栏那层表面（透明模糊），
 * 卡片 / 弹层里没有采样源时走 COUI 的普通填色外观；renderer 建不起来也退回普通填色。
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
internal actual fun PlatformSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    small: Boolean,
    reselectable: Boolean,
) {
    val toolbar = LocalCouiToolbarBackdrop.current
    val renderer = remember(toolbar) {
        if (toolbar == null) null else runCatching { CouiFloatingRenderer.standalone() }.getOrNull()
    }
    SegmentedControl(
        modifier = modifier,
        labels = labels,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        backdrop = if (renderer != null) toolbar else null,
        dark = LocalIsDarkTheme.current,
        small = small,
        reselectable = reselectable,
        renderer = renderer,
    )
}
