package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.CompositingStrategy as ComposeCompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiBackdropEffectScope
import com.xjtu.toolbox.ui.glass.coui.DrawCouiBackdropNode
import kotlin.math.ceil

private const val BlurOffsetPx = 2f
private const val SigmaScale = 0.57735f

/** 仅模糊背景；长度为 px，各节点独立持有横纵图层，Shader 在首次绘制创建。 */
@Composable
fun Modifier.drawCouiGradientBackdrop(
    backdrop: CouiBackdrop,
    fadeHeightPx: Float,
    referenceBlurRadiusPx: Float,
    gradientSpeed: Float? = null,
): Modifier {
    require(gradientSpeed == null || gradientSpeed.isFinite() && gradientSpeed > 0f)
    return graphicsLayer {
        shape = RectangleShape
        clip = true
        compositingStrategy = ComposeCompositingStrategy.Offscreen
    }.then(GradientBackdropElement(backdrop, fadeHeightPx, referenceBlurRadiusPx, gradientSpeed))
}

private data class GradientBackdropElement(
    val backdrop: CouiBackdrop,
    val fadeHeightPx: Float,
    val referenceBlurRadiusPx: Float,
    val gradientSpeed: Float?,
) : ModifierNodeElement<GradientBackdropNode>() {
    override fun create() = GradientBackdropNode(backdrop, fadeHeightPx, referenceBlurRadiusPx, gradientSpeed)
    override fun update(node: GradientBackdropNode) {
        node.backdrop = backdrop
        node.update(fadeHeightPx, referenceBlurRadiusPx, gradientSpeed)
    }
    override fun InspectorInfo.inspectableProperties() { name = "drawCouiGradientBackdrop" }
}

private class GradientBackdropNode(
    backdrop: CouiBackdrop,
    private var fadeHeightPx: Float,
    private var referenceBlurRadiusPx: Float,
    private var gradientSpeed: Float?,
) : DrawCouiBackdropNode(backdrop) {
    private var passes = emptyList<GraphicsLayer>()
    private var renderer: CouiGradientRenderer? = null
    private var rendererDensity: Density? = null

    override fun onAttach() {
        super.onAttach()
        passes = List(2) { requireGraphicsContext().createGraphicsLayer() }
    }

    override fun onDetach() {
        passes.forEach { requireGraphicsContext().releaseGraphicsLayer(it) }
        passes = emptyList()
        renderer = null
        rendererDensity = null
        super.onDetach()
    }

    fun update(fadeHeightPx: Float, referenceBlurRadiusPx: Float, gradientSpeed: Float?) {
        if (this.fadeHeightPx != fadeHeightPx || this.referenceBlurRadiusPx != referenceBlurRadiusPx ||
            this.gradientSpeed != gradientSpeed) {
            this.fadeHeightPx = fadeHeightPx
            this.referenceBlurRadiusPx = referenceBlurRadiusPx
            this.gradientSpeed = gradientSpeed
            renderer = null
        }
    }

    override fun configureEffects(scope: CouiBackdropEffectScope) {
        val density = scope.recordingDensity
        val current = renderer?.takeIf { rendererDensity == density }
            ?: CouiGradientRenderer(fadeHeightPx, referenceBlurRadiusPx, gradientSpeed, density, passes).also {
                renderer = it
                rendererDensity = density
            }
        scope.padding = current.padding
        current.height = scope.size.height
    }

    override fun drawSource(scope: DrawScope, source: DrawScope.() -> Unit) {
        checkNotNull(renderer).draw(scope, source)
    }
}

/**
 * 原厂渐进 Gaussian 先横后纵。AGSL 路径在半分辨率上算：采样点仍是原尺寸每 2px 一个，
 * 只是取自 2×2 平均后的像素，像素数降到四分之一；最后一遍在半径很小处渐隐，
 * 露出下面原尺寸的清晰页面（采样源总是画在这一层正下方），不让放大回去的半分辨率糊在清晰端。
 */
private class CouiGradientRenderer(
    private val fadeHeightPx: Float,
    private val referenceBlurRadiusPx: Float,
    private val gradientSpeed: Float?,
    private val density: Density,
    private val passes: List<GraphicsLayer>,
) {
    // 将宿主的参考半径换算为 COUI 核: 原权重 sigma=radius/3, 采样间距为 2px。
    private val kernelRadius = ceil(
        (referenceBlurRadiusPx * SigmaScale + 0.5f) * 3f / BlurOffsetPx,
    ).toInt()
    // 两遍共用一份程序，每次建 effect 前重设全部 uniform（见 CouiShaderCache）
    private val shader by lazy { CouiShaderCache.get(gradientGaussianShader(kernelRadius, gradientSpeed)) }

    /** 中间图相对原尺寸的比例：低版本整块 BlurEffect 不降。 */
    private val resolution = if (couiEffectsSupported) 0.5f else 1f
    private var lastSize = IntSize.Zero
    private var lastHeight = Float.NaN
    private var recordedSize = IntSize.Zero
    private var recordedLayoutDirection: LayoutDirection? = null

    var height: Float = 0f

    // 覆盖最大采样偏移和线性插值的外沿, 不让边界 Clamp 进入可见区域。
    val padding: Float = kernelRadius * BlurOffsetPx + 1f

    init {
        passes.forEach {
            it.compositingStrategy = CompositingStrategy.Offscreen
            it.clip = true
        }
    }

    fun draw(scope: DrawScope, drawBackdrop: DrawScope.() -> Unit) {
        if (scope.size.width <= 0f || scope.size.height <= 0f) return
        val paddedSize = IntSize(
            ceil(scope.size.width * resolution).toInt(),
            ceil(scope.size.height * resolution).toInt(),
        )
        updateEffects(paddedSize)
        // 新 Canvas 不继承 CouiBackdrop 的 padding 平移; 独立 Density 避免 DrawScope 循环代理。
        passes[0].record(density, scope.layoutDirection, paddedSize) {
            scale(resolution, resolution, Offset.Zero) {
                translate(padding, padding) { drawBackdrop() }
            }
        }
        // 独立图层落实两次栅格化, 不让第二遍在 Shader 内嵌套执行第一遍卷积。
        // 第二遍指令只引用第一遍; 子层更新仍会传播, 不需要因背景滚动而重录。
        if (recordedSize != paddedSize || recordedLayoutDirection != scope.layoutDirection) {
            val horizontal = passes[0]
            passes[1].record(density, scope.layoutDirection, paddedSize) { drawLayer(horizontal) }
            recordedSize = paddedSize
            recordedLayoutDirection = scope.layoutDirection
        }
        scope.translate(-padding, -padding) {
            scale(1f / resolution, 1f / resolution, Offset.Zero) { drawLayer(passes[1]) }
        }
    }

    private fun updateEffects(size: IntSize) {
        if (lastSize == size && lastHeight == height) return
        if (!couiEffectsSupported) {
            // 低版本：整块均匀模糊，没有渐隐。
            passes[0].renderEffect = BlurEffect(referenceBlurRadiusPx, referenceBlurRadiusPx, TileMode.Decal)
            passes[1].renderEffect = null
            lastSize = size
            lastHeight = height
            return
        }
        val gradientHeight = if (gradientSpeed == null) fadeHeightPx else height
        // 中间图坐标 = 原尺寸 × resolution；采样间距仍对应原尺寸的 BlurOffsetPx
        val offset = BlurOffsetPx * resolution
        repeat(2) { index ->
            shader.setFloatUniform("in_blurOffset", if (index == 0) offset else 0f, if (index == 1) offset else 0f)
            shader.setFloatUniform("in_fadeOut", if (index == 1) 1f else 0f)
            shader.setFloatUniform("inputSize", size.width.toFloat(), size.height.toFloat())
            shader.setFloatUniform("gradientTop", (padding + height - gradientHeight) * resolution)
            shader.setFloatUniform("gradientHeight", gradientHeight * resolution)
            // RenderEffect 保存 uniform 快照; 仅几何改变时更新, 普通滚动复用。
            passes[index].renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "child")
                .asComposeRenderEffect()
        }
        lastSize = size
        lastHeight = height
    }
}

// libhwui.so: OplusGradientGaussianBlurEffect::make_blur_effect, 保留整数半径、权重和剩余中心权重。
// shortcut: 噪声种子固定, 不复刻原厂逐次随机的时间变化; 需要动态噪声时再接入渲染时钟。
private fun gradientGaussianShader(kernelRadius: Int, gradientSpeed: Float?): String = """
    const int in_blurRadius = $kernelRadius;
    const float PI = 3.1415926;
    uniform shader child;
    uniform float2 inputSize;
    uniform float gradientTop;
    uniform float gradientHeight;
    uniform half2 in_blurOffset;
    // 1 = 最后一遍：半径很小处渐隐，露出下面原尺寸的清晰页面
    uniform half in_fadeOut;

    float4 sampleChild(float2 coord) {
        return child.eval(clamp(coord, float2(0.5), inputSize - 0.5));
    }

    float interleavedGradientNoise(float2 coord) {
        const float3 magicNumbers = float3(0.06711056, 0.00583715, 52.9829189);
        return fract(magicNumbers.z * fract(dot(coord, magicNumbers.xy)));
    }

    half4 main(float2 coord) {
        float4 c = sampleChild(coord);
        float position = clamp((coord.y - gradientTop) / gradientHeight, 0.0, 1.0);
        // 通用入口保留 smoothstep；显式原厂分支使用 1-pow(1-ratio,speed)。
        float ratio = ${if (gradientSpeed == null) "smoothstep(0.0, 1.0, 1.0 - position)" else "1.0 - pow(position, ${gradientSpeed})"};
        float radiusF = float(in_blurRadius) * ratio;
        int radius = int(radiusF);
        float keep = in_fadeOut > 0.5 ? smoothstep(1.0, 4.0, radiusF) : 1.0;
        if (radius <= 1) return half4(c * keep);
        // 卷积结果恢复中心 alpha 后恒为零; 保留清晰端直通, 不截断低但非零的覆盖率。
        if (c.a == 0.0) return half4(0.0);
        float2 offset = float2(0.0);
        float sumWeight = 0.0;
        // 颜色累加与覆盖率归一保持 float，避免 half 舍入随整数半径形成横向色带。
        float4 finalColor = float4(0.0);
        // 原厂权重 N(0, radius/3)；逐项递推 w(i+1) = w(i)·e^{-(2i+1)/2σ²}，每像素只算三次 exp
        float sigma = float(radius) / 3.0;
        float twoSigma2 = 2.0 * sigma * sigma + 0.00000001;
        float w = exp(-1.0 / twoSigma2) / sqrt(2.0 * PI * sigma * sigma);
        float ratioStep = exp(-3.0 / twoSigma2);
        float ratioGrowth = exp(-2.0 / twoSigma2);
        for (int i = 1; i <= in_blurRadius; i++) {
            if (i > radius) break;
            float weight = clamp(w, 0.0, 1.0);
            offset.x = min(in_blurOffset.x, float(radius)) * float(i);
            offset.y = min(in_blurOffset.y, float(radius)) * float(i);
            finalColor += sampleChild(coord - offset) * weight;
            finalColor += sampleChild(coord + offset) * weight;
            sumWeight += 2.0 * weight;
            w *= ratioStep;
            ratioStep *= ratioGrowth;
        }
        finalColor += c * (1.0 - sumWeight);
        // 屏幕外没有页面采样; 恢复有效颜色但不扩张中心覆盖度, 避免顶边变暗或补成不透明。
        if (finalColor.a <= 0.0) return half4(0.0);
        finalColor = float4(finalColor.rgb * (c.a / finalColor.a), c.a);
        float noise = interleavedGradientNoise(coord + 0.5) - 0.5;
        finalColor.rgb = clamp(finalColor.rgb + noise * c.a / 200.0, float3(0.0), float3(c.a));
        return half4(finalColor * keep);
    }
""".trimIndent()
