package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Web 端没有 AGSL：面板退回纯白 / 纯黑实底（与经典风格同一种观感）。 */
@Composable
actual fun CouiGlassPanel(
    pageBackdrop: CouiPageBackdrop?,
    modifier: Modifier,
    cornerRadius: Dp,
    tint: Color,
    fallbackColor: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    CouiGlassPanelFallback(modifier, cornerRadius, fallbackColor, content)
}
