package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class CouiSearchBarColors(
    val foreground: Color,
    val secondary: Color,
    val clear: Color,
)


/** SettingsSearchBar 的 instant-search 分支。位置尺寸为 dp，额外动画留白保留原始 px。 */
object CouiSearchBarDefaults {
    @Composable
    fun colors(dark: Boolean = CouiTheme.isDark): CouiSearchBarColors {
        val theme = CouiTheme.colorSchemeFor(dark)
        return CouiSearchBarColors(theme.onSurface, theme.onSurfaceVariant, theme.onSurface.copy(alpha = if (dark) .3f else .26f))
    }

    val height = 52.dp
    val surfaceHeight = 40.dp
    val horizontalMargin = 16.dp
    val cancelGap = 12.dp
    val cancelPadding = 16.dp
    val cancelMaxWidth = 152.dp
    val innerIconSlot = 36.dp
    val iconSize = 20.dp
    val iconStart = 4.dp
    val textEnd = 4.dp
    val shape = CouiCapsuleShape
    internal const val cancelTranslationPx = 20f
    // TYPE_FRAMEWORK_TOP_BAR_BLUR；原始像素半径，不再乘 density。
    internal const val blurRadius = 20f
    internal const val cancelContentBlurRadius = 30f

}
