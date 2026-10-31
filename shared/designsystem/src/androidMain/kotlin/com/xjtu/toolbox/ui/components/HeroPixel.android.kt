package com.xjtu.toolbox.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

@Composable
actual fun HeroPixelBackground(modifier: Modifier, animated: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        CpuHeroPixelBackground(modifier)
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val running = animated && LocalPageVisible.current && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val seconds = remember { mutableFloatStateOf(0f) }
    if (running) {
        LaunchedEffect(Unit) {
            val base = seconds.floatValue
            val start = TimeSource.Monotonic.markNow()
            while (true) {
                seconds.floatValue = base + start.elapsedNow().inWholeMicroseconds / 1_000_000f
                delay(PIXEL_FRAME_MS)
            }
        }
    }
    RuntimePixel(modifier) { seconds.floatValue }
}

private const val PIXEL_FRAME_MS = 33L

/** 站点 dither shader 的 AGSL 版；坐标从 Compose 左上角起算，其余逐行照搬。 */
private const val PIXEL_AGSL = """
uniform float2 size;
uniform float time;
uniform float seed;
uniform float crease;
uniform float speed;
uniform float cell;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;

float2 hash2(float2 p) {
    p = float2(dot(p, float2(127.1, 311.7)), dot(p, float2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = dot(hash2(i) - 0.5, f);
    float b = dot(hash2(i + float2(1.0, 0.0)) - 0.5, f - float2(1.0, 0.0));
    float c = dot(hash2(i + float2(0.0, 1.0)) - 0.5, f - float2(0.0, 1.0));
    float d = dot(hash2(i + float2(1.0, 1.0)) - 0.5, f - float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 2.0;
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.02 + 7.3;
        a *= 0.5;
    }
    return v;
}

float ign(float2 px) {
    return fract(52.9829189 * fract(0.06711056 * px.x + 0.00583715 * px.y));
}

half4 main(float2 fragCoord) {
    // 站点用左下角原点；翻一下 y，渐变和像素格方向才一致。
    float2 gl = float2(fragCoord.x, size.y - fragCoord.y);
    float2 ci = floor(gl / cell);
    float2 uv = ((ci + 0.5) * cell) / size;
    float2 p = (uv - 0.5) * float2(size.x / size.y, 1.0) * 1.6 + seed;
    float t = time * 0.12 * speed;
    float2 q = float2(fbm(p + t), fbm(p + float2(3.1, 1.7) - t));
    float f = fbm(p + 2.2 * q);
    float k = smoothstep(-0.45, 0.55, f + 0.3 * uv.y - 0.1) * (1.0 + 0.3 * crease) - 0.15 * crease;
    float s = clamp(k, 0.0, 1.0) * 2.0;
    float lvl = floor(s) + step(ign(ci), fract(s));
    float3 col = c0;
    if (lvl >= 0.5) col = c1;
    if (lvl >= 1.5) col = c2;
    return half4(half3(col), half(1.0));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RuntimePixel(modifier: Modifier, seconds: () -> Float) {
    val cell = with(LocalDensity.current) { 1.dp.toPx() }
    val shader = remember { RuntimeShader(PIXEL_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(
        modifier
            .fillMaxSize()
            .preferredFrameRate(FrameRateCategory.Normal)
            .graphicsLayer()
            .drawWithCache {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("seed", 3.7f)
                shader.setFloatUniform("crease", 1f)
                shader.setFloatUniform("speed", 1f)
                shader.setFloatUniform("cell", cell)
                shader.setFloatUniform("c0", 0f, 0f, 0f)
                shader.setFloatUniform("c1", 0.1137f, 0.3059f, 0.8471f)
                shader.setFloatUniform("c2", 0.5765f, 0.7725f, 0.9922f)
                onDrawBehind {
                    shader.setFloatUniform("time", seconds())
                    drawRect(brush)
                }
            },
    )
}
