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

/**
 * 顶栏按钮玻璃（材质、投影、边缘光）的不透明度，绘制时读取；图标不受影响。
 * 页面顶栏在正文没滚到下面时给 0：按钮只剩图标、融进标题栏，滚到下面再和顶栏毛玻璃一起淡入。
 */
internal val LocalCouiToolbarSurfaceAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/** 沿用 ToolbarMaterialEffectDelegate 的光效几何；浅色材质改成白磨砂、投影加重（见 [CouiTopAppBarButtonDefaults.material]）。 */
object CouiTopAppBarButtonDefaults {
    val circleShape = CouiShape(999.dp, cornerWeight = 2f)

    @Composable
    fun style(dark: Boolean = CouiTheme.isDark, search: Boolean = false): CouiToolbarStyle {
        val density = LocalDensity.current.density
        val contour = LocalCouiContourGlowEnabled.current
        val colors = CouiTheme.colorSchemeFor(dark)
        return CouiToolbarStyle(dark, circleShape, CouiCapsuleShape,
            material = { capsule -> material(dark, capsule, search, blurRadius = selfBlur.value * density) },
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

    /**
     * 按钮自身的模糊（原版为 0，只给顶栏背景层重新上色）。顶栏背景的渐进模糊越往下越弱，
     * 按钮叠一层自己的模糊，透出来的内容才不会发清。
     */
    val selfBlur = 12.dp

    /**
     * 浅色：偏白的透明磨砂——轻提亮（dodge）后盖一层约六成的白，而不是原版那层 LUMINOSITY 灰
     * （它把底下的颜色压成灰，按钮看着发脏）。边缘光加强一点，配合投影出一圈质感。深色照原版。
     */
    fun material(dark: Boolean, capsule: Boolean, search: Boolean = false, blurRadius: Float = 0f): CouiFloatingMaterial = CouiFloatingMaterial(
        firstColor = Color(if (dark) 0x80666666 else 0x40808080),
        firstBlendMode = if (dark) BlendMode.OVERLAY else BlendMode.COLOR_DODGE,
        secondColor = Color(if (dark) 0xBF262626 else 0x9EFFFFFF),
        secondBlendMode = if (dark) BlendMode.LUMINOSITY else BlendMode.SRC_OVER,
        blurRadius = blurRadius,
        strokeType = if (capsule) 1 else 2,
        edgeAlpha = if (dark) 0.6f else 0.45f,
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
        // 浅色的白磨砂按钮靠一圈投影托出来，比原版重一些
        alphaScale: Float = if (search) 1f else if (dark) 0.75f else 1.3f,
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
