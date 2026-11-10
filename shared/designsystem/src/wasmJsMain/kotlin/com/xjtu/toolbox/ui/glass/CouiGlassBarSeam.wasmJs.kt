package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 非 Android 平台没有 AGSL 浮层材质：宿主拿不到采样源，底栏继续走 kyant 的玻璃实现。
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
    // 永不到达：rememberCouiGlassBarBackdrop() 在这一平台返回 null，调用方已回退 kyant 玻璃
}
