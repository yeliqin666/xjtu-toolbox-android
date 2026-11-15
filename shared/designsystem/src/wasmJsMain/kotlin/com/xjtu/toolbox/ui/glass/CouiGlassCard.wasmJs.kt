package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Web 端没有 AGSL：有色玻璃卡片退回半透明色块。 */
@Composable
actual fun CouiGlassCard(
    color: Color,
    modifier: Modifier,
    cornerRadius: Dp,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    CouiGlassCardFallback(
        color = color,
        modifier = modifier,
        cornerRadius = cornerRadius,
        enabled = enabled,
        onClick = onClick,
        content = content,
    )
}
