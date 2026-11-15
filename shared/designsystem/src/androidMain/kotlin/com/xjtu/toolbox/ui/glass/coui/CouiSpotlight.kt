package com.xjtu.toolbox.ui.glass.coui

import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled
import kotlin.math.PI
import kotlin.math.pow

/** 原厂跟手光预设; 半径及光源高度使用设备像素, 不随 density 换算。 */
enum class SpotlightPreset(
    internal val lightIntensity: Float,
    internal val darkIntensity: Float,
    internal val endRadius: Float,
    internal val darkBlendMode: BlendMode = BlendMode.OVERLAY,
    internal val lightBlendMode: BlendMode = BlendMode.OVERLAY,
    internal val ambient: Float = 0.4f,
    internal val diffuse: Float = 0.4f,
    internal val noise: Float = 0.08f,
) {
    TranslucentLarge1(0.6f, 0.1f, 800f),
    OpaqueSmall1(0.6f, 0.5f, 500f),
    OpaqueMedium1(0.6f, 0.8f, 500f),
    OpaqueSmall2(1f, 0.15f, 500f, BlendMode.PLUS),
    OpaqueMedium2(1f, 0.15f, 500f, BlendMode.PLUS),
    ChipTab(0.2f, 0.3f, 600f, BlendMode.SRC_OVER, BlendMode.PLUS, 0.1f, 0.5f, 0f),
}

private data class SpotlightStyle(
    val intensity: Float,
    val ambient: Float,
    val diffuse: Float,
    val lightHeight: Float,
    val noise: Float,
    val dither: Float,
    val blendMode: BlendMode = BlendMode.OVERLAY,
    val startRadius: Float = 100f,
    val endRadius: Float = 800f,
    val color: Color = Color.White,
)

sealed interface SpotlightDiagnostic {
    data object Pressed : SpotlightDiagnostic
    data class Released(val progress: Float) : SpotlightDiagnostic
    data class FirstDraw(val hardware: Boolean, val size: IntSize, val progress: Float) : SpotlightDiagnostic
}

/**
 * 只观察触摸, 不拦截点击、滑选或滚动。绘制回调应置于透明前景层, 熄灭后为 null。
 * 必须选择预设和深浅色; 可选参数为 null 时继承预设, 非 null 时只覆盖该项。
 * inputTarget 接收本地触点及是否已有光效会话; 返回 null 时取消会话。
 * cancelOnConsumedMove 用于滚动子项：父容器接管后取消当前会话，直到所有手指抬起都不重新点亮。
 */
@Composable
fun rememberCouiSpotlight(
    interactive: Boolean,
    preset: SpotlightPreset,
    dark: Boolean,
    intensity: Float? = null,
    ambient: Float? = null,
    diffuse: Float? = null,
    lightHeight: Float? = null,
    noise: Float? = null,
    dither: Float? = null,
    blendMode: BlendMode? = null,
    startRadius: Float? = null,
    endRadius: Float? = null,
    color: Color? = null,
    inputTarget: ((Offset, Boolean) -> LayoutCoordinates?)? = null,
    cancelOnConsumedMove: Boolean = false,
    onDiagnostic: (SpotlightDiagnostic) -> Unit = {},
): Pair<Modifier, (DrawScope.() -> Unit)?> {
    val style = SpotlightStyle(
        intensity = intensity ?: if (dark) preset.darkIntensity else preset.lightIntensity,
        ambient = ambient ?: preset.ambient,
        diffuse = diffuse ?: preset.diffuse,
        lightHeight = lightHeight ?: 200f,
        noise = noise ?: preset.noise,
        dither = dither ?: 0f,
        blendMode = blendMode ?: if (dark) preset.darkBlendMode else preset.lightBlendMode,
        startRadius = startRadius ?: 100f,
        endRadius = endRadius ?: preset.endRadius,
        color = color ?: Color.White,
    )
    return runtimeSpotlight(interactive, style, inputTarget, cancelOnConsumedMove, onDiagnostic)
}

@Composable
private fun runtimeSpotlight(
    interactive: Boolean,
    style: SpotlightStyle,
    inputTarget: ((Offset, Boolean) -> LayoutCoordinates?)?,
    cancelOnConsumedMove: Boolean,
    onDiagnostic: (SpotlightDiagnostic) -> Unit,
): Pair<Modifier, (DrawScope.() -> Unit)?> {
    val diagnostic = rememberUpdatedState(onDiagnostic)
    val currentInputTarget by rememberUpdatedState(inputTarget)
    val light = remember(style.startRadius, style.endRadius) {
        SpotlightState(style.startRadius, style.endRadius) { diagnostic.value(it) }
    }
    DisposableEffect(light) {
        onDispose { light.dispose() }
    }
    val visible by remember(light) {
        derivedStateOf(structuralEqualityPolicy()) { light.progress > 0f }
    }
    val shader = remember { RuntimeShader(SpotlightShader) }
    val paint = remember(style.blendMode) { Paint().apply { blendMode = style.blendMode } }
    var inputCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val touchModifier = Modifier
        .onGloballyPositioned { inputCoordinates = it }
        .onSizeChanged { if (inputTarget == null) light.setSize(it) }
        .pointerInput(light, interactive, inputTarget == null, cancelOnConsumedMove) {
            if (!interactive) return@pointerInput
            try {
                awaitPointerEventScope {
                    var activePointer: PointerId? = null
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.isCouiTouchCancelled()) {
                            activePointer = null
                            light.release()
                            cancelled = false
                            continue
                        }
                        if (cancelled) {
                            if (event.changes.none { it.pressed }) cancelled = false
                            continue
                        }
                        val wasIdle = activePointer == null
                        val newPointer = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        val tracked = event.changes.firstOrNull { it.id == activePointer }
                        // 与 drawable 一致: 新按下的手指接管光源, 抬起后交回仍按下的首指。
                        val change = if (newPointer != null) {
                            newPointer
                        } else if (tracked != null && !tracked.pressed) {
                            event.changes.firstOrNull { it.pressed } ?: tracked
                        } else {
                            tracked ?: if (currentInputTarget != null) event.changes.firstOrNull { it.pressed } else null
                        } ?: continue
                        val target = currentInputTarget?.invoke(change.position, !wasIdle)
                        if (currentInputTarget != null && target?.isAttached != true) {
                            light.release()
                            activePointer = null
                            continue
                        }
                        val bounds = target?.size ?: size
                        if (bounds.width <= 0 || bounds.height <= 0) continue
                        val localPosition = if (target != null) {
                            val input = inputCoordinates?.takeIf { it.isAttached } ?: continue
                            target.localPositionOf(input, change.position)
                        } else change.position
                        light.setSize(bounds)
                        val position = Offset(
                            localPosition.x / bounds.width,
                            localPosition.y / bounds.height,
                        )
                        light.move(position)
                        activePointer = change.id
                        if (wasIdle) light.press()
                        if (!change.pressed) {
                            activePointer = null
                            light.release()
                        }
                        if (cancelOnConsumedMove) {
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            if (activePointer != null && finalEvent.changes.any {
                                    it.isConsumed && it.pressed && it.position != it.previousPosition
                                }) {
                                activePointer = null
                                light.release()
                                cancelled = true
                            }
                        }
                    }
                }
            } finally {
                light.release()
            }
        }
    val drawSpotlight: DrawScope.() -> Unit = {
        val progress = light.progress
        val canvas = drawContext.canvas.nativeCanvas
        if (progress > 0f && size.width > 0f && size.height > 0f) {
            light.reportDraw(canvas.isHardwareAccelerated, IntSize(size.width.toInt(), size.height.toInt()), progress)
        }
        if (progress > 0f && size.width > 0f && size.height > 0f && canvas.isHardwareAccelerated) {
            val position = light.position
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform(
                "u_lightPosAndHeight", position.x * size.width, position.y * size.height,
                light.radius, style.intensity * progress,
            )
            shader.setFloatUniform("u_lightColorAndAmbient", style.color.red, style.color.green, style.color.blue, style.ambient)
            shader.setFloatUniform("u_lightingParams", style.diffuse, style.lightHeight, style.noise, style.dither)
            // 与 COUISpotLightEffectDrawable 一致, 每帧把更新后的 shader 交给独立 Paint。
            paint.shader = shader
            canvas.drawPaint(paint)
        }
    }
    return touchModifier to if (visible) drawSpotlight else null
}

private class SpotlightState(
    private val startRadius: Float,
    private val endRadius: Float,
    private val onDiagnostic: (SpotlightDiagnostic) -> Unit,
) {
    private var reportNextDraw = false
    private var disposed = false
    private var extinguishing = false
    private var bounds = IntSize.Zero
    private val progressValue = FloatValueHolder()
    private val positionXValue = FloatValueHolder()
    private val positionYValue = FloatValueHolder()

    // COUI 的这条路径与 AndroidX 旧版弹簧一致: 持续重定向, 而不是每个 MOVE 重启动画。
    private val positionXAnimation = SpringAnimation(positionXValue).apply {
        spring = createSpring(0.35f, 0.15f)
        setMinimumVisibleChange(0.0001f)
        addUpdateListener { _, value, _ -> position = position.copy(x = value) }
    }
    private val positionYAnimation = SpringAnimation(positionYValue).apply {
        spring = createSpring(0.35f, 0.15f)
        setMinimumVisibleChange(0.0001f)
        addUpdateListener { _, value, _ -> position = position.copy(y = value) }
    }
    private val progressSpring = createSpring(0.15f, 0f)
    private val progressAnimation = SpringAnimation(progressValue).apply {
        spring = progressSpring
        // 保留厂商 0..10000 的原始进度及其终止阈值, 只在绘制时归一化。
        setMinimumVisibleChange(1f)
        addUpdateListener { _, value, _ -> updateProgress(value) }
    }

    var progress by mutableFloatStateOf(0f)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    var radius by mutableFloatStateOf(0f)
        private set

    fun setSize(size: IntSize) {
        bounds = size
    }

    fun move(target: Offset) {
        if (disposed) return
        if (progressValue.value == 0f) {
            positionXValue.value = target.x
            positionYValue.value = target.y
            position = target
        } else {
            positionXAnimation.animateToFinalPosition(target.x)
            positionYAnimation.animateToFinalPosition(target.y)
        }
    }

    fun press() {
        if (disposed) return
        extinguishing = false
        reportNextDraw = true
        onDiagnostic(SpotlightDiagnostic.Pressed)
        progressSpring.stiffness = responseToStiffness(0.15f)
        progressAnimation.animateToFinalPosition(10000f)
    }

    fun release() {
        if (disposed || extinguishing) return
        extinguishing = true
        onDiagnostic(SpotlightDiagnostic.Released(progress))
        progressSpring.stiffness = responseToStiffness(0.4f)
        progressAnimation.animateToFinalPosition(0f)
    }

    fun dispose() {
        // 防止 pointerInput 的 finally 在移除节点后重新启动 Choreographer 回调。
        disposed = true
        progressAnimation.cancel()
        positionXAnimation.cancel()
        positionYAnimation.cancel()
    }

    fun reportDraw(hardware: Boolean, size: IntSize, progress: Float) {
        if (!reportNextDraw) return
        reportNextDraw = false
        onDiagnostic(SpotlightDiagnostic.FirstDraw(hardware, size, progress))
    }

    private fun updateProgress(value: Float) {
        progress = (value / 10000f).coerceIn(0f, 1f)
        if (value == 0f) {
            radius = 0f
            extinguishing = false
            positionXAnimation.skipToEnd()
            positionYAnimation.skipToEnd()
        } else {
            radius = startRadius + (endRadius - startRadius) * progress +
                if (extinguishing && value > 0f) 2f * endRadius * (1f - progress) else 0f
            if (spotLightOutOfBounds()) progressAnimation.skipToEnd()
        }
    }

    private fun spotLightOutOfBounds(): Boolean {
        val x = position.x * bounds.width
        val y = position.y * bounds.height
        return x - radius > bounds.width || x + radius < 0f ||
            y - radius > bounds.height || y + radius < 0f
    }

    private fun createSpring(response: Float, bounce: Float): SpringForce = SpringForce().apply {
        stiffness = responseToStiffness(response)
        dampingRatio = 1f - bounce
    }

    private fun responseToStiffness(response: Float): Float = (2.0 * PI / response).pow(2.0).toFloat()
}

// COUI SpotLight.AGSL_SOURCE, 保留厂商光照和噪声计算。
private const val SpotlightShader = """
uniform vec4 u_lightPosAndHeight;
uniform vec4 u_lightColorAndAmbient;
uniform vec4 u_lightingParams;
uniform vec2 resolution;

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float max3(vec3 v) {
    return max(max(v.r, v.g), v.b);
}

float noise(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float softLight(float A, float B) {
    float l = B - (1.0 - 2.0 * A) * B * (1.0 - B);
    float h = B + (2.0 * A - 1.0) * (sqrt(B) - B);
    return mix(l, h, step(0.5, A));
}

float dither(vec2 coord, float ditherN) {
    float n1 = fract(sin(dot(coord, vec2(12.9898, 78.233))) * 43758.5453);
    float n2 = fract(sin(dot(coord + vec2(1.0), vec2(12.9898, 78.233))) * 43758.5453);
    return (n1 + n2 - 1.0) * ditherN / 255.0;
}

half4 main(float2 fragCoord) {
    float2 lightP = u_lightPosAndHeight.xy;
    float radius = u_lightPosAndHeight.z;
    float intensity = u_lightPosAndHeight.w;

    vec3 lightCol = u_lightColorAndAmbient.rgb;
    float ambientStr = u_lightColorAndAmbient.w;
    float diffuseStr = u_lightingParams.x;
    float lightH = u_lightingParams.y;
    float noise_exp = u_lightingParams.z;
    float ditherN = u_lightingParams.w;
    const float lightIntensity = 1.5;

    vec2 delta = fragCoord - lightP;
    vec3 L = vec3(delta, lightH);
    float dist = length(L);
    L = normalize(L);

    vec3 N = vec3(0.0, 0.0, 1.0);
    float diff = max(dot(N, L), 0.0);
    vec3 lighting = (ambientStr + diffuseStr * diff) * lightCol;
    if (dist >= radius) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }

    float att = smoothstep(1.0, 0.0, dist / radius) * step(dist, radius);
    lighting *= att * lightIntensity * intensity;
    float alpha = clamp(luminance(lighting), 0.0, 1.0);

    float postOn = max(step(0.001, noise_exp), step(0.001, ditherN));
    vec3 postLighting = lighting;
    float postAlpha = alpha;

    float noiseOn = step(0.001, noise_exp);
    vec2 uv_pos = fragCoord * 9.259e-4;
    float n = clamp((noise(uv_pos) - 0.5) * noise_exp + 0.5, 0.0, 1.0);
    float mask = max3(postLighting);
    float fadeAlpha = clamp(softLight(n, mask), 0.0, 1.0);
    float noiseDelta = clamp(abs(fadeAlpha - mask), 0.0, 1.0);
    postLighting += vec3(noiseDelta) * noiseOn;

    float ditherOn = step(0.001, ditherN);
    float d = dither(fragCoord, ditherN);
    postLighting += vec3(d) * ditherOn;
    postAlpha = mix(postAlpha, clamp(postAlpha + d, 0.0, 1.0), ditherOn);

    lighting = mix(lighting, postLighting, postOn);
    alpha = mix(alpha, postAlpha, postOn);
    return half4(lighting, alpha);
}
"""
