package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Web 端没有 COUI AGSL：首页顶栏退回实色底。 */
@Composable
actual fun CouiHomeTopBarSurface(
    pageBackdrop: CouiPageBackdrop?,
    gradientColor: Color,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.background(gradientColor)) { content() }
}

/** Web 端没有 COUI AGSL：顶栏动作按钮退回半透明圆钮。 */
@Composable
actual fun CouiToolbarActionButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    CouiToolbarActionButtonFallback(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}
