package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** 浏览器（wasmJs）侧的降级：圆环旋转。 */
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
