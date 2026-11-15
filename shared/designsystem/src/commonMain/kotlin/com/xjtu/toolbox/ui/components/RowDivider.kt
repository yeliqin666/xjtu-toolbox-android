package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 卡片里相邻两行之间的细线：0.5dp、淡描边色。[startInset] 对齐到行的文字起点，不横穿行首图标。
 * 只画在两行之间，标题下面、最后一行后面都不画。
 */
@Composable
fun RowDivider(startInset: Dp = 16.dp, endInset: Dp = 0.dp) {
    Box(
        Modifier
            .padding(start = startInset, end = endInset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f)),
    )
}
