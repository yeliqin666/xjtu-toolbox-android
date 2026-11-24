package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * 非 Android：没有「物理屏幕短边」，用窗口宽度。
 * 不能取宽高的较小值：横向的矮窗口（笔记本高缩放最大化后只有 ~560dp 高）会被误判成手机。
 */
@Composable
actual fun smallestScreenWidthDp(): Int {
    val info = LocalWindowInfo.current
    return with(LocalDensity.current) { info.containerSize.width.toDp().value.toInt() }
}
