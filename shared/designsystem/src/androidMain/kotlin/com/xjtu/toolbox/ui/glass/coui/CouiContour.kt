package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RuntimeShader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** 独立轮廓预设；Menu 保留既有菜单分布，Dialog/Button 对应原厂 ShadowEdge Drawable。 */
enum class CouiContourStyle { Menu, Dialog, Button }

/**
 * 在实际半透明表面上绘制轮廓与内晕, 不采样系统 Window 背景。
 * 输出已包含 surfaceColor, 调用方不能再叠加一次底色; effectAlpha 只衰减光效。
 * floating 使用 FAB 的加亮算法并保持表面 alpha, 默认仍使用独立白光覆盖率。
 */
class CouiContour(private val floating: Boolean = false, private val style: CouiContourStyle = CouiContourStyle.Menu) {
    init { require(!floating || style == CouiContourStyle.Menu) { "浮动轮廓不可同时使用 Drawable 预设" } }
    private val shader = RuntimeShader(if (floating) couiFloatingContourShader() else CouiContourShader)

    private var nativeParameters: NativeParameters? = null
    private var lastSurface = Color.Unspecified
    private var lastEffectAlpha = Float.NaN

    fun shader(
        size: Size,
        density: Float,
        radiusPx: Float,
        cornerWeight: Float,
        surfaceColor: Color,
        innerGlowAlpha: Float,
        lineAlpha: Float,
        effectAlpha: Float,
        lineWidthPx: Float = 2.5f * density,
    ): RuntimeShader {
        require(radiusPx.isFinite() && radiusPx >= 0f) { "圆角半径必须为有限非负值" }
        require(cornerWeight.isFinite() && cornerWeight >= if (style == CouiContourStyle.Menu) 2f else 1f) { "圆角权重不符合所选轮廓预设" }
        require(innerGlowAlpha.isFinite() && innerGlowAlpha in 0f..1f) { "内晕透明度必须为 0 到 1 的有限值" }
        require(lineAlpha.isFinite() && lineAlpha in 0f..1f) { "描边透明度必须为 0 到 1 的有限值" }
        require(effectAlpha.isFinite() && effectAlpha in 0f..1f) { "光效透明度必须为 0 到 1 的有限值" }
        if (lastSurface != surfaceColor) {
            shader.setFloatUniform("uSurfaceColor", surfaceColor.red, surfaceColor.green, surfaceColor.blue, surfaceColor.alpha)
            lastSurface = surfaceColor
        }
        if (lastEffectAlpha != effectAlpha) {
            shader.setFloatUniform("uEffectAlpha", effectAlpha)
            lastEffectAlpha = effectAlpha
        }
        if (floating) {
            val halfWidth = size.width.coerceAtLeast(1f) * 0.5f
            val halfHeight = size.height.coerceAtLeast(1f) * 0.5f
            val radius = radiusPx.coerceAtMost(size.minDimension.coerceAtLeast(0f) * 0.5f)
            val ball = floatArrayOf(0f, 0f, halfWidth, halfHeight, radius, cornerWeight, 1f, 1f)
            val glow = floatingInnerGlow(ball, innerGlowAlpha, 2)
            shader.setFloatUniform(
                "u_commonArray",
                floatArrayOf(halfWidth, halfHeight, 0f, 0f, halfWidth, halfHeight, radius, cornerWeight),
            )
            shader.setFloatUniform(
                "u_baseShapeCtxArray",
                floatingShapeContext(halfWidth, halfHeight, cornerWeight, radius),
            )
            shader.setFloatUniform("u_edgeArray", floatingEdge(ball, 2.5f * density, lineAlpha, 2))
            shader.setFloatUniform("u_shadowArray", glow)
            shader.setFloatUniform("u_shadowShapeCtxArray", floatingShadowShapeContext(ball, glow))
        } else {
            require(density.isFinite() && density > 0f && lineWidthPx.isFinite() && lineWidthPx > 0f)
            shader.setFloatUniform("uUseLightEdgeColor", 0f)
            shader.setFloatUniform("uNativeWhiteEdge", if (style == CouiContourStyle.Menu) 0f else 1f)
            shader.setFloatUniform("uNativeShadowEdge", if (style == CouiContourStyle.Menu) 0f else 1f)
            if (style == CouiContourStyle.Menu) {
                shader.setCouiContourUniforms(size, density, radiusPx, cornerWeight, innerGlowAlpha, lineAlpha)
            } else {
                val width = size.width.coerceAtLeast(1f)
                val height = size.height.coerceAtLeast(1f)
                val previous = nativeParameters
                if (previous == null || previous.size != size || previous.density != density ||
                    previous.radius != radiusPx || previous.weight != cornerWeight || previous.inner != innerGlowAlpha ||
                    previous.line != lineAlpha || previous.width != lineWidthPx) {
                    nativeParameters = NativeParameters(size, density, radiusPx, cornerWeight, innerGlowAlpha, lineAlpha, lineWidthPx)
                    shader.setFloatUniform("u_commonArray", floatArrayOf(width / 2f, height / 2f, 0f, 0f,
                        width / 2f, height / 2f, radiusPx.coerceAtMost(size.minDimension / 2f), cornerWeight))
                    shader.setFloatUniform("u_edgeArray", shadowEdgeArray(width, height, density, lineWidthPx,
                        lineAlpha, style == CouiContourStyle.Button))
                    shader.setFloatUniform("u_shadowArray", shadowGlowArray(width, height, density, innerGlowAlpha))
                }
            }
        }
        return shader
    }

    private data class NativeParameters(val size: Size, val density: Float, val radius: Float,
        val weight: Float, val inner: Float, val line: Float, val width: Float)
}

private const val CouiContourShader = CouiMaterialShaderFunctions + CouiContourShaderFunctions + """
    uniform float4 uSurfaceColor;
    uniform float uEffectAlpha;
    uniform float uNativeShadowEdge;

    half4 main(float2 fragCoord) {
        float2 coords = float2(fragCoord.x, u_commonArray[1]*2.0-fragCoord.y);
        CommonDataRect rectData;
        rectData.halfResolution = float2(u_commonArray[0], u_commonArray[1]);
        rectData.pos = coords - float2(u_commonArray[2], u_commonArray[3]) - rectData.halfResolution;
        rectData.halfSize = float2(u_commonArray[4], u_commonArray[5]);
        rectData.coner = u_commonArray[6];
        rectData.weight = u_commonArray[7];
        float d = smoothSDF_D(rectData.pos, rectData.halfSize, rectData.coner, rectData.weight);
        half4 surface = half4(uSurfaceColor.rgb * uSurfaceColor.a, uSurfaceColor.a);
        if (uNativeShadowEdge > 0.5) {
            float coverage = shadowEdgeCoverage(rectData, d);
            if (coverage < EFFECT_PARAM_THRESHOLD) return half4(0.0);
            half4 color = half4(blendMenuInnerGlow(rectData, surface, d, fragCoord));
            if (dot(rectData.pos, rectData.pos) >= EPS) {
                color = blendEdge(rectData, color, half4(1.0), d, 1.0);
            }
            return mix(surface, color, uEffectAlpha) * coverage;
        }
        half4 color = half4(blendMenuInnerGlow(rectData, surface, d, fragCoord));
        // 小尺寸窗口中心可能进入描边带, 此处没有周长方向, 避免原公式 normalize(0)。
        if (dot(rectData.pos, rectData.pos) >= EPS) {
            // 直边保留菜单原有描边分布, 仅在最外侧圆角段淡出, 不从中心重复衰减。
            // 零圆角也保留一个像素的淡出带, 避免 smoothstep 的两端重合。
            float fadeStart = max(rectData.halfSize.x - max(rectData.coner, 1.0), rectData.halfSize.x * 0.5);
            float lineFade = smoothstep(rectData.halfSize.x, fadeStart, abs(rectData.pos.x));
            color = blendEdge(rectData, color, surface, d, lineFade);
        }
        // 将提亮量装入白光 SrcOver 覆盖率, 避免亮后景暗边或白色半透明表面的描边被限幅吃掉。
        float3 light = max(color.rgb - surface.rgb, float3(0.0)) / max(float3(1.0) - surface.rgb, float3(EPS));
        float coverage = clamp(max(max(light.r, light.g), light.b), 0.0, 1.0) * uEffectAlpha;
        // shortcut: 独立白光不保留背景感知色彩, 有真实后景输入时应使用 CouiEdge。
        return surface * (1.0 - coverage) + half4(coverage);
    }
"""
