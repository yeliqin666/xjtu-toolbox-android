package com.xjtu.toolbox.ui.glass.coui

// OplusCausticShadowEffectImpl::emitCode 的 SDR 分支; 输入是该次投影前的 destination。
internal const val CouiCausticShader = """#version 300
uniform shader u_originShader;
uniform float u_commonArray[12];
uniform float u_pyrolysisArray[14];
uniform float2 u_offset;
uniform float2 u_invScale;
uniform int u_foregroundComposition;
float RBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.) + length(max(q, 0.)) - r;
}
float Oval(float2 p, float2 b, float r) {
    float k0 = length(p / b);
    float k1 = length(p / (b * b));
    return k0 * (k0 - 1.0) / k1;
}
float4 performBlend(float4 base, float4 blend) {
    float3 resultRgb = base.rgb + (blend.rgb * blend.a);
    float resultAlpha = base.a * (1.0 - blend.a) + blend.a;
    return float4(clamp(resultRgb, 0.0, 1.0), resultAlpha);
}
half4 caustic(float2 position, half4 destination) {
    float2 halfRecSize = float2(u_commonArray[4], u_commonArray[5]) * .5;
    float2 p = (position + u_offset - halfRecSize) * u_invScale + halfRecSize;
    if (u_pyrolysisArray[3] > 0.001) {
        float2 recLT = float2(u_commonArray[2], u_commonArray[3]);
        float2 recCenter = recLT + halfRecSize;
        float recCorner = u_commonArray[6];
        float2 opticsOffset = float2((u_pyrolysisArray[4] + u_pyrolysisArray[6]) * 0.5,
                                    (u_pyrolysisArray[5] + u_pyrolysisArray[7]) * 0.5);
        float2 pos_optics = p - recCenter - opticsOffset;
        float2 halfSizeOptics = halfRecSize + float2(u_pyrolysisArray[6] - u_pyrolysisArray[4],
                                                   u_pyrolysisArray[7] - u_pyrolysisArray[5]) * 0.5;
        float sdf = min(u_pyrolysisArray[8], min(halfRecSize.x, halfRecSize.y));
        float opticsSdf = sdf;
        if (all(lessThanEqual(abs(pos_optics), halfSizeOptics))) {
            halfSizeOptics -= opticsSdf;
            halfSizeOptics = max(halfSizeOptics, float2(0.0));
            float conerOptics = min(recCorner, min(halfSizeOptics.x, halfSizeOptics.y));
            uint outlineType = floatBitsToUint(u_commonArray[10]);
            if (outlineType == 0) {
                opticsSdf = RBox(pos_optics, halfSizeOptics, conerOptics);
            } else if (outlineType == 1) {
                opticsSdf = Oval(pos_optics, halfSizeOptics, conerOptics);
            }
            float value = smoothstep(sdf, 0.0, opticsSdf);
            value *= pow(smoothstep(halfSizeOptics.y + sdf, -halfSizeOptics.y - sdf, -pos_optics.y), u_pyrolysisArray[11]);
            if (u_pyrolysisArray[10] > 0.001) {
                value = pow(value, u_pyrolysisArray[9]) * u_pyrolysisArray[10] + (1.0 - u_pyrolysisArray[10]) * value;
            }
            float4 opticsColor = float4(u_pyrolysisArray[0], u_pyrolysisArray[1], u_pyrolysisArray[2], u_pyrolysisArray[3]);
            opticsColor.a *= value;
            float gray = clamp((dot(float3(0.2126, 0.7152, 0.0722), destination.rgb) - u_pyrolysisArray[12]) * u_pyrolysisArray[13], 0.0, 1.0);
            opticsColor.a *= (1.0 - gray) * (1.0 - gray);
            return half4(performBlend(destination, opticsColor));
        }
    }
    return destination;
}
float channelCoverage(float base, float result) {
    if (result > base) return (result - base) / (1.0 - base);
    if (result < base) return (base - result) / base;
    return 0.0;
}
half4 main(float2 position) {
    half4 destination = u_originShader.eval(position);
    half4 result = caustic(position, destination);
    if (u_foregroundComposition == 0) return result;
    // 在不透明 SDR 背景上重建原结果, 无光处输出透明, 避免矩形回填擦掉兄弟投影。
    float3 base = float3(destination.rgb);
    float3 color = float3(result.rgb);
    float3 coverage = float3(channelCoverage(base.r, color.r),
                            channelCoverage(base.g, color.g),
                            channelCoverage(base.b, color.b));
    float alpha = max(coverage.r, max(coverage.g, coverage.b));
    return half4(clamp(color - base * (1.0 - alpha), 0.0, alpha), alpha);
}
"""
