package com.xjtu.toolbox.ui.glass.coui

/** GrRRectShadowGeoProc 使用的离散 A8 表, 不是连续 Gaussian 的近似替换。 */
internal const val ShadowVertexShader = """
Varyings main(const Attributes a) {
    Varyings v;
    v.position = a.position;
    v.shadow = a.shadow;
    return v;
}
"""

internal fun shadowFragmentShader(fast: Boolean, oval: Boolean): String = """
uniform half4 u_color;
uniform float4 u_rect;
""" + (if (oval) OvalShadowClip else "float clipAlpha(float2 p) { return 1.0; }\n") +
    if (fast) """
float2 main(const Varyings v, out float4 color) {
    color = u_color * clipAlpha(v.position);
    half3 shadowParams = half3(v.shadow);
    half d = length(shadowParams.xy);
    return float2(128.0 * float(shadowParams.z * (1.0 - d)), 0.5);
}
""" else """
float2 main(const Varyings v, out float4 color) {
    half factor = 1.0 - half(v.shadow.z);
    factor = exp(-factor * factor * 4.0) - 0.018;
    color = u_color * (factor * clipAlpha(v.position));
    return v.position;
}
"""

internal fun shadowClipShader(oval: Boolean, image: Boolean): String = "#version 300\n" +
    "uniform float4 u_rect;\n" + (if (image) "uniform shader image;\n" else "") +
    (if (oval) OvalShadowClip else CapsuleShadowClip) +
    if (image) "half4 main(float2 p) { return image.eval(p) * clipAlpha(p); }"
    else "half4 main(float2 p) { return half4(clipAlpha(p)); }"

// shortcut: 公开 Mesh 不暴露 HWUI 精度 caps, 使用原 FP32 分支; 有可靠 caps 入口后补低精度分支。
private const val OvalShadowClip = """
float clipAlpha(float2 p) {
    float2 radius = (u_rect.zw - u_rect.xy) * 0.5;
    float2 center = (u_rect.zw + u_rect.xy) * 0.5;
    if (abs(radius.x - radius.y) <= 0.000244140625) {
        if (radius.x < 0.5) return 1.0;
        float r = max(radius.x - 0.5, 0.001);
        half d = half((length((center - p) * (1.0 / r)) - 1.0) * r);
        return saturate(d);
    }
    float2 d = p - center;
    float2 z = d / (radius * radius);
    float implicitValue = dot(z, d) - 1.0;
    float grad = max(4.0 * dot(z, z), 1.1755e-38);
    float distance = implicitValue * inversesqrt(grad);
    return saturate(0.5 + half(distance));
}
"""

// OplusG2CornerEffect: fullsmooth / weight=1 / inverse fill AA。
private const val CapsuleShadowClip = """
float smoothAbs(float x, float k) { return sqrt(x * x + k * k); }
float smoothMaxSqrt(float a, float b, float k) {
    float d = a - b;
    return 0.5 * (a + b + sqrt(d * d + k * k));
}
float clipAlpha(float2 p) {
    float2 hs = (u_rect.zw - u_rect.xy) * 0.5;
    float2 pl = p - (u_rect.xy + u_rect.zw) * 0.5;
    if (hs.x < hs.y) { hs = hs.yx; pl = pl.yx; }
    float2 q = float2(smoothAbs(pl.x, 1.0), smoothAbs(pl.y, 1.0));
    float r = hs.y;
    float a = max(hs.x - r, 0.0);
    float zoneCap = abs(pl.x) - a;
    float scale = r * 0.0125;
    float transitionPixels = max(4.0, 70.0 * scale);
    float s = q.x - a;
    float xHard = max(s, 0.0);
    float xSoft = smoothMaxSqrt(s, 0.0, 26.6 * scale);
    float wx = 1.0 - smoothstep(0.0, max(70.0 * scale, 1e-3), abs(s));
    float wy = smoothstep(r - max(60.0 * scale, 1e-3), r, q.y);
    float x = mix(xHard, xSoft, clamp(wx * wy, 0.0, 1.0));
    float d = length(float2(x, q.y)) - r;
    float aa = fwidth(d);
    float aaFactor = smoothstep(-transitionPixels - 2.0, -transitionPixels * 0.2, zoneCap);
    float currentAA = mix(1e-4, aa, aaFactor);
    float fillAlpha = 1.0 - smoothstep(-currentAA, currentAA, d);
    return 1.0 - half(fillAlpha);
}
"""
