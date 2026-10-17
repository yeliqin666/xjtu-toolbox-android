package com.xjtu.toolbox.html.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HTML 内容排版与视觉样式规范
 */
@Immutable
data class HtmlStyle(
    val textColor: Color,
    val linkColor: Color,
    val codeBackgroundColor: Color,
    val codeTextColor: Color,
    val quoteBorderColor: Color,
    val quoteBackgroundColor: Color,
    val dividerColor: Color,
    val bodyStyle: TextStyle,
    val imageCornerRadius: Dp = 8.dp,
    val blockSpacing: Dp = 10.dp,
    val paragraphSpacing: Dp = 8.dp,
) {
    companion object {
        @Composable
        fun default(): HtmlStyle {
            val colors = MiuixTheme.colorScheme
            val textStyles = MiuixTheme.textStyles
            return HtmlStyle(
                textColor = colors.onSurface,
                linkColor = colors.primary,
                codeBackgroundColor = colors.onSurface.copy(alpha = 0.08f),
                codeTextColor = colors.onSurface,
                quoteBorderColor = colors.primary.copy(alpha = 0.7f),
                quoteBackgroundColor = colors.onSurface.copy(alpha = 0.04f),
                dividerColor = colors.dividerLine,
                bodyStyle = textStyles.body2,
                imageCornerRadius = 8.dp,
                blockSpacing = 10.dp,
                paragraphSpacing = 8.dp,
            )
        }
    }
}
