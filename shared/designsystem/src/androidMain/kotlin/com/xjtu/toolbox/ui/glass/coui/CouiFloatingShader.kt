package com.xjtu.toolbox.ui.glass.coui

// PJZ110 libhwui OplusMaterialEffectMetaBallShader: setCornerParams 对应 cornerType=4。
internal fun couiFloatingShader(index: Int): String {
    require(index in 1..3)
    // 原片段使用 fwidth; 保留导数抗锯齿, 不改成固定像素宽度。
    return "#version 300\nconst int MATERIAL_INDEX = $index;\n" + CouiMaterialShaderFunctions +
        FloatingBaseUniforms + FloatingBallUniforms + FloatingLightingUniforms + FloatingShapeFunctions +
        FloatingPairFunctions + FloatingLightingFunctions + FloatingSdf + when (index) {
        1 -> FloatingIndex1
        2 -> FloatingIndex2
        else -> FloatingIndex3
    } + "return sr;}" + FloatingRect + when (index) {
        1 -> FloatingRect1
        2 -> FloatingRect2
        else -> FloatingRect3
    } + "return rectData;}" + FloatingMain
}

// BaseShader 的非融球 cornerType=4 分支, 不声明关联主体或计算 pair。
internal fun couiFloatingStandaloneShader(): String = "#version 300\n" + CouiMaterialShaderFunctions +
    FloatingBaseUniforms + "uniform float u_baseShapeCtxArray[12];\n" + FloatingLightingUniforms +
    FloatingShapeFunctions + FloatingLightingFunctions + FloatingRect + FloatingStandaloneRect +
    "return rectData;}" + FloatingStandaloneMain

// 只复用浮动组件的表面光效, 不接入采样、融球、投影或导数抗锯齿。
internal fun couiFloatingContourShader(): String = CouiMaterialShaderFunctions + FloatingLightingUniforms + FloatingShapeFunctions +
    FloatingLightingFunctions + FloatingContourMain

private const val FloatingBaseUniforms = """
uniform shader u_originShader;
uniform float2 u_size;
uniform float u_alpha;
uniform float uUseMenuLightEdge;
uniform float u_commonArray[12];
layout(color) uniform half4 u_color;
layout(color) uniform half4 u_themeTint;
"""

private const val FloatingBallUniforms = """
uniform float u_ball1Array[8];
uniform float u_ball2Array[8];
uniform float u_ball3Array[8];
uniform float u_ball1ShapeCtxArray[12];
uniform float u_ball2ShapeCtxArray[12];
uniform float u_ball3ShapeCtxArray[12];
uniform float u_preCmpArray12[24];
uniform float u_preCmpArray13[24];
uniform float u_preCmpArray23[24];
"""

private const val FloatingLightingUniforms = """
uniform float u_shadowArray[28];
uniform float u_shadowShapeCtxArray[24];
uniform float u_edgeArray[28];
"""

private const val FloatingShapeFunctions = """
struct CommonDataRect{
vec2 halfResolution;
vec2 pos;
vec2 halfSize;
vec2 invScale;
float coner;
float weight;
}
;
struct ShapeResult {
float sdf;
 float mixC;
}
;
struct RectCorner {
float2 center;
float2 halfSize;
float coner;
float weight;
}
;
const float EPS = 1e-6;
const float PI = 3.1415926;
const float ANTIALIASING = 2.0;
const float BLEND_EDGE_FADE_RANGE_X = 0.0;
const float BLEND_EDGE_FADE_RANGE_Y = 5.0;
const float BLEND_SHAPE_CHANGE = 5.0;
const float BLEND_EDGE_FADE = 50.0;
const float BLEND_STRENGTH_SMOOTH = 26.0;
const float BLEND_STRENGTH = 25.0;
const float BLEND_COLOR_SPEED_PIXEL = 20.0;
const float BLEND_COLOR_RANGE = 50.0;
const float BLEND_COLOR_FADE = 0.883;
const float BLEND_PROCESS_CONTROL = 0.3;
const float BLEND_ARC_END = 0.85;
const float BLEND_ARC_START = 1.0;
const float BLEND_ARC_RANGE = 12.5;
float RBox(float2 p,float2 b,float r){
float2 q=abs(p)-b+r;
return min(max(q.x,q.y),0.)+length(max(q,0.))-r;
}
struct ShapeCtx{
float2 halfSize;
float weight;
float r;
bool isVert;
float2 hs;
float a;
float kMaxEff;
float xBandEff;
float yBandEff;
float transitionPixels;
}
;
const float DEFAULT_TRANSITION_PIXELS = 4.0;
const float kAbs = 1.0;
const float rRef = 80.0;
const float kMax = 26.6;
const float junctionXBand = 70.0;
const float junctionYBand = 60.0;
const float junctionPower = 1.0;
float smoothAbs(float x, float k) {
return sqrt(x * x + k * k);
}
float smoothMaxSqrt(float a, float b, float k) {
float d = a - b;
return 0.5 * (a + b + sqrt(d * d + k * k));
}
ShapeCtx shapeCtxFromValues(float2 halfSize, float weight, float r, float isVert,float2 hs, float a, float kMaxEff,float xBandEff, float yBandEff, float transitionPixels) {
ShapeCtx ctx;
ctx.halfSize = halfSize;
ctx.weight = weight;
ctx.r = r;
ctx.isVert = isVert > 0.5;
ctx.hs = hs;
ctx.a = a;
ctx.kMaxEff = kMaxEff;
ctx.xBandEff = xBandEff;
ctx.yBandEff = yBandEff;
ctx.transitionPixels = transitionPixels;
return ctx;
}
ShapeCtx shapeCtxFromArray(float data[12]) {
return shapeCtxFromValues(float2(data[0], data[1]), data[2], data[3], data[4],float2(data[5], data[6]), data[7], data[8],data[9], data[10], data[11]);
}
ShapeCtx shadowShapeCtxFromArray(float data[24], int offset) {
// 调用只使用两组固定槽位; 常量下标同时支持不含 ES3 特性的表面 Shader。
if (offset == 0) {
return shapeCtxFromValues(float2(data[0], data[1]), data[2], data[3], data[4], float2(data[5], data[6]), data[7], data[8], data[9], data[10], data[11]);
}
return shapeCtxFromValues(float2(data[12], data[13]), data[14], data[15], data[16], float2(data[17], data[18]), data[19], data[20], data[21], data[22], data[23]);
}
float sdCapsule(float2 pLocal, ShapeCtx ctx, inout float zoneCap) {
float2 pl = ctx.isVert ? pLocal.yx : pLocal;
float2 q = float2(smoothAbs(pl.x, kAbs), smoothAbs(pl.y, kAbs));
float r = ctx.hs.y;
zoneCap = abs(pl.x) - ctx.a;
float s = q.x - ctx.a;
float xHard = max(s, 0.0);
float xSoft = (ctx.kMaxEff > 0.0) ? smoothMaxSqrt(s, 0.0, ctx.kMaxEff) : xHard;
float wx = 1.0 - smoothstep(0.0, max(ctx.xBandEff, 1e-3), abs(s));
float wy = smoothstep(r - max(ctx.yBandEff, 1e-3), r, q.y);
float w = clamp(wx * wy, 0.0, 1.0);
float x = mix(xHard, xSoft, w);
return length(float2(x, q.y)) - r;
}
float powFast(float x, float n) {
x = max(x, 1e-8);
return exp2(log2(x) * n);
}
float lpNorm(float2 v, float n) {
v = abs(v);
if (n < 2.0 + EPS) {
return length(v);
}
float m = max(v.x, v.y);
if (m < 1e-8) return 0.0;
float2 u = v / m;
float s = powFast(u.x, n) + powFast(u.y, n);
return m * exp2(log2(max(s, EPS)) / n);
}
float sdRoundRect(float2 p, float2 halfSize, float r, float n, inout float2 q) {
q = abs(p) - halfSize + float2(r);
float dCorner = lpNorm(max(q, 0.0), n);
if (q.x > 0.0 && q.y > 0.0) return dCorner - r;
else return max(q.x, q.y) - r;
}
float smoothSdfD(float2 p, ShapeCtx ctx, inout float aaFactor){
float rectSdf = 0.0;
float cornerZone = 0.0;
float2 qRect = float2(0.0, 0.0);
float transitionPixels = DEFAULT_TRANSITION_PIXELS;
if (ctx.weight  < 2.0) {
transitionPixels = ctx.transitionPixels;
float zoneCap = 0.0;
if(ctx.weight  > 1.005){
float t = smoothstep(2.0, 1.0, ctx.weight);
float dG2 = sdRoundRect( p, ctx.halfSize, ctx.r, 2.0, qRect);
float zoneG2 = min(qRect.x, qRect.y);
float dCapsule = sdCapsule( p, ctx, zoneCap);
rectSdf = mix(dG2, dCapsule, t);
cornerZone = mix(zoneG2, zoneCap, t);
}
 else {
rectSdf = sdCapsule( p, ctx, zoneCap);
cornerZone = zoneCap;
}
}
 else {
rectSdf = sdRoundRect( p, ctx.halfSize, ctx.r, ctx.weight, qRect);
cornerZone = min(qRect.x, qRect.y);
}
aaFactor = smoothstep(-transitionPixels - 2.0, -transitionPixels * 0.2, cornerZone);
return rectSdf;
}
"""

private const val FloatingPairFunctions = """
float gain(float x, float k) {
float a = 0.5*pow(2.0*((x<0.5)?x:1.0-x), k);
return (x<0.5)?a:1.0-a;
}
void blendPair(float2 p,float2 point1, float2 point2,RectCorner rc1, RectCorner rc2,ShapeCtx shapeCtx1, ShapeCtx shapeCtx2,inout float raw1, inout float raw2,inout float sdf1, inout float sdf2,float2 invScale1, float2 invScale2,inout float minDist,float pre[24],inout float colorRangeC,inout float aaFactor){
if(!all(greaterThanEqual(float4(rc1.halfSize, rc2.halfSize), float4(EPS))) || pre[21] < 0.5)return;
float preShapeChange = pre[20];
float2 pt1 = point1, pt2 = point2;
float distance1 = raw1, distance2 = raw2;
if(preShapeChange > EPS){
float2 prePoint = float2(pre[0], pre[1]);
float2 preDir = float2(pre[2], pre[3]);
float preParaShapeY1 = pre[9], preParaShapeX1 = pre[10], preParaShapeStrength1 = pre[11];
float preParaShapeY2 = pre[12], preParaShapeX2 = pre[13], preParaShapeStrength2 = pre[14];
float preArc = pre[15];
float2 pointDir = p - prePoint;
float distanceDirY = abs(dot(pointDir, preDir));
float distanceDirX = abs(dot(pointDir, float2(preDir.y, -preDir.x)));
float rangeDistance1 = smoothstep(preParaShapeY1, 0.0, distanceDirY) * gain(smoothstep(preParaShapeX1, 0.0, distanceDirX), preArc);
float smoothStrength1 = preParaShapeStrength1 * rangeDistance1;
if(smoothStrength1 > EPS){
pt1 += -preDir * smoothStrength1 * invScale1;
aaFactor = max(aaFactor, rangeDistance1);
float aaFactor1 = 0.0;
distance1 = smoothSdfD(pt1, shapeCtx1, aaFactor1);
aaFactor = max(aaFactor1,aaFactor);
}
float rangeDistance2 = smoothstep(preParaShapeY2, 0.0, distanceDirY) * gain(smoothstep(preParaShapeX2, 0.0, distanceDirX), preArc);
float smoothStrength2 = preParaShapeStrength2 * rangeDistance2;
if(smoothStrength2 > EPS){
pt2 += preDir * smoothStrength2 * invScale2;
aaFactor = max(aaFactor, rangeDistance2);
float aaFactor2 = 0.0;
distance2 = smoothSdfD(pt2, shapeCtx2, aaFactor2);
aaFactor = max(aaFactor2,aaFactor);
}
}
float2 preRectCenter = float2(pre[4], pre[5]);
float2 preRectHalfSize = float2(pre[6], pre[7]);
float preMinCorner = pre[8];
float preParaEdge12 = pre[16], preParaBlendStrength12 = pre[17];
float preDiffBetween = pre[18];
colorRangeC = pre[19];
float shapeDistance = RBox(p - preRectCenter, preRectHalfSize, preMinCorner);
float mask = BLEND_EDGE_FADE > EPS ? (1.0 - smoothstep(BLEND_EDGE_FADE, 0.0, abs(shapeDistance)) * preParaEdge12) : 1.0;
float finalBlendStrength = BLEND_STRENGTH_SMOOTH > EPS ?  preParaBlendStrength12 : (BLEND_STRENGTH * step(0.0, preDiffBetween));
finalBlendStrength = finalBlendStrength * mask;
float blendedDistance;
if(finalBlendStrength > EPS){
float h = clamp(0.5 + 0.5 * (distance1 - distance2) / finalBlendStrength, 0.0, 1.0);
blendedDistance = mix(distance1, distance2, h) - finalBlendStrength * h * (1.0 - h);
aaFactor = max(max(aaFactor,  h * (1.0 - h) * 4.0), 1.0);
float s1 = smoothstep(0.0, finalBlendStrength, distance2 - distance1 - finalBlendStrength);
float sdfBehindHalf = blendedDistance*(1.0 - s1)+distance2*s1;
sdf2 =  min(sdf2, sdfBehindHalf);
}
else{
blendedDistance = min(distance1, distance2);
sdf2 = min(sdf2, distance2);
}
sdf1 = min(sdf1, distance1);
minDist = min(minDist, blendedDistance);
}
"""

private const val FloatingLightingFunctions = CouiEdgeColorFunctions + """
float random(float2 st){
return fract(sin(dot(st.yx,float2(79.1214,78.233)))*43760.5453);
}
float softLight(float A,float B){
float l=B-(1.-2.*A)*B*(1.-B);
float h=B+(2.*A-1.)*(sqrt(B)-B);
return mix(l,h,step(.5,A));
}
float getShadowAlpha(float fadeLine, float fadeSdf, float d, float para1, float para2){
float alpha = smoothstep(fadeLine,0.0,fadeSdf)*smoothstep(fadeLine, 0.0, d);
if(para2>EPS){
alpha = pow(alpha, para1)*para2+(1.0-para2)*alpha;
}
return alpha;
}
half4 blendShadowColor(float2 coords, CommonDataRect rectData, half4 color, float d, bool useMenuLightEdge){
float3 uFadeAlpha = float3(u_shadowArray[3], u_shadowArray[4], u_shadowArray[5]);
float fadeAlpha = 0.0;
vec3 fadeAlphaVec3 = uFadeAlpha;
float gray = clamp((dot(vec3(0.2126, 0.7152, 0.0722), color.rgb) - 0.6) * 1.6, 0.0, 1.0);
fadeAlphaVec3 =  fadeAlphaVec3*(1.0 - gray)*(1.0-gray);
if(all(lessThanEqual(fadeAlphaVec3, vec3(EPS)))){
return color;
}
if(any(greaterThan(fadeAlphaVec3.xy, float2(EPS)))){
float uFadeIn = u_shadowArray[6];
float2 uFadeOffset = float2(u_shadowArray[7], u_shadowArray[8]);
float uFadeInPara1 =  u_shadowArray[11];
float uFadeInPara2 =  u_shadowArray[12];
float2 posNew = (rectData.pos-uFadeOffset);
float fadeLine = uFadeIn*-1.0;
float fadeSdf = fadeLine;
float2 halfSizeFade = float2(u_shadowArray[16], u_shadowArray[17]);
float fadeConerX = u_shadowArray[18];
if(rectData.weight<2.0 || any(greaterThan(abs(posNew), float2(u_shadowArray[24], u_shadowArray[25])))){
float aaFactor = 0.0;
fadeSdf = smoothSdfD(posNew, shadowShapeCtxFromArray(u_shadowShapeCtxArray, 0), aaFactor);
float alpha = getShadowAlpha(fadeLine, fadeSdf, d, uFadeInPara1, uFadeInPara2);
alpha = alpha*(fadeAlphaVec3.x-fadeAlphaVec3.y) + fadeAlphaVec3.y ;
  fadeAlpha = alpha;
}
else{
fadeAlpha = fadeAlphaVec3.y;
}
}
if(fadeAlphaVec3.z>EPS){
float uFadeOut = u_shadowArray[13];
float uFadeOutPara1 =  u_shadowArray[14];
float uFadeOutPara2 = u_shadowArray[15];
float fadeConerY = u_shadowArray[20];
float fadeLine =  uFadeOut *-1.0;
float fadeSdf = fadeLine;
if(rectData.weight<2.0 || any(greaterThan(abs(rectData.pos), float2(u_shadowArray[26], u_shadowArray[27])))){
if(u_shadowArray[22] > 0.5){
fadeSdf = d;
}
else{
float aaFactor = 0.0;
fadeSdf = smoothSdfD(rectData.pos, shadowShapeCtxFromArray(u_shadowShapeCtxArray, 12), aaFactor);
}
float alpha = getShadowAlpha(fadeLine, fadeSdf, d, uFadeOutPara1, uFadeOutPara2);
alpha *= fadeAlphaVec3.z ;
fadeAlpha = mix(fadeAlpha, 1.0,alpha);
}
}
if(fadeAlpha > EPS){
float2 uvPos=coords.xy*0.000925;
float n=clamp((random(uvPos)-.5)*0.008+.5,0.,1.);
fadeAlpha=clamp(softLight(n,fadeAlpha),0.0,1.0);
}
float3 uFadeColor = float3(u_shadowArray[0], u_shadowArray[1], u_shadowArray[2]);
half4 fadeColor = half4(uFadeColor, fadeAlpha);
// 菜单式内晕按覆盖率混色，避免直接叠亮后截断高光。
if (!useMenuLightEdge) fadeColor.rgb += color.rgb;
return half4(clamp(mix(color.rgb, fadeColor.rgb, fadeColor.a), 0.0,1.0),1.0);
}
float getDis(float2 pos,float2 halfsize){
float2 rect=abs(pos-halfsize);
return rect.x+rect.y;
}
half4 blendEdge(CommonDataRect rectData, half4 color, half4 rawColor, float d, bool useMenuLightEdge){
float2 uRectLineAlpha = float2(u_edgeArray[7], u_edgeArray[8]);
if(all(lessThanEqual(uRectLineAlpha, float2(EPS)))){
return color;
}
float2 uRectLineNear = float2(u_edgeArray[3], u_edgeArray[4]);
float2 uRectLineFar = float2(u_edgeArray[5], u_edgeArray[6]);
if(d<-u_edgeArray[27]){
return color;
}
float3 uRectLineColor = float3(u_edgeArray[0], u_edgeArray[1], u_edgeArray[2]);
float uRectLinePara1 = u_edgeArray[14];
float uRectLinePara2 = u_edgeArray[15];
float distanceAll = u_edgeArray[16];
float2 halfSize = rectData.halfSize;
float2 dirPos= normalize(rectData.pos);
float2 dirAbsPos = abs(dirPos);
float2 posN = min(float2(dirAbsPos.xy*halfSize.yx/max(float2(.001),dirAbsPos.yx)),halfSize)*sign(dirPos);
float2 halfSizeSymbol = float2(u_edgeArray[24], u_edgeArray[25]);
float flag = u_edgeArray[19]*dot(halfSizeSymbol,posN);
float disPoint = u_edgeArray[18];
float disPos = getDis(posN,halfSize);
float distanceNear = mix(disPoint+disPos,abs(disPoint-disPos),step(0.,flag));
distanceNear=min(u_edgeArray[26]-distanceNear,distanceNear);
float lineNearAlpha = uRectLineAlpha.x*smoothstep(u_edgeArray[20], u_edgeArray[21], distanceNear);
float lineFarAlpha = uRectLineAlpha.y*smoothstep(u_edgeArray[22], u_edgeArray[23], distanceAll-distanceNear);
float lineNearSdf=smoothstep(-uRectLineNear.y,-uRectLineNear.x,d);
float lineFarSdf=smoothstep(-uRectLineFar.y,-uRectLineFar.x,d);
if(uRectLinePara2 > EPS){
lineNearSdf = pow(lineNearSdf, uRectLinePara1)*uRectLinePara2+(1.0-uRectLinePara2)*lineNearSdf;
lineFarSdf = pow(lineFarSdf, uRectLinePara1)*uRectLinePara2+(1.0-uRectLinePara2)*lineFarSdf;
}
lineNearAlpha*=lineNearSdf;
lineFarAlpha*=lineFarSdf;
float lineAlpha = clamp(lineNearAlpha+lineFarAlpha,0.,1.);
if (useMenuLightEdge) rawColor = lightEdgeReferenceColor(rawColor);
return mix(color, couiEdgeColor(rawColor, uRectLineColor, useMenuLightEdge), lineAlpha);
}

"""

private const val FloatingContourMain = """
uniform float u_commonArray[8];
uniform float u_baseShapeCtxArray[12];
uniform float4 uSurfaceColor;
uniform float uEffectAlpha;
half4 main(float2 fragCoord) {
    CommonDataRect rectData;
    float2 coords = float2(fragCoord.x, u_commonArray[1] * 2.0 - fragCoord.y);
    rectData.halfResolution = float2(u_commonArray[0], u_commonArray[1]);
    rectData.pos = coords - rectData.halfResolution;
    rectData.halfSize = float2(u_commonArray[4], u_commonArray[5]);
    rectData.invScale = float2(1.0);
    rectData.coner = u_commonArray[6];
    rectData.weight = u_commonArray[7];
    float aaFactor = 0.0;
    float d = smoothSdfD(rectData.pos, shapeCtxFromArray(u_baseShapeCtxArray), aaFactor);
    half4 surface = half4(uSurfaceColor.rgb, 1.0);
    half4 color = blendShadowColor(coords, rectData, surface, d, false);
    if (dot(rectData.pos, rectData.pos) >= EPS) {
        color = blendEdge(rectData, color, surface, d, false);
    }
    color.rgb = mix(surface.rgb, clamp(color.rgb, 0.0, 1.0), uEffectAlpha);
    return half4(color.rgb * uSurfaceColor.a, uSurfaceColor.a);
}
"""

private const val FloatingSdf = """
ShapeResult getRoundedMetaballSDF(float2 p, CommonDataRect rectData, inout float aaFactor) {
RectCorner rc1 = RectCorner(float2(u_ball1Array[0], u_ball1Array[1]), float2(u_ball1Array[2], u_ball1Array[3]), u_ball1Array[4], u_ball1Array[5]);
RectCorner rc2 = RectCorner(float2(u_ball2Array[0], u_ball2Array[1]), float2(u_ball2Array[2], u_ball2Array[3]), u_ball2Array[4], u_ball2Array[5]);
RectCorner rc3 = RectCorner(float2(u_ball3Array[0], u_ball3Array[1]), float2(u_ball3Array[2], u_ball3Array[3]), u_ball3Array[4], u_ball3Array[5]);
float2 ball1InvScale = float2(u_ball1Array[6], u_ball1Array[7]);
float2 ball2InvScale = float2(u_ball2Array[6], u_ball2Array[7]);
float2 ball3InvScale = float2(u_ball3Array[6], u_ball3Array[7]);
float2 rp1 = (p - rc1.center) * ball1InvScale, rp2 = (p - rc2.center) * ball2InvScale, rp3 = (p - rc3.center) * ball3InvScale;
ShapeCtx shapeCtx1 = shapeCtxFromArray(u_ball1ShapeCtxArray);
ShapeCtx shapeCtx2 = shapeCtxFromArray(u_ball2ShapeCtxArray);
ShapeCtx shapeCtx3 = shapeCtxFromArray(u_ball3ShapeCtxArray);
float sdfIndex1 = 100.0, sdfIndex2 = 100.0, sdfIndex3 = 100.0;
float rawSdf1 = 100.0, rawSdf2 = 100.0, rawSdf3 = 100.0;
float minDistance = 100.0;
float range12 = 0.0, range13 = 0.0, range23 = 0.0;
const float2 eps2 = float2(EPS);
if(all(greaterThanEqual(rc1.halfSize, eps2))){
float aaFactor1 = 0.0;
sdfIndex1 = smoothSdfD(rp1, shapeCtx1, aaFactor1);
if(MATERIAL_INDEX == 1) aaFactor = aaFactor1;
rawSdf1 = sdfIndex1;
minDistance = min(sdfIndex1, minDistance);
}
if(all(greaterThanEqual(rc2.halfSize, eps2))){
float aaFactor2 = 0.0;
sdfIndex2 = smoothSdfD(rp2, shapeCtx2, aaFactor2);
if(MATERIAL_INDEX == 2) aaFactor = aaFactor2;
rawSdf2 = sdfIndex2;
minDistance = min(sdfIndex2, minDistance);
}
if(all(greaterThanEqual(rc3.halfSize, eps2))){
float aaFactor3 = 0.0;
sdfIndex3 = smoothSdfD(rp3, shapeCtx3, aaFactor3);
if(MATERIAL_INDEX == 3) aaFactor = aaFactor3;
rawSdf3 = sdfIndex3;
minDistance = min(sdfIndex3, minDistance);
}
blendPair(p, rp1, rp2, rc1, rc2, shapeCtx1, shapeCtx2, rawSdf1, rawSdf2, sdfIndex1, sdfIndex2, ball1InvScale, ball2InvScale, minDistance, u_preCmpArray12, range12, aaFactor);
blendPair(p, rp1, rp3, rc1, rc3, shapeCtx1, shapeCtx3, rawSdf1, rawSdf3, sdfIndex1, sdfIndex3, ball1InvScale, ball3InvScale, minDistance, u_preCmpArray13, range13, aaFactor);
blendPair(p, rp2, rp3, rc2, rc3, shapeCtx2, shapeCtx3, rawSdf2, rawSdf3, sdfIndex2, sdfIndex3, ball2InvScale, ball3InvScale, minDistance, u_preCmpArray23, range23, aaFactor);
float mixC;
ShapeResult sr;
float aa = max(EPS, mix(EPS, fwidth(minDistance), aaFactor));
mixC = smoothstep(aa, -aa, minDistance);
float antiAa = ANTIALIASING+aa;
float epsAa = aa+EPS;
sr.sdf = minDistance;

"""

private const val FloatingColor = """
if(shape < EPS){
return half4(0.0);
}
half4 originCol = u_originShader.eval(min(max(position, float2(2.0)), u_size));
half4 outputCol = clamp(vec4(originCol.rgb/max(originCol.a, EPS), 1.0), 0.0, 1.0);
half4 rawCol = outputCol;
outputCol = mix(outputCol, half4(u_color.rgb, 1.0), u_color.a);
bool useMenuLightEdge = uUseMenuLightEdge > 0.5;
outputCol = blendShadowColor(coords, rectData, outputCol, sdf, useMenuLightEdge);
outputCol = blendEdge(rectData, outputCol, rawCol, sdf, useMenuLightEdge);
outputCol = clamp(outputCol, 0.0, 1.0);
// 只染材质与背景光效, 在覆盖率之前处理, 不填充透明区域或影响前景。
if(u_themeTint.a > 0.0){
outputCol.rgb = mix(outputCol.rgb, luminosity(outputCol.rgb, u_themeTint.rgb), u_themeTint.a);
}
outputCol *= shape*originCol.a;
outputCol.rgb = clamp(outputCol.rgb, vec3(0.0), vec3(outputCol.a));
return outputCol * u_alpha;
}

"""

private const val FloatingMain = """
half4 main(float2 position) {
float2 coords = float2(position.x, u_commonArray[10]-position.y);
CommonDataRect rectData = getCommonDataRect(coords);
float aaFactor = 1.0;
ShapeResult shapeResult = getRoundedMetaballSDF(coords, rectData, aaFactor);
float shape = shapeResult.mixC;
float sdf = shapeResult.sdf;
""" + FloatingColor

private const val FloatingStandaloneMain = """
half4 main(float2 position) {
float2 coords = float2(position.x, u_commonArray[10]-position.y);
CommonDataRect rectData = getCommonDataRect(coords);
float aaFactor = 1.0;
float sdf = smoothSdfD(rectData.pos, shapeCtxFromArray(u_baseShapeCtxArray), aaFactor);
float aa = max(EPS, mix(EPS, fwidth(sdf), aaFactor));
float shape = smoothstep(aa, -aa, sdf);
""" + FloatingColor

private const val FloatingIndex1 = """
float inLayer3ForBall1 = 1.0 - smoothstep(aa, range13 + epsAa, sdfIndex3);
float inLayer2ForBall1 = 1.0 - smoothstep(aa, range12 + epsAa, sdfIndex2);
float rangeWhenInLayer3ForBall1 = mix(range13, max(range13, range12), inLayer2ForBall1);
float colorRangeValue1 = mix(range12, rangeWhenInLayer3ForBall1, inLayer3ForBall1);
colorRangeValue1 = max(BLEND_COLOR_RANGE * colorRangeValue1, antiAa);
float mixC1 = pow(smoothstep(colorRangeValue1, aa, sdfIndex1), BLEND_COLOR_FADE);
mixC1 *= mixC;
sr.mixC = mixC1;

"""

private const val FloatingIndex2 = """
float colorRangeValue2 = max(BLEND_COLOR_RANGE * range23, antiAa);
float mixC2 = pow(smoothstep(colorRangeValue2, aa, sdfIndex2), BLEND_COLOR_FADE) * step(aa, sdfIndex1);
mixC2 *= mixC;
sr.mixC = mixC2;

"""

private const val FloatingIndex3 = """
float mixC3 = step(sdfIndex3, aa) * step(aa, sdfIndex2) * step(aa, sdfIndex1);
mixC3 *= mixC;
sr.mixC = mixC3;

"""

private const val FloatingRect = """
CommonDataRect getCommonDataRect(float2 coords) {
CommonDataRect rectData;
rectData.halfResolution = vec2(u_commonArray[0], u_commonArray[1]);

"""

private const val FloatingStandaloneRect = """
rectData.invScale = vec2(u_commonArray[8], u_commonArray[9]);
rectData.pos = (coords.xy - vec2(u_commonArray[2], u_commonArray[3])) * rectData.invScale;
rectData.halfSize = vec2(u_commonArray[4], u_commonArray[5]);
rectData.coner = u_commonArray[6];
rectData.weight = u_commonArray[7];
"""

private const val FloatingRect1 = """
rectData.invScale = vec2(u_ball1Array[6], u_ball1Array[7]);
rectData.pos = (coords.xy - vec2(u_ball1Array[0], u_ball1Array[1])) * rectData.invScale;
rectData.halfSize =  vec2(u_ball1Array[2], u_ball1Array[3]);
rectData.coner =  u_ball1Array[4];
rectData.weight = u_ball1Array[5];

"""

private const val FloatingRect2 = """
rectData.invScale = vec2(u_ball2Array[6], u_ball2Array[7]);
rectData.pos = (coords.xy - vec2(u_ball2Array[0], u_ball2Array[1])) * rectData.invScale;
rectData.halfSize =  vec2(u_ball2Array[2], u_ball2Array[3]);
rectData.coner =  u_ball2Array[4];
rectData.weight = u_ball2Array[5];

"""

private const val FloatingRect3 = """
rectData.invScale = vec2(u_ball3Array[6], u_ball3Array[7]);
rectData.pos = (coords.xy - vec2(u_ball3Array[0], u_ball3Array[1])) * rectData.invScale;
rectData.halfSize =  vec2(u_ball3Array[2], u_ball3Array[3]);
rectData.coner =  u_ball3Array[4];
rectData.weight = u_ball3Array[5];

"""
