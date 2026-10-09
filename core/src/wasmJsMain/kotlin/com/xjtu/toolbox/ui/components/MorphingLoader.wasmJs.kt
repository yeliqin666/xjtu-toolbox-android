package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 浏览器（wasmJs）侧的降级：圆环旋转。
 *
 * `FallbackMorphingLoader` 在 commonMain 里，用 common 绘图原语写成，所以这里只是一行转发。
 */
@Composable
internal actual fun PlatformMorphingLoader(
    modifier: Modifier,
    size: Dp,
    color: Color,
    morphDurationMs: Int,
    pauseDurationMs: Int,
) {
    FallbackMorphingLoader(modifier = modifier, size = size, color = color)
}
