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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.CouiCornerWeight
import com.xjtu.toolbox.ui.glass.coui.CouiShape
import kotlin.math.ceil

/** 材质只处理背景, 菜单文字和状态层保持清晰。 */
internal data class CouiMenuMaterial(
    val drawing: Modifier = Modifier,
    val input: Modifier = Modifier,
    val source: CouiMenuMaterialSource? = null,
)

/** 只复用面板材质，不能把父行文字或交互状态录入子面板。 */
internal class CouiMenuMaterialSource(private val layer: GraphicsLayer, private val shape: Shape) {
    private val matrix = Matrix()
    private val path = Path()
    var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    fun draw(scope: DrawScope, target: LayoutCoordinates) {
        val source = coordinates ?: return
        if (!source.isAttached || !target.isAttached || layer.size == IntSize.Zero) return
        matrix.reset()
        target.transformFrom(source, matrix)
        path.reset()
        path.addOutline(shape.createOutline(source.size.toSize(), scope.layoutDirection, scope))
        scope.withTransform({ transform(matrix) }) {
            clipPath(path) { drawLayer(layer) }
        }
    }
}

@Composable
internal fun couiMenuMaterial(
    backdrop: CouiBackdrop,
    shape: Shape,
    interactive: Boolean,
    motion: CouiMenuMotion,
    touchMotion: CouiTouchMotion,
    themeTintEnabled: Boolean,
    inputTarget: ((Offset, Boolean) -> LayoutCoordinates?)? = null,
    transitionProgress: (() -> Float)? = null,
    parentTouchMotion: CouiTouchMotion? = null,
    collapseBlend: (() -> Float)? = null,
    mergeSource: CouiMenuMaterialSource? = null,
): CouiMenuMaterial {
    // 材质组合覆盖进入、退出和反向动画, 不按 expanded 提前释放背景。
    backdrop.RetainWhileComposed()
    val surface = LocalCouiMenuStyle.current.colors.surface
    val dark = LocalCouiMenuStyle.current.isDark
    // 固定使用 COUI RoundFrameLayout 的普通材质模式 4, 不按 HDR 状态切换。
    val mix = if (dark) Color(0x99262626) else Color(0x99CDCDCD)
    val blend = if (dark) Color(0x99262626) else Color(0xBFEEEEEE)
    val themeTint = LocalCouiMenuStyle.current.themeTint
    val blurredBackdrop = rememberCouiMenuBlur(backdrop, surface)
    val backgroundLayer = rememberGraphicsLayer()
    val source = remember(backgroundLayer, shape) { CouiMenuMaterialSource(backgroundLayer, shape) }
    val density = LocalDensity.current
    val mergeLayer = if (mergeSource != null) rememberGraphicsLayer() else null
    val edgeFadeLayer = if (collapseBlend != null) rememberGraphicsLayer() else null
    val edgeFadeWidth = CouiMenuDefaults.subMenuEdgeFadeWidth.value * density.density
    val edgeFadeRadius = CouiMenuDefaults.subMenuEdgeFadeBlurRadius.value * density.density
    val edgeFadePadding = ceil(edgeFadeWidth + 3f * edgeFadeRadius).toInt()
    if (edgeFadeLayer != null) {
        edgeFadeLayer.topLeft = IntOffset(-edgeFadePadding, -edgeFadePadding)
        edgeFadeLayer.blendMode = BlendMode.DstOut
        edgeFadeLayer.renderEffect = remember(edgeFadeRadius) {
            BlurEffect(null, edgeFadeRadius, edgeTreatment = TileMode.Decal)
        }
    }
    val edgeRadiusPx = LocalCouiMenuStyle.current.resolvedRadius.value * density.density
    val edgeWeight = (LocalCouiMenuStyle.current.shape as? CouiShape)?.cornerWeight ?: CouiCornerWeight
    val edge = rememberCouiMenuEdge(dark, edgeRadiusPx, edgeWeight, mix, blend)
    backgroundLayer.clip = true
    val (spotlightInput, drawSpotlight) = couiMenuSpotlight(interactive, dark, inputTarget)
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null, neverEqualPolicy()) }
    val drawing = Modifier
        .graphicsLayer {
            this.shape = shape
            clip = true
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .onGloballyPositioned {
            coordinates = it
            source.coordinates = it
        }
        .drawWithContent {
            transitionProgress?.invoke()
            val blendProgress = collapseBlend?.invoke()?.coerceIn(0f, 1f) ?: 0f
            // 订阅外层变换, graphicsLayer 单独更新时也重新映射背景, 避免采样跟着菜单移动。
            val frame = motion.frame
            val scaleX = if (parentTouchMotion == null) frame.scale * touchMotion.scaleX.value else
                frame.scale * parentTouchMotion.scaleX.value + touchMotion.scaleX.value - 1f
            val scaleY = if (parentTouchMotion == null) frame.scale * touchMotion.scaleY.value else
                frame.scale * parentTouchMotion.scaleY.value + touchMotion.scaleY.value - 1f
            val translation = frame.translation + Offset(
                touchMotion.translationX.value + (parentTouchMotion?.translationX?.value ?: 0f),
                touchMotion.translationY.value + (parentTouchMotion?.translationY?.value ?: 0f),
            )
            val target = coordinates
            drawRect(surface)
            if (target != null && target.isAttached && size.width > 0f && size.height > 0f &&
                scaleX > 0f && scaleY > 0f && translation.isFinite
            ) {
                backgroundLayer.renderEffect = edge.renderEffect(size, density.density)
                // 背景录制不调用 drawContent, 使用独立 Density 避免作用域密度自引用。
                backgroundLayer.record(density, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                    blurredBackdrop.draw(this, target)
                }
                drawLayer(backgroundLayer)
                if (mergeLayer != null && mergeSource != null && blendProgress > 0f) {
                    mergeLayer.record(density, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                        mergeSource.draw(this, target)
                    }
                    // 子底色仍不透明；尾段用父面板同位置的材质替换，避免移除时整块变色。
                    mergeLayer.alpha = blendProgress
                    drawLayer(mergeLayer)
                }
            }
            if (themeTintEnabled) {
                // 只引入页面色相, 保留材质亮度, 不让前景参与混色。
                drawRect(themeTint, blendMode = BlendMode.Color)
            }
            if (edgeFadeLayer != null && blendProgress > 0f) {
                val outline = shape.createOutline(size, layoutDirection, density)
                edgeFadeLayer.record(
                    density = density,
                    layoutDirection = layoutDirection,
                    size = IntSize(size.width.toInt() + edgeFadePadding * 2, size.height.toInt() + edgeFadePadding * 2),
                ) {
                    translate(edgeFadePadding.toFloat(), edgeFadePadding.toFloat()) {
                        drawOutline(outline, Color.Black, style = Stroke(edgeFadeWidth * 2f))
                    }
                }
                edgeFadeLayer.alpha = blendProgress
                // 只擦除当前离屏背景的轮廓窄带；文字、图标和跟手光在遮罩之后绘制。
                drawLayer(edgeFadeLayer)
            }
            drawContent()
        }
        // 跟手光仍沿用 COUI 菜单的透明前景合成, 不进入通知轮廓的背景混色链。
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithContent {
            drawSpotlight?.invoke(this)
            drawContent()
        }
    return CouiMenuMaterial(drawing, spotlightInput, source)
}
