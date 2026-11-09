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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.preferredFrameRate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
/** Android 侧的液体背景实现。 */
@Composable
internal actual fun PlatformLiquidBackground(
    modifier: Modifier,
    colors: LiquidColors,
    seed: Float,
    speed: Float,
    crease: Float,
    animated: Boolean,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        FallbackLiquidBackground(modifier, colors)
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val running = animated && LocalPageVisible.current && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // 着色器时间按 30 帧/秒推进：液体本身流得很慢，无限动画只会让整个窗口按 120Hz 白白重合成。
    val seconds = remember { mutableFloatStateOf(0f) }
    if (running) {
        LaunchedEffect(Unit) {
            // 暂停再回来时接着上次的相位走，不从 0 重新开始。
            val base = seconds.floatValue
            val start = TimeSource.Monotonic.markNow()
            while (true) {
                seconds.floatValue = base + start.elapsedNow().inWholeMicroseconds / 1_000_000f
                delay(LIQUID_FRAME_MS)
            }
        }
    }
    RuntimeLiquid(modifier, colors, seed, speed, crease) { seconds.floatValue }
}

private const val LIQUID_FRAME_MS = 33L

/**
 * 站点 shader 的 AGSL 版：fbm 噪声做 domain warping，三色平滑混合，最后压一道暗折痕。
 * 唯一改动是去掉 mask（站点用它把液体裁成 tab 面板的形状），坐标改从 Compose 的左上角起算。
 */
private const val LIQUID_AGSL = """
uniform float2 size;
uniform float time;
uniform float seed;
uniform float2 mouse;
uniform float crease;
uniform float2 zoom;
uniform float speed;
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

half4 main(float2 fragCoord) {
    // 站点用的是 gl_FragCoord（左下角原点），这里翻一下 y 保持同样的流向。
    float2 uv = float2(fragCoord.x / size.x, 1.0 - fragCoord.y / size.y);
    float2 p = (uv - 0.5) * float2(size.x / size.y, 1.0) * 1.6 * zoom + seed;
    float2 d = uv - mouse;
    p += 0.35 * d * exp(-4.0 * dot(d, d));
    float t = time * 0.12 * speed;
    float2 q = float2(fbm(p + t), fbm(p + float2(3.1, 1.7) - t));
    float2 r = float2(
        fbm(p + 2.4 * q + float2(1.7, 9.2) + 0.3 * t),
        fbm(p + 2.4 * q + float2(8.3, 2.8) - 0.2 * t)
    );
    float f = fbm(p + 2.2 * r);
    float k = smoothstep(-0.35, 0.55, f + 0.35 * uv.y - 0.15);
    float3 col = mix(c0, c1, smoothstep(0.0, 0.55, k));
    col = mix(col, c2, smoothstep(0.55, 1.0, k));
    col *= 1.0 - 0.55 * crease * smoothstep(0.04, 0.0, abs(f + 0.1));
    return half4(half3(col), half(1.0));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RuntimeLiquid(
    modifier: Modifier,
    colors: LiquidColors,
    seed: Float,
    speed: Float,
    crease: Float,
    seconds: () -> Float,
) {
    val shader = remember { RuntimeShader(LIQUID_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(
        modifier
            .fillMaxSize()
            .preferredFrameRate(FrameRateCategory.Normal)
            // 画进硬件缓存层：滚动时卡片只是平移，直接复用缓存好的像素，着色器只在自己 30 帧/秒的
            // 节拍上重算。不加的话这道逐像素 fbm 会跟着 120Hz 的滚动每帧整卡重算一遍（首页滑动掉帧的大头）
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("seed", seed)
                // 手机上不跟指针：稳态的鼠标放在中心，只留一点边缘扰动。
                shader.setFloatUniform("mouse", 0.5f, 0.5f)
                shader.setFloatUniform("crease", crease)
                shader.setFloatUniform("zoom", 1f, 1f)
                shader.setFloatUniform("speed", speed)
                shader.setFloatUniform("c0", colors.dark.red, colors.dark.green, colors.dark.blue)
                shader.setFloatUniform("c1", colors.mid.red, colors.mid.green, colors.mid.blue)
                shader.setFloatUniform("c2", colors.light.red, colors.light.green, colors.light.blue)
                onDrawBehind {
                    shader.setFloatUniform("time", seconds())
                    drawRect(brush)
                }
            },
    )
}
