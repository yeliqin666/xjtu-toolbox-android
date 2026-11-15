package com.xjtu.toolbox.ui.glass.coui.menu

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import kotlin.math.ceil

@Composable
fun rememberCouiMenuBlur(backdrop: CouiBackdrop, background: Color): CouiMenuBlur =
    rememberCouiKawaseBlur(backdrop, background, CouiMenuBlurRadius)

/** 菜单固定 radius160; 弹窗使用原厂 integer/coui_dialog_blur_radius = 200。 */
private const val CouiMenuBlurRadius = 160

/** 原厂 BackgroundBlurDrawable 的 Fast Kawase 载体, radius 为引擎等级而不是像素。 */
@Composable
internal fun rememberCouiKawaseBlur(backdrop: CouiBackdrop, background: Color, radius: Int): CouiMenuBlur {
    val layers = List(10) { rememberGraphicsLayer() }
    return remember(backdrop, background, radius, layers) {
        CouiMenuBlur(backdrop, background, radius, layers)
    }
}

/** 移植 PJZ110 type2 的 Fast Kawase, radius 使用原厂引擎等级; 每轮保留独立像素网格。 */
class CouiMenuBlur internal constructor(
    private val backdrop: CouiBackdrop,
    private val background: Color,
    private val radius: Int,
    private val layers: List<GraphicsLayer>,
) {
    private val downsampleShader = RuntimeShader(MenuDownsampleShader)
    private val blurShaders = List(9) { RuntimeShader(MenuKawaseShader) }
    private var firstSize = IntSize.Zero
    private var recordedBlurSize = IntSize.Zero
    private var recordedDensity: Density? = null
    private var recordedLayoutDirection: LayoutDirection? = null

    init {
        layers.forEach {
            it.compositingStrategy = CompositingStrategy.Offscreen
            it.clip = true
        }
    }

    fun draw(scope: DrawScope, coordinates: LayoutCoordinates) = draw(scope, coordinates, 1f, Offset.Zero)

    /** coordinates 为未动画的锚点；变换与消费者的实际绘制使用同一帧数据。 */
    internal fun draw(scope: DrawScope, coordinates: LayoutCoordinates, sampleScale: Float, translation: Offset) {
        fun drawSource(target: DrawScope) {
            target.withTransform({
                scale(1f / sampleScale, 1f / sampleScale, Offset.Zero)
                translate(-translation.x, -translation.y)
            }) { backdrop.draw(this, coordinates) }
        }
        val menuSize = scope.size
        if (!coordinates.isAttached || menuSize.width <= 0f || menuSize.height <= 0f) return
        if (radius == 0) {
            scope.drawRect(background)
            drawSource(scope)
            return
        }
        val origin = coordinates.localToScreen(Offset.Zero)
        val right = coordinates.localToScreen(Offset(menuSize.width, 0f))
        val bottom = coordinates.localToScreen(Offset(0f, menuSize.height))
        val deviceWidth = (right - origin).getDistance() * sampleScale
        val deviceHeight = (bottom - origin).getDistance() * sampleScale
        if (!deviceWidth.isFinite() || !deviceHeight.isFinite() || deviceWidth <= 0f || deviceHeight <= 0f) {
            scope.drawRect(background)
            return
        }

        // native 以设备像素决定网格, 而不是 dp 或未变形的菜单尺寸。
        val blocksX = ceil(deviceWidth / 16f).toInt().coerceAtLeast(1)
        val blocksY = ceil(deviceHeight / 16f).toInt().coerceAtLeast(1)
        val captureSize = IntSize(blocksX * 8, blocksY * 8)
        val blurSize = IntSize(blocksX * 2, blocksY * 2)
        updateEffects(captureSize, blurSize)

        layers[0].record(scope, scope.layoutDirection, captureSize) {
            // 补底也进入采样链, 不能在材质混色之后再补不透明背景。
            drawRect(background)
            withTransform({
                scale(captureSize.width / menuSize.width, captureSize.height / menuSize.height, Offset.Zero)
            }) {
                drawSource(this)
            }
        }
        recordBlurPasses(scope, blurSize)
        scope.withTransform({
            // native 使用输入区域与实际中间图尺寸的比值, 不是固定乘八。
            scale(menuSize.width / blurSize.width, menuSize.height / blurSize.height, Offset.Zero)
        }) {
            drawLayer(layers[9])
        }
    }

    private fun recordBlurPasses(scope: DrawScope, size: IntSize) {
        val density = recordedDensity
        if (recordedBlurSize == size && density != null && density.density == scope.density &&
            density.fontScale == scope.fontScale && recordedLayoutDirection == scope.layoutDirection
        ) return
        // 固定指令只引用前驱层; RenderNode 继续跟踪动态内容, GraphicsLayer 负责恢复被回收的指令。
        // 保存 Density 值, 不让恢复回调持有不断重定向的 DrawScope。
        val recordingDensity = Density(scope.density, scope.fontScale)
        for (index in 1..9) {
            val previous = layers[index - 1]
            layers[index].record(recordingDensity, scope.layoutDirection, size) {
                drawLayer(previous)
            }
        }
        recordedBlurSize = size
        recordedDensity = recordingDensity
        recordedLayoutDirection = scope.layoutDirection
    }

    private fun updateEffects(captureSize: IntSize, blurSize: IntSize) {
        if (firstSize == captureSize) return
        firstSize = captureSize
        downsampleShader.setFloatUniform("inputSize", captureSize.width.toFloat(), captureSize.height.toFloat())
        layers[0].renderEffect = RenderEffect.createRuntimeShaderEffect(downsampleShader, "child")
            .asComposeRenderEffect()
        blurShaders.forEachIndexed { index, shader ->
            shader.setFloatUniform("inputSize", blurSize.width.toFloat(), blurSize.height.toFloat())
            // fastKawase 的 radius 分支先减十, p=10, 再执行 i=1..9; 160 对应原菜单取样。
            shader.setFloatUniform("in_blurOffset", (index + 1) * ((radius - 10f) / (9f * 10f)) / 8f)
            layers[index + 1].renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "child")
                .asComposeRenderEffect()
        }
    }
}

// librenderengineextimpl.so: 0x2fd60; sampleChild 承接原 child shader 的 0.25 局部矩阵与 Clamp。
private const val MenuDownsampleShader = """
    uniform shader child;
    uniform float2 inputSize;
    half offset = 0.25;

    half4 sampleChild(float2 xy) {
        return child.eval(clamp(xy * 4.0, float2(0.5), inputSize - 0.5));
    }

    half4 main(float2 xy) {
        half4 c = sampleChild(xy + float2(+offset, +offset));
             c += sampleChild(xy + float2(+offset, -offset));
             c += sampleChild(xy + float2(-offset, -offset));
             c += sampleChild(xy + float2(-offset, +offset));
        return half4(c.rgb * 0.25, 1.0);
    }
"""

// librenderengineextimpl.so: 0x33f4a; 只增加逻辑图像边界 Clamp, 不采样 RenderNode 的对齐余量。
private const val MenuKawaseShader = """
    uniform shader child;
    uniform float2 inputSize;
    uniform float in_blurOffset;

    half4 sampleChild(float2 xy) {
        return child.eval(clamp(xy, float2(0.5), inputSize - 0.5));
    }

    half4 main(float2 xy) {
        half4 c = sampleChild(xy);
        c += sampleChild(xy + float2(+in_blurOffset, +in_blurOffset));
        c += sampleChild(xy + float2(+in_blurOffset, -in_blurOffset));
        c += sampleChild(xy + float2(-in_blurOffset, -in_blurOffset));
        c += sampleChild(xy + float2(-in_blurOffset, +in_blurOffset));
        return half4(c.rgb * 0.2, 1.0);
    }
"""
