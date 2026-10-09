package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** 非 Android：没有「物理屏幕短边」这个概念，用窗口短边近似（见 commonMain 的说明）。 */
@Composable
actual fun smallestScreenWidthDp(): Int {
    val info = LocalWindowInfo.current
    return with(LocalDensity.current) {
        val w = info.containerSize.width.toDp().value
        val h = info.containerSize.height.toDp().value
        minOf(w, h).toInt()
    }
}
