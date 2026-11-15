package com.xjtu.toolbox.ui.glass.coui

import android.graphics.BlendMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingMaterial
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingShadow
import com.xjtu.toolbox.ui.glass.coui.LocalCouiContourGlowEnabled
import com.xjtu.toolbox.ui.glass.coui.CouiShape
import com.xjtu.toolbox.ui.glass.coui.CouiCapsuleShape
import com.xjtu.toolbox.ui.glass.coui.CouiTheme
import androidx.compose.ui.unit.dp

internal val LocalCouiToolbarContentColor = staticCompositionLocalOf { Color.Unspecified }

/** 沿用 ToolbarMaterialEffectDelegate 的光效几何及底栏混色方案, 略增强底色权重以改善可读性, 普通投影适当减弱。 */
object CouiTopAppBarButtonDefaults {
    val circleShape = CouiShape(999.dp, cornerWeight = 2f)

    @Composable
    fun style(dark: Boolean = CouiTheme.isDark, search: Boolean = false): CouiToolbarStyle {
        val density = LocalDensity.current.density
        val contour = LocalCouiContourGlowEnabled.current
        val colors = CouiTheme.colorSchemeFor(dark)
        return CouiToolbarStyle(dark, circleShape, CouiCapsuleShape,
            material = { capsule -> material(dark, capsule, search) },
            shadow = { capsule -> shadow(dark, capsule, density, contour, search) },
            contentColor = LocalCouiToolbarContentColor.current.takeOrElse { colors.onSurface },
            textStyle = CouiTheme.typography.body,
            cursorColor = colors.accent, contourGlowEnabled = contour)
    }

    /** ToolbarMaterialEffectResolver 的 TOP_BAR 与 CAPSULE_1/CIRCLE_1；不含项目原有加深特化。 */
    fun systemMaterial(dark: Boolean, capsule: Boolean): CouiFloatingMaterial = CouiFloatingMaterial(
        firstColor = Color(if (dark) 0xCC262626 else 0x66333333),
        firstBlendMode = if (dark) BlendMode.SRC_OVER else BlendMode.COLOR_DODGE,
        secondColor = Color(if (dark) 0xB37F7F7F else 0x99F5F5F5),
        secondBlendMode = if (dark) BlendMode.OVERLAY else BlendMode.LUMINOSITY,
        blurRadius = 0f, strokeType = if (capsule) 1 else 2,
        edgeAlpha = if (dark) .6f else .2f, edgeWidthDp = if (capsule) 2.2f else 1.9f,
        innerGlowAlpha = if (capsule) { if (dark) .01f else .1f } else { if (dark) .1f else .15f },
        innerGlowScale = if (capsule && !dark) 1.15f else 1f,
    )

    fun material(dark: Boolean, capsule: Boolean, search: Boolean = false): CouiFloatingMaterial = CouiFloatingMaterial(
        firstColor = Color(if (dark) 0x80666666 else 0xA3666666),
        firstBlendMode = if (dark) BlendMode.OVERLAY else BlendMode.COLOR_DODGE,
        secondColor = Color(if (dark) 0xBF262626 else 0x99F0F0F0),
        secondBlendMode = BlendMode.LUMINOSITY,
        blurRadius = 0f,
        strokeType = if (capsule) 1 else 2,
        edgeAlpha = if (dark) 0.6f else 0.2f,
        edgeWidthDp = if (capsule) 2.2f else 1.9f,
        innerGlowAlpha = (if (capsule) {
            if (dark) 0.01f else 0.1f
        } else {
            if (dark) 0.1f else 0.15f
        }) * if (search) 1f else 0.85f,
        innerGlowScale = if (capsule && !dark) 1.15f else 1f,
    )

    fun shadow(
        dark: Boolean,
        capsule: Boolean,
        density: Float,
        contour: Boolean,
        search: Boolean = false,
        alphaScale: Float = if (search) 1f else 0.75f,
    ): CouiFloatingShadow {
        fun pixelSize(dp: Float): Float {
            val px = dp * density
            return (if (px >= 0f) px + 0.5f else px - 0.5f).toInt().toFloat()
        }
        // selector 6 是 CAUSTIC_UPPER, selector 5 是 LV3_A, 不是 LV5。
        return CouiFloatingShadow(
            elevation = pixelSize(if (contour) 16f else 100f),
            color = Color.Black.copy(alpha = (if (contour) 30f else if (dark) 68f else 34f) / 255f * alphaScale),
            lightY = pixelSize(if (contour) -160f else if (dark) -240f else -200f),
            lightZ = if (contour) 0f else pixelSize(10000f),
            lightRadius = pixelSize(if (contour) 800f else 2000f),
            causticColor = Color(if (dark) 0x33FFFFFF else 0x2EFFFFFF),
            layout = Rect(0f, 0f, 0f, pixelSize(4.899993896f)),
            sdf = pixelSize(9.229980469f),
            smoothCorner = capsule || search,
        )
    }
}
