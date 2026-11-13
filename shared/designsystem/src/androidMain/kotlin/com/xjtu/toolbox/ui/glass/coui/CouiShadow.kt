package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/** 普通投影配置; 长度使用 dp, 不包含材质、背景采样或 caustic。 */
@Immutable
data class CouiShadowStyle(
    val elevation: Dp,
    val color: Color,
    val lightY: Dp,
    val lightZ: Dp,
    val lightRadius: Dp,
) {
    init {
        require(elevation.value.isFinite() && elevation.value >= 0f) { "投影高度必须是有限的非负值" }
        require(lightY.value.isFinite()) { "光源 Y 必须是有限值" }
        require(lightZ.value.isFinite() && lightZ > elevation) { "光源 Z 必须是有限值且大于投影高度" }
        require(lightRadius.value.isFinite() && lightRadius.value >= 0f) { "光源半径必须是有限的非负值" }
    }
}

object CouiShadowDefaults {
    /** 菜单 LV4 普通投影; 深浅色由宿主指定。 */
    fun menu(dark: Boolean = false): CouiShadowStyle = CouiShadowStyle(
        elevation = if (dark) 36.dp else 180.dp,
        color = Color(if (dark) 0x96000000 else 0x30000000),
        lightY = if (dark) (-1500).dp else (-200).dp,
        lightZ = 10000.dp,
        lightRadius = if (dark) 12000.dp else 2000.dp,
    )
}

/**
 * 按不透明主体在内容后绘制本地坐标投影, 不改变布局或裁剪内容。radius 为空时使用短边的一半。
 * 光源与高度按资源尺寸取整为像素; 半径保留小数。父级变换作用于内容和投影整体。
 * 需按窗口变换生成网格或控制节点透明度时, 使用 CouiShadowRenderer。
 * 返回的 Modifier 可复用；renderer 与缓存随各节点独立创建。
 */
@Composable
fun Modifier.couiShadow(
    style: CouiShadowStyle = CouiShadowDefaults.menu(),
    outline: CouiShadowOutline = CouiShadowOutline.RoundRect,
    radius: Dp? = null,
    clipping: Boolean = outline != CouiShadowOutline.RoundRect,
): Modifier {
    require(radius == null || (radius.value.isFinite() && radius.value >= 0f)) { "投影圆角必须是有限的非负值" }
    require(!clipping || outline != CouiShadowOutline.RoundRect) { "普通圆角投影尚未提供 inverse clip" }
    return then(ShadowElement(style, outline, radius, clipping))
}

private data class ShadowElement(
    val style: CouiShadowStyle,
    val outline: CouiShadowOutline,
    val radius: Dp?,
    val clipping: Boolean,
) : ModifierNodeElement<ShadowNode>() {
    override fun create() = ShadowNode(this)
    override fun update(node: ShadowNode) { node.parameters = this }
    override fun InspectorInfo.inspectableProperties() { name = "couiShadow" }
}

private class ShadowNode(var parameters: ShadowElement) : Modifier.Node(), DrawModifierNode {
    private val renderer = CouiShadowRenderer()

    override fun ContentDrawScope.draw() {
        val (style, outline, radius, clipping) = parameters
        drawIntoCanvas { canvas ->
            renderer.draw(
                canvas = canvas.nativeCanvas,
                slot = 0,
                width = size.width,
                height = size.height,
                sx = 1f,
                sy = 1f,
                windowX = 0f,
                windowY = 0f,
                alpha = 1f,
                color = style.color.toArgb(),
                elevation = style.elevation.roundToPx().toFloat(),
                lightY = style.lightY.roundToPx().toFloat(),
                lightZ = style.lightZ.roundToPx().toFloat(),
                lightRadius = style.lightRadius.roundToPx().toFloat(),
                outline = outline,
                radius = radius?.toPx() ?: (size.minDimension * 0.5f),
                clipping = clipping,
            )
        }
        drawContent()
    }
}
