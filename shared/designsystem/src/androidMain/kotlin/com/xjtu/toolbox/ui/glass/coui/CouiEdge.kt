package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/** 背景感知描边的色彩样式, 共用深色描边几何。 */
enum class CouiEdgeStyle { Light, Dark }

/** 接收原始模糊图与组件材质配色; contourGlowEnabled 同时控制描边与内光晕。 */
class CouiEdge(
    style: CouiEdgeStyle,
    private val radiusPx: Float,
    private val cornerWeight: Float,
    mix: Color,
    blend: Color,
    private val innerGlowAlpha: Float,
    contourGlowEnabled: Boolean = true,
    private val lineAlpha: Float = if (style == CouiEdgeStyle.Light) 0.55f else 0.5f,
) {
    private val shader = RuntimeShader(CouiEdgeShader)
    private var lastSize = Size.Zero
    private var lastDensity = 0f
    private var cachedEffect: RenderEffect? = null

    init {
        require(radiusPx.isFinite() && radiusPx >= 0f) { "圆角半径必须为有限非负值" }
        require(cornerWeight.isFinite() && cornerWeight >= 2f) { "圆角权重必须为有限值且不小于 2, 与裁剪 Shape 保持一致" }
        require(innerGlowAlpha.isFinite() && innerGlowAlpha in 0f..1f) { "内晕透明度必须为 0 到 1 的有限值" }
        require(lineAlpha.isFinite() && lineAlpha in 0f..1f) { "描边透明度必须为 0 到 1 的有限值" }
        shader.setFloatUniform("uMixColor", mix.red, mix.green, mix.blue, mix.alpha)
        shader.setFloatUniform("uBlendColor", blend.red, blend.green, blend.blue, blend.alpha)
        shader.setFloatUniform("uUseLightEdgeColor", if (style == CouiEdgeStyle.Light) 1f else 0f)
        shader.setFloatUniform("uContourGlowEnabled", if (contourGlowEnabled) 1f else 0f)
    }

    fun renderEffect(size: Size, density: Float): RenderEffect {
        cachedEffect?.let { effect ->
            if (size == lastSize && density == lastDensity) return effect
        }
        shader.setCouiContourUniforms(size, density, radiusPx, cornerWeight, innerGlowAlpha, lineAlpha)
        // 与现有模糊层一致, uniform 更新后重建 effect, 不复用旧尺寸的 native 快照。
        val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        cachedEffect = effect
        lastSize = size
        lastDensity = density
        return effect
    }
}

internal fun RuntimeShader.setCouiContourUniforms(
    size: Size,
    density: Float,
    radiusPx: Float,
    cornerWeight: Float,
    innerGlowAlpha: Float,
    lineAlpha: Float,
) {
    val width = max(1f, size.width)
    val height = max(1f, size.height)
    val radius = radiusPx.coerceIn(0f, size.minDimension.coerceAtLeast(0f) / 2f)
    setFloatUniform(
        "u_commonArray",
        floatArrayOf(
            width / 2f, height / 2f, 0f, 0f, width / 2f, height / 2f,
            radius, cornerWeight,
        ),
    )
    // NotificationPlatFormBlurParamsManager: 仅基准尺寸乘 density, 不缩放纵向光效参数。
    val baseWidth = max(1f, 328f * density)
    val baseHeight = max(1f, 60f * density)
    val (nearSolid, nearFade) = rectLineFadeForSize(
        0.15f, 0.4f, baseWidth, baseHeight, width, height,
    )
    val (farSolid, farFade) = rectLineFadeForSize(
        0.2f, 0.4f, baseWidth, baseHeight, width, height,
    )
    setFloatUniform(
        "u_edgeArray",
        floatArrayOf(
            1f, 1f, 1f, 2f, 8f, 2f, 6f,
            0.7f * lineAlpha, lineAlpha,
            nearSolid, nearSolid + max(nearFade, 0.001f),
            farSolid, farSolid + max(farFade, 0.001f),
            0f, 4.46f, 1.1f,
        ),
    )
    // COUI SHADOW_STYLE_2 的 business Mapper, 主题透明度由组件装配层提供。
    val shadowScale = max(1f, density) * 0.34f
    setFloatUniform(
        "u_shadowArray",
        floatArrayOf(
            1f, 1f, 1f,
            innerGlowAlpha, 0f, 0f,
            86f * shadowScale, 0f, 10f * shadowScale,
            1f + (1.1f - 1f) * 600f * shadowScale / width,
            1f + (1.07f - 1f) * 600f * shadowScale / height,
            4f, 0.7f, shadowScale, 1f, 0f,
        ),
    )
}

internal fun rectLineFadeForSize(
    solid: Float,
    fade: Float,
    baseWidth: Float,
    baseHeight: Float,
    width: Float,
    height: Float,
): Pair<Float, Float> {
    val basePerimeter = (baseWidth + baseHeight) * 2f
    val perimeter = (width + height) * 2f
    val total = solid + fade
    val end = total * basePerimeter
    if (end > baseWidth) {
        val fadeLength = fade * basePerimeter
        return ((end - baseWidth + width - fadeLength) / perimeter) to (fadeLength / perimeter)
    }
    if (total <= 0f) return solid to fade
    val mappedTotal = (width - (baseWidth - end)) / perimeter
    return (solid * mappedTotal / total) to (mappedTotal * fade / total)
}

internal const val CouiEdgeColorFunctions = """
        float colorDodge(float s, float d) {
            return d / (1.0 - s + 0.00000001);
        }

        float3 colorDodge(float3 s, float3 d) {
            return float3(colorDodge(s.x, d.x), colorDodge(s.y, d.y), colorDodge(s.z, d.z));
        }

        // Shade Light 的面板混色在前, 普通有描边卡片混色在后; 参数来自 PJZ110 SystemUI。
        half4 lightEdgeReferenceColor(half4 color) {
            color = couiMaterial(color,
                float4(0.0, 0.0, 0.0, 146.0 / 255.0),
                float4(float3(153.0 / 255.0), 102.0 / 255.0));
            color.rgb = mix(color.rgb, luminosity(float3(64.0 / 255.0), color.rgb), 25.0 / 255.0);
            color.rgb = mix(color.rgb, colorDodge(float3(115.0 / 255.0), color.rgb), 77.0 / 255.0);
            return color;
        }

        half4 couiEdgeColor(half4 rawColor, float3 lineColor, bool limitHighlights) {
            float gray = clamp(dot(float3(0.2126, 0.7152, 0.0722), rawColor.rgb)*1.2,0.0,1.0);
            float edgeImprove = (1.0 - gray);
            edgeImprove *= edgeImprove;
            edgeImprove *= edgeImprove;
            edgeImprove *= 0.1;
            half4 color = clamp(rawColor + half4(float3(edgeImprove), 0.0), 0.0, 1.0);
            color.rgb = mix(2.0 * color.rgb * lineColor, 1.0 - 2.0 * (1.0 - color.rgb) * (1.0 - lineColor), step(lineColor, float3(0.5)));
            // 等比例收回过亮描边，避免逐通道截断后变成白边。
            if (limitHighlights) {
                float peak = max(max(color.r, color.g), color.b);
                color.rgb /= max(1.0, peak);
            }
            return color;
        }
"""

// 通知描边与 COUI 菜单内晕共用距离场; 裁剪 Shape 须使用相同圆角和权重。
internal const val CouiContourShaderFunctions = CouiEdgeColorFunctions + """
        uniform float uUseLightEdgeColor;
        uniform float uNativeWhiteEdge;
        uniform float uContourGlowEnabled;
        uniform float u_commonArray[8];
        uniform float u_shadowArray[16];
        uniform float u_edgeArray[16];

       const float EPS = 1e-6;
       const float EFFECT_PARAM_THRESHOLD = 0.001;

       struct CommonDataRect{
            vec2 halfResolution;
            vec2 pos;
            vec2 halfSize;
            float coner;
            float weight;
        };

        float random(vec2 p){
            return fract(sin(dot(p.yx,vec2(79.1214,78.233)))*43760.5453);
        }

        float softLight(float A,float B){
            float l=B-(1.-2.*A)*B*(1.-B);
            float h=B+(2.*A-1.)*(sqrt(B)-B);
            return mix(l,h,step(.5,A));
        }

        const float kAbs = 1.0;
        const float kMax = 26.6;
        const float junctionXBand = 70.0;
        const float junctionYBand = 60.0;

        float smoothAbs(float x, float k) {
            return sqrt(x * x + k * k);
        }

        float smoothMaxSqrt(float a, float b, float k) {
            float d = a - b;
            return 0.5 * (a + b + sqrt(d * d + k * k));
        }

        float sdCapsule(vec2 pLocal, vec2 halfSize) {
            bool isVertical = halfSize.x < halfSize.y;
            vec2 hs = isVertical ? halfSize.yx : halfSize;
            vec2 pl = isVertical ? pLocal.yx : pLocal;

            vec2 q = vec2(smoothAbs(pl.x, kAbs), smoothAbs(pl.y, kAbs));
            float r = hs.y;
            float a = max(hs.x - r, 0.0);

            float scale = r * 0.0125;
            float kMaxEff = kMax * scale;
            float xBandEff = junctionXBand * scale;
            float yBandEff = junctionYBand * scale;

            float s = q.x - a;
            float xHard = max(s, 0.0);
            float xSoft = (kMaxEff > 0.0) ? smoothMaxSqrt(s, 0.0, kMaxEff) : xHard;
            float wx = 1.0 - smoothstep(0.0, max(xBandEff, 1e-3), abs(s));
            float wy = smoothstep(r - max(yBandEff, 1e-3), r, q.y);
            float w = clamp(wx * wy, 0.0, 1.0);
            float x = mix(xHard, xSoft, w);
            return length(vec2(x, q.y)) - r;
        }

        float powFast(float x, float n) {
            // 保护极小值，防止 log2(0)
            x = max(x, 1e-8);
            return exp2(log2(x) * n);
        }

        float lpNorm(vec2 v, float n) {
            v = abs(v);
            float m = max(v.x, v.y);
            if (m < 1e-8) return 0.0;
            vec2 u = v / m;
            float s = powFast(u.x, n) + powFast(u.y, n);
            return m * exp2(log2(max(s, EPS)) / n);
        }

        float lpNorm4(vec2 v) {
            v = abs(v);
            vec2 v2 = v * v;
            vec2 v4 = v2 * v2;
            return sqrt(sqrt(max(v4.x + v4.y, 0.0)));
        }

        float sdRoundRect(vec2 p, vec2 halfSize, float r, float n) {
            vec2 q = abs(p) - halfSize + vec2(r);
            float nClamped = max(n, 2.0);
            float dCorner = (abs(nClamped - 4.0) < 1e-3) ? lpNorm4(max(q, 0.0)) : lpNorm(max(q, 0.0), nClamped);
            if (q.x > 0.0 && q.y > 0.0) return dCorner - r;
            else return max(q.x, q.y) - r;
        }


        float smoothSDF_D(vec2 p, vec2 b, float corner, float weight){
            float rectSdf = 0.0;

            if (weight  < 2.0) {
                if(weight  > 1.005){
                    float t = smoothstep(2.0, 1.0, weight);
                    float d_G2 = sdRoundRect( p, b, corner, 2.0);

                    float d_Capsule = sdCapsule( p, b);
                    rectSdf = mix(d_G2, d_Capsule, t);
                }
                else
                {
                    rectSdf = sdCapsule( p, b);
                }
            } else {
                rectSdf = sdRoundRect( p, b, corner, weight);
            }
            return rectSdf;
        }

        // ShadowEdge NEW_G2：直边到圆角区的 AA 宽度，不改既有菜单/FAB 的裁剪契约。
        float shadowEdgeCoverage(CommonDataRect rectData, float d) {
            vec2 q = abs(rectData.pos) - rectData.halfSize + vec2(rectData.coner);
            float zone = min(q.x, q.y);
            float transitionPixels = 4.0;
            if (rectData.weight < 2.0) {
                bool vertical = rectData.halfSize.x < rectData.halfSize.y;
                vec2 hs = vertical ? rectData.halfSize.yx : rectData.halfSize;
                vec2 p = vertical ? rectData.pos.yx : rectData.pos;
                float zoneCap = abs(p.x) - max(hs.x - hs.y, 0.0);
                transitionPixels = max(4.0, hs.y * 0.0125 * 70.0);
                zone = rectData.weight > 1.005
                    ? mix(zone, zoneCap, smoothstep(2.0, 1.0, rectData.weight)) : zoneCap;
            }
            float factor = smoothstep(-transitionPixels - 2.0, -transitionPixels * 0.2, zone);
            float aa = mix(EPS, 1.0, factor);
            return 1.0 - smoothstep(-aa, aa, d);
        }

        float4 blendMenuInnerGlow(CommonDataRect rectData, float4 color, float d, float2 fragCoord) {
            float3 uFadeColor = float3(u_shadowArray[0], u_shadowArray[1], u_shadowArray[2]);
            float3 uFadeAlpha = float3(u_shadowArray[3], u_shadowArray[4], u_shadowArray[5]);
            float uFadeIn = u_shadowArray[6];
            float2 uFadeOffset = float2(u_shadowArray[7], u_shadowArray[8]);
            float2 uFadeScale = float2(u_shadowArray[9], u_shadowArray[10]);
            float uFadeInPara1 =  u_shadowArray[11];
            float uFadeInPara2 =  u_shadowArray[12];
            float uFadeOut = u_shadowArray[13];
            float uFadeOutPara1 =  u_shadowArray[14];
            float uFadeOutPara2 = u_shadowArray[15];
            float preCalcMinC = min(rectData.halfSize.x,rectData.halfSize.y);
            float2 fadeConer;
            fadeConer.y = min(preCalcMinC, max(uFadeOut*1.2, rectData.coner));

            float fadeAlpha = 0.0;
            if(any(greaterThan(uFadeAlpha.xy, float2(EFFECT_PARAM_THRESHOLD)))) {
                float2 pos_new = (rectData.pos-uFadeOffset);
                float fadeLine = uFadeIn*-1.0;
                float fadeSdf = fadeLine;
                float2 halfSizeFade = rectData.halfSize*uFadeScale;
                fadeConer.x = min(min(halfSizeFade.x, halfSizeFade.y),max(uFadeIn*1.2,rectData.coner));
                if(rectData.weight<2.0 || any(greaterThan(abs(pos_new), halfSizeFade - float2(fadeConer.x)))) {
                    fadeSdf = smoothSDF_D(pos_new, halfSizeFade, fadeConer.x, rectData.weight);
                    float alpha = smoothstep(fadeLine,0.0,fadeSdf);
                    if(uFadeInPara2>EFFECT_PARAM_THRESHOLD) {
                        alpha = pow(alpha, uFadeInPara1)*uFadeInPara2+(1.0-uFadeInPara2)*alpha;
                    }
                    alpha = alpha*(uFadeAlpha.x-uFadeAlpha.y) + uFadeAlpha.y;
                    fadeAlpha = alpha;
                } else {
                    fadeAlpha = uFadeAlpha.y;
                }
            }

            if(uFadeAlpha.z>EFFECT_PARAM_THRESHOLD) {
                float fadeLine =  uFadeOut *-1.0;
                float fadeSdf = fadeLine;
                if(rectData.weight<2.0 || any(greaterThan(abs(rectData.pos), rectData.halfSize - float2(fadeConer.y)))) {
                    if(abs(fadeConer.y - rectData.coner) < EFFECT_PARAM_THRESHOLD) {
                        fadeSdf = d;
                    } else {
                        fadeSdf = smoothSDF_D(rectData.pos, rectData.halfSize, fadeConer.y, rectData.weight);
                    }
                    float alpha = smoothstep(fadeLine,0.0,fadeSdf);
                    if(uFadeOutPara2>EFFECT_PARAM_THRESHOLD) {
                        alpha = pow(alpha, uFadeOutPara1)*uFadeOutPara2+(1.0-uFadeOutPara2)*alpha;
                    }
                    alpha *= uFadeAlpha.z;
                    fadeAlpha = mix(fadeAlpha, 1.0,alpha);
                }
            }

            float2 uv_pos=fragCoord.xy*0.013;
            float n=clamp((random(uv_pos)-.5)*0.01+.5,0.,1.);
            fadeAlpha=clamp(softLight(n,fadeAlpha),0.0,1.0);
            float4 fadeColor = float4(uFadeColor, fadeAlpha);
            fadeColor.rgb *= fadeColor.a;
            float4 reColor = float4(0.0);
            reColor.rgb = color.rgb*(1.0-fadeColor.a)+fadeColor.rgb;
            reColor.a = color.a*(1.0-fadeColor.a)+fadeColor.a;
            return reColor;
        }

        float getDis(float2 pos,float2 halfsize) {
            float2 rect=abs(pos-halfsize);
            return rect.x+rect.y;
        }

        half4 blendEdge(CommonDataRect rectData, half4 color, half4 rawColor, float d, float mix_c) {
            float3 uRectLineColor = float3(u_edgeArray[0], u_edgeArray[1], u_edgeArray[2]);
            float2 uRectLineNear = float2(u_edgeArray[3], u_edgeArray[4]);
            float2 uRectLineFar = float2(u_edgeArray[5], u_edgeArray[6]);
            float2 uRectLineAlpha = float2(u_edgeArray[7], u_edgeArray[8]);
            half4 uRectLineFade = half4(u_edgeArray[9], u_edgeArray[10], u_edgeArray[11], u_edgeArray[12]);
            float uAngle = u_edgeArray[13];
            float uRectLinePara1 = u_edgeArray[14];
            float uRectLinePara2 = u_edgeArray[15];
            float2 rectLineNear=uRectLineNear;
            float2 rectLineFar=uRectLineFar;
            half4 rectLineFade=uRectLineFade;
            if(d<-max(rectLineNear.y,rectLineFar.y)) {
                return color;
            }
            float distanceAll = (rectData.halfSize.x+rectData.halfSize.y)*2.0;
            float2 halfSize = rectData.halfSize;
            float ratio = mod(uAngle+rectData.halfSize.x/distanceAll*0.5+0.5,1.0);
            float2 dir_pos= normalize(rectData.pos);
            float2 dirAbs_pos = abs(dir_pos);
            float2 pos_n = min(float2(dirAbs_pos.xy*halfSize.yx/max(float2(.001),dirAbs_pos.yx)),halfSize)*sign(dir_pos);
            float2 halfSize_symobol = float2(-halfSize.y,halfSize.x);
            float flag = mix(-1.0,1.0, step(ratio,0.5))*dot(halfSize_symobol,pos_n);
            float dis_point = distanceAll*min(ratio,1.0-ratio)*2.0;
            float dis_pos = getDis(pos_n,halfSize);
            float distance_near = mix(dis_point+dis_pos,abs(dis_point-dis_pos),step(0.,flag));
            distance_near=min(2.*distanceAll-distance_near,distance_near);
            float lineNearAlpha = uRectLineAlpha.x*smoothstep(rectLineFade.y*distanceAll, rectLineFade.x*distanceAll, distance_near);
            float lineFarAlpha = uRectLineAlpha.y*smoothstep(rectLineFade.w*distanceAll, rectLineFade.z*distanceAll, distanceAll-distance_near);

            float lineNearSdf=smoothstep(-rectLineNear.y,-rectLineNear.x,d);
            float lineFarSdf=smoothstep(-rectLineFar.y,-rectLineFar.x,d);

            if(uRectLinePara2 > EPS) {
                lineNearSdf = pow(lineNearSdf, uRectLinePara1)*uRectLinePara2+(1.0-uRectLinePara2)*lineNearSdf;
                lineFarSdf = pow(lineFarSdf, uRectLinePara1)*uRectLinePara2+(1.0-uRectLinePara2)*lineFarSdf;
            }

            lineNearAlpha*=lineNearSdf;
            lineFarAlpha*=lineFarSdf;

            float lineAlpha = clamp(lineNearAlpha+lineFarAlpha,0.,1.);

            if (uNativeWhiteEdge > 0.5) {
                // ShadowEdge Drawable 输出预乘白光；这里没有后景感知提亮。
                half4 edge = half4(uRectLineColor * lineAlpha, lineAlpha);
                return color * (1.0 - lineAlpha) + edge;
            }

            return mix(color, couiEdgeColor(rawColor, uRectLineColor, uUseLightEdgeColor > 0.5), lineAlpha*mix_c);
        }

"""

private const val CouiEdgeShader = CouiMaterialShaderFunctions + CouiContourShaderFunctions + """
        uniform shader content;
        uniform float4 uMixColor;
        uniform float4 uBlendColor;

        half4 main(float2 fragCoord) {
            half4 sourceColor = content.eval(fragCoord);
            half4 displayColor = couiMaterial(sourceColor, uMixColor, uBlendColor);
            if (uContourGlowEnabled < 0.5) return displayColor;
            half4 edgeReferenceColor = displayColor;
            if (uUseLightEdgeColor > 0.5) {
                edgeReferenceColor = lightEdgeReferenceColor(sourceColor);
            }
            float2 coords = float2(fragCoord.x, u_commonArray[1]*2.0-fragCoord.y);
            CommonDataRect rectData;
            rectData.halfResolution = float2(u_commonArray[0], u_commonArray[1]);
            rectData.pos = coords - float2(u_commonArray[2], u_commonArray[3]) - rectData.halfResolution;
            rectData.halfSize = float2(u_commonArray[4], u_commonArray[5]);
            rectData.coner = u_commonArray[6];
            rectData.weight = u_commonArray[7];
            float d = smoothSDF_D(rectData.pos, rectData.halfSize, rectData.coner, rectData.weight);
            half4 color = blendMenuInnerGlow(rectData, displayColor, d, fragCoord);
            color = blendEdge(rectData, color, edgeReferenceColor, d, 1.0);
            // shortcut: 整材质由调用方的公开 Shape Path 裁剪, 未移植厂商 GPU 抗锯齿覆盖率。
            return color;
        }
"""
