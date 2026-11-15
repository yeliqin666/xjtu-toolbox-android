package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.coui.CouiCheckboxColors
import com.xjtu.toolbox.ui.glass.coui.CouiCheckboxDefaults
import com.xjtu.toolbox.ui.glass.coui.SpotlightDiagnostic
import com.xjtu.toolbox.ui.glass.coui.CouiShape
import com.xjtu.toolbox.ui.glass.coui.resolveCouiCornerRadius
import com.xjtu.toolbox.ui.glass.coui.CouiTheme

data class CouiMenuColors(
    val surface: Color,
    val container: Color,
    val primary: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outlineVariant: Color,
)

object CouiMenuDefaults {
    const val spotlightDiffuse: Float = 0.5f
    val radius = 28.dp
    val shape = CouiShape(radius)
    val itemShape = CouiShape(radius)
    internal const val subMenuMergeThreshold = 0.2f
    internal val subMenuEdgeFadeWidth = 4.dp
    internal val subMenuEdgeFadeBlurRadius = 2.dp

    @Composable
    fun style(dark: Boolean = CouiTheme.isDark): CouiMenuStyle {
        val colors = CouiTheme.colorSchemeFor(dark)
        return CouiMenuStyle(
            colors = CouiMenuColors(colors.menuSurface, colors.container, colors.accentOnSurface,
                colors.onSurface, colors.onSurfaceVariant, colors.outlineVariant),
            shape = shape, itemShape = itemShape,
            checkboxShape = CouiCheckboxDefaults.shape, checkboxBorderShape = CouiCheckboxDefaults.borderShape,
            checkboxColors = CouiCheckboxDefaults.colors(colors.menuCheckboxAccent, dark),
            itemTextStyle = CouiTheme.typography.menuItem, headerTextStyle = CouiTheme.typography.label,
            themeTint = CouiTheme.materialTintFor(dark),
            dark = dark,
        )
    }
}

/** 仅描述宿主外观。CouiShape 自动提供半径；其他 Shape 须显式提供 radius。 */
data class CouiMenuStyle(
    val colors: CouiMenuColors,
    val shape: Shape,
    val itemShape: Shape,
    val checkboxShape: Shape,
    val checkboxBorderShape: Shape,
    val checkboxColors: CouiCheckboxColors,
    val radius: Dp = Dp.Unspecified,
    val itemTextStyle: TextStyle,
    val headerTextStyle: TextStyle,
    val themeTint: Color = Color.Transparent,
    val spotlightDiffuse: Float? = null,
    val dark: Boolean? = null,
)

internal val CouiMenuStyle.resolvedRadius: Dp
    get() = shape.resolveCouiCornerRadius(radius)

/** 旧完整 Style 未携带模式时保留原判定；自动主题必须提供实际模式。 */
internal val CouiMenuStyle.isDark: Boolean
    get() = dark ?: (colors.surface.luminance() < 0.5f)

internal val LocalCouiMenuStyle = staticCompositionLocalOf<CouiMenuStyle> {
    error("菜单样式必须由 Menu 提供")
}
internal val LocalCouiMenuDiagnostic = staticCompositionLocalOf<((SpotlightDiagnostic) -> Unit)?> { null }
