package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 非 Android 平台没有 AGSL 浮层材质：玻璃底栏画不出来，`glassNavStyle` 据此让壳走经典底栏。
actual fun couiGlassBarPlatformSupported(): Boolean = false

@Composable
actual fun rememberCouiGlassBarBackdrop(): CouiGlassBarBackdrop? = null

@Composable
actual fun CouiGlassBottomBar(
    backdrop: CouiGlassBarBackdrop,
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    // 永不到达：rememberCouiGlassBarBackdrop() 在这一平台返回 null，`GlassBottomTabs` 不会调到这里
}
