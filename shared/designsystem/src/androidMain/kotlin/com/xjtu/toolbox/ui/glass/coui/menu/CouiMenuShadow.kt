package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.xjtu.toolbox.ui.glass.coui.CouiShadowDefaults
import com.xjtu.toolbox.ui.glass.coui.CouiShadowOutline
import com.xjtu.toolbox.ui.glass.coui.CouiShadowRenderer
import com.xjtu.toolbox.ui.glass.coui.CouiTouchMotion
import kotlin.math.min

/** drawing 挂未变换的外层 Box, surface 挂原面板 Column; 两者不能合并到同一 LayoutNode。 */
internal data class CouiMenuShadow(val drawing: Modifier, val surface: Modifier)

@Composable
internal fun rememberCouiMenuShadow(
    shape: Shape,
    motion: CouiMenuMotion,
    touchMotion: CouiTouchMotion,
    transitionProgress: (() -> Float)? = null,
    parentTouchMotion: CouiTouchMotion? = null,
    nodeAlpha: () -> Float = { motion.frame.alpha },
    outlineAlpha: () -> Float = { 1f },
    clipTop: Float = 0f,
    clipHeight: Float = Float.POSITIVE_INFINITY,
): CouiMenuShadow {
    val dark = LocalCouiMenuStyle.current.isDark
    val child = parentTouchMotion != null
    val renderer = remember { CouiShadowRenderer() }
    val density = LocalDensity.current
    val radius = with(density) { LocalCouiMenuStyle.current.resolvedRadius.toPx() }
    val style = CouiShadowDefaults.menu(dark)
    // 原厂资源先经 getDimensionPixelSize, 子面板 translationZ 则为 1 物理像素。
    val elevation = with(density) { style.elevation.roundToPx().toFloat() } + if (child) 1f else 0f
    val lightY = with(density) { style.lightY.roundToPx().toFloat() }
    val lightZ = with(density) { style.lightZ.roundToPx().toFloat() }
    val lightRadius = with(density) { style.lightRadius.roundToPx().toFloat() }
    val color = style.color.toArgb()
    var outer: LayoutCoordinates? by remember { mutableStateOf(null, neverEqualPolicy()) }
    var surface: LayoutCoordinates? by remember { mutableStateOf(null, neverEqualPolicy()) }
    val drawing = Modifier.onGloballyPositioned { outer = it }.drawWithContent {
        // 图层可独立更新; 显式订阅全部变换, 不等待下一次布局回调。
        val frame = motion.frame
        val scaleX = if (parentTouchMotion == null) frame.scale * touchMotion.scaleX.value else
            frame.scale * parentTouchMotion.scaleX.value + touchMotion.scaleX.value - 1f
        val scaleY = if (parentTouchMotion == null) frame.scale * touchMotion.scaleY.value else
            frame.scale * parentTouchMotion.scaleY.value + touchMotion.scaleY.value - 1f
        val translation = frame.translation + Offset(
            touchMotion.translationX.value + (parentTouchMotion?.translationX?.value ?: 0f),
            touchMotion.translationY.value + (parentTouchMotion?.translationY?.value ?: 0f),
        )
        transitionProgress?.invoke()
        val alpha = nodeAlpha().coerceIn(0f, 1f) * outlineAlpha().coerceIn(0f, 1f)
        val from = outer
        val target = surface
        val height = min(size.height, clipHeight).coerceAtLeast(0f)
        if (from != null && from.isAttached && target != null && target.isAttached &&
            scaleX > 0f && scaleY > 0f && translation.isFinite && size.width > 0f && height > 0f
        ) {
            val point = Offset(0f, clipTop)
            val origin = from.localPositionOf(target, point)
            val sx = (from.localPositionOf(target, point + Offset(size.width, 0f)).x - origin.x) / size.width
            val sy = (from.localPositionOf(target, point + Offset(0f, height)).y - origin.y) / height
            val window = target.localToWindow(point)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(origin.x, origin.y)
                renderer.draw(native, 0, size.width, height, sx, sy, window.x, window.y,
                    alpha, color, elevation, lightY - clipTop, lightZ, lightRadius,
                    CouiShadowOutline.RoundRect, radius, clipping = false)
                native.restore()
            }
        }
        drawContent()
    }
    return CouiMenuShadow(drawing, Modifier.onGloballyPositioned { surface = it })
}
