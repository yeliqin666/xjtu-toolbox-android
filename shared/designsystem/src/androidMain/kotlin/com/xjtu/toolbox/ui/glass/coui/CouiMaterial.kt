package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** BackgroundBlurDrawable 的普通材质模式，亮度混色后执行第二层混合。 */
enum class CouiMaterialBlendMode { Overlay, ColorDodge }

/** 两层材质混色, 只作用于背景输入。组件负责选择颜色和模式预设。 */
@Composable
fun rememberCouiMaterialEffect(mix: Color, blend: Color,
    blendMode: CouiMaterialBlendMode = CouiMaterialBlendMode.Overlay): RenderEffect =
    remember(mix, blend, blendMode) {
        val shader = RuntimeShader(CouiMaterialShader)
        shader.setFloatUniform("uMixColor", mix.red, mix.green, mix.blue, mix.alpha)
        shader.setFloatUniform("uBlendColor", blend.red, blend.green, blend.blue, blend.alpha)
        shader.setFloatUniform("uColorDodge", if (blendMode == CouiMaterialBlendMode.ColorDodge) 1f else 0f)
        RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

internal const val CouiMaterialShaderFunctions = """
    float lum(float3 color) {
        return dot(color, float3(0.3, 0.59, 0.11));
    }

    float3 luminosity(float3 source, float3 destination) {
        float destinationLum = lum(destination);
        float sourceLum = lum(source);
        float3 color = destination + (sourceLum - destinationLum);
        float minColor = min(min(color.x, color.y), color.z);
        float maxColor = max(max(color.x, color.y), color.z);
        if (minColor < 0.0) {
            return sourceLum + ((color - sourceLum) * sourceLum) / (sourceLum - minColor);
        } else if (maxColor > 1.0) {
            return sourceLum + ((color - sourceLum) * (1.0 - sourceLum)) / (maxColor - sourceLum);
        }
        return color;
    }

    float overlay(float source, float destination) {
        return destination < 0.5
            ? 2.0 * source * destination
            : 1.0 - 2.0 * (1.0 - source) * (1.0 - destination);
    }

    float3 overlay(float3 source, float3 destination) {
        return float3(
            overlay(source.x, destination.x),
            overlay(source.y, destination.y),
            overlay(source.z, destination.z)
        );
    }

    half4 couiMaterial(half4 inColor, float4 mixColor, float4 blendColor) {
        float3 luminosityColor = luminosity(mixColor.rgb, inColor.rgb);
        inColor.rgb = mix(inColor.rgb, luminosityColor, mixColor.a);
        float3 overlayColor = overlay(blendColor.rgb, inColor.rgb);
        inColor.rgb = mix(inColor.rgb, overlayColor, blendColor.a);
        return inColor;
    }
"""

private const val CouiMaterialShader = CouiMaterialShaderFunctions + """
    uniform shader content;
    uniform float4 uMixColor;
    uniform float4 uBlendColor;
    uniform float uColorDodge;

    half4 main(float2 coordinate) {
        half4 color = content.eval(coordinate);
        if (uColorDodge < 0.5) return couiMaterial(color, uMixColor, uBlendColor);
        color.rgb = mix(color.rgb, luminosity(uMixColor.rgb, color.rgb), uMixColor.a);
        // MaterialFilter::appendColorDodge：不提前限幅；在最终 SDR 输出处合成。
        color.rgb = mix(color.rgb, color.rgb / (1.0 - uBlendColor.rgb), uBlendColor.a);
        return color;
    }
"""
