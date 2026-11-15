package com.xjtu.toolbox.ui.glass.coui

import android.animation.ArgbEvaluator
import android.graphics.BlendMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** COUIChipTab 的 TYPE_DEFAULT 材质及库内按钮阴影；模糊半径使用像素，其余尺寸按 dp 换算。 */
object CouiChipTabDefaults {
    private val colorEvaluator = ArgbEvaluator()
    fun contentColor(dark: Boolean, enabled: Boolean = true): Color =
        Color(if (enabled) { if (dark) 0xE6FFFFFF else 0xE6000000 }
            else { if (dark) 0x4DFFFFFF else 0x42000000 })

    val textStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    @Composable
    internal fun themeTextStyle(): TextStyle = textStyle.copy(fontFamily = CouiTheme.typography.chipLabelFontFamily ?: textStyle.fontFamily)

    fun material(dark: Boolean, checkedFraction: Float): CouiFloatingMaterial {
        require(checkedFraction.isFinite()) { "选中进度必须为有限值" }
        val fraction = checkedFraction.coerceIn(0f, 1f)
        fun color(start: Long, end: Long): Color = Color(
            colorEvaluator.evaluate(fraction, Color(start).toArgb(), Color(end).toArgb()) as Int,
        )
        return CouiFloatingMaterial(
            firstColor = if (dark) color(0x33B2B2B2, 0xA6505050) else color(0x144E4E4E, 0x66A3A1A1),
            firstBlendMode = if (dark) BlendMode.LUMINOSITY else BlendMode.COLOR_DODGE,
            secondColor = if (dark) color(0x33303030, 0x4CA7A7A7) else color(0x14606060, 0x99F5F5F5),
            secondBlendMode = if (dark) BlendMode.OVERLAY else BlendMode.LUMINOSITY,
            blurRadius = 40f,
            strokeType = 1,
            edgeAlpha = (if (dark) 0.6f else 0.2f) * fraction,
            edgeWidthDp = 2f * fraction,
            // type=1 的原生前端以 0.5 为基值；这里还原 CAPSULE_2 的 fadeInAlpha。
            innerGlowAlpha = (if (dark) 0.04f else 0.2f) * fraction,
            innerGlowScale = fraction,
        )
    }

    fun shadow(dark: Boolean, density: Float, checkedFraction: Float, caustic: Boolean): CouiFloatingShadow {
        require(density.isFinite() && density > 0f) { "像素密度必须为有限正值" }
        require(checkedFraction.isFinite()) { "选中进度必须为有限值" }
        val fraction = checkedFraction.coerceIn(0f, 1f)
        val base = CouiTopAppBarButtonDefaults.shadow(dark, capsule = true, density = density, contour = caustic)
        return base.copy(
            elevation = base.elevation * fraction,
            causticColor = base.causticColor.copy(alpha = base.causticColor.alpha * fraction),
        )
    }
}
