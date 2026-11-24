package com.xjtu.toolbox.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.toLinearShader
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 首页卡片的玻璃：背景快照 + 每张卡一趟着色器。
 *
 * 以前每张液态卡是一整条离屏流水线（采样层 → 折射 → 高光层 → 模糊投影），滚动时卡片的采样位置每帧都变，
 * 二十来张卡每帧整套重跑：真机一帧 200 多个渲染批次、每帧重新分配显存，GPU 一帧 20ms，滚动一路掉帧。
 *
 * 首页背景（底色 + 壁纸 + 遮罩）是静止的，所以只在它变的时候抓一次快照：一张清晰的、一张降采样后模糊的。
 * 每张卡直接画一个圆角矩形，着色器同时读这两张图——中间取模糊的、边缘带取清晰的并折射，高光也在同一趟里算。
 * 不开离屏层、不逐卡模糊、不分配显存；卡片一动只重画它自己那块背景（单独一层，不连带卡面文字重录）。
 */

/** 模糊副本的分辨率（背景像素 × 它）：糊过的图本来没有细节，降采样存，取样时双线性放大。 */
private const val FROST_SCALE = 0.25f

/** 背景画完后等这么久再抓：壁纸淡入、连续重画时只抓最后一次。 */
private const val CAPTURE_SETTLE_MS = 120L

/** 抓完后隔这么久补抓一次（壁纸淡入 180ms，留足余量）。 */
private const val CAPTURE_FOLLOW_UP_MS = 450L

/**
 * 首页背景的快照与它在页面里的位置。卡片取样的坐标都相对背景层算。
 * 只在 UI 线程读写。
 */
@Stable
internal class HomeGlass {
    /** 背景层的坐标（`onGloballyPositioned` 回填）。 */
    var coordinates: LayoutCoordinates? = null

    /** 清晰快照、模糊快照（按背景层像素对齐；模糊那张是 [FROST_SCALE] 倍分辨率）。 */
    var clearImage: ImageBitmap? by mutableStateOf(null)
        private set
    var frostImage: ImageBitmap? by mutableStateOf(null)
        private set

    /** 着色器里用的两张图（双线性、边缘夹取）。 */
    var clearShader: Shader? = null
        private set
    var frostShader: Shader? = null
        private set

    val ready: Boolean get() = clearImage != null && frostImage != null

    internal val dirty = Channel<Unit>(Channel.CONFLATED)

    /** 背景那一层的录制方式：照常画、并通知「背景重画了」，随后抓新快照。 */
    val recordBackground: ContentDrawScope.() -> Unit = {
        dirty.trySend(Unit)
        drawContent()
    }

    internal fun publish(clear: ImageBitmap, frost: ImageBitmap) {
        clearShader = clear.toLinearShader()
        frostShader = frost.toLinearShader()
        clearImage = clear
        frostImage = frost
    }

    internal fun clear() {
        clearShader = null
        frostShader = null
        clearImage = null
        frostImage = null
    }

    /** 卡片本地坐标 → 背景像素的映射（左上角 + 每个本地像素对应几个背景像素），拿不到返回 null。 */
    fun mappingOf(card: LayoutCoordinates): GlassMapping? {
        val bg = coordinates?.takeIf { it.isAttached } ?: return null
        if (!card.isAttached) return null
        val w = card.size.width.toFloat()
        val h = card.size.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        return runCatching {
            val p0 = bg.localPositionOf(card, Offset.Zero)
            val p1 = bg.localPositionOf(card, Offset(w, h))
            GlassMapping(p0, Offset((p1.x - p0.x) / w, (p1.y - p0.y) / h))
        }.getOrNull()
    }
}

/** 卡片本地 (x, y) 对应背景像素 `origin + (x, y) × axis`；axis 吸收了卡片所在图层的缩放（按压、编辑态收一圈）。 */
internal data class GlassMapping(val origin: Offset, val axis: Offset) {
    fun movedFrom(other: GlassMapping?): Boolean =
        other == null ||
            abs(origin.x - other.origin.x) > 0.25f || abs(origin.y - other.origin.y) > 0.25f ||
            abs(axis.x - other.axis.x) > 0.0005f || abs(axis.y - other.axis.y) > 0.0005f
}

/** 柔光卡用：把模糊快照里属于这张卡的那一块画上来（调用方负责裁成圆角）。 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrost(image: ImageBitmap, map: GlassMapping) {
    withTransform({
        // 背景像素 q → 本地 (q − origin) / axis
        scale(1f / map.axis.x, 1f / map.axis.y, pivot = Offset.Zero)
        translate(-map.origin.x, -map.origin.y)
    }) {
        drawImage(
            image,
            dstSize = IntSize((image.width / FROST_SCALE).roundToInt(), (image.height / FROST_SCALE).roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** 首页卡片区拿到的玻璃（背景上没有可透的细节时为 null）。 */
internal val LocalHomeGlass = staticCompositionLocalOf<HomeGlass?> { null }

/**
 * 首页玻璃的快照源：[backgroundLayer] 是背景那一层的录制（`layerBackdrop` 的 `onDraw` 用 [HomeGlass.recordBackground]）。
 * [enabled] 为 false（背景没有可透的细节）时不抓、清掉旧快照。
 */
@Composable
internal fun rememberHomeGlass(backgroundLayer: GraphicsLayer, enabled: Boolean, frostRadius: Dp): HomeGlass {
    val glass = remember { HomeGlass() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val frostLayer = rememberGraphicsLayer()
    LaunchedEffect(glass, enabled, frostRadius, density) {
        if (!enabled) {
            glass.clear()
            return@LaunchedEffect
        }
        suspend fun capture() {
            val clear = runCatching { backgroundLayer.toImageBitmap() }.getOrNull() ?: return
            if (clear.width <= 0 || clear.height <= 0) return
            val small = IntSize(
                max(1, (clear.width * FROST_SCALE).roundToInt()),
                max(1, (clear.height * FROST_SCALE).roundToInt()),
            )
            val radius = with(density) { frostRadius.toPx() } * FROST_SCALE
            frostLayer.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
            frostLayer.record(density, direction, small) {
                drawImage(clear, dstSize = small, filterQuality = FilterQuality.Low)
            }
            val frost = runCatching { frostLayer.toImageBitmap() }.getOrNull() ?: return
            androidx.compose.ui.util.trace("xjtu:glass:publish") { glass.publish(clear, frost) }
        }
        for (signal in glass.dirty) {
            delay(CAPTURE_SETTLE_MS)
            while (glass.dirty.tryReceive().isSuccess) Unit
            capture()
            // 壁纸有自己的图层：它解码后的淡入只重画那一层，外面这层不重录、不会再通知。
            // 隔一会儿再抓一次，接住这类只在子层里发生的变化
            delay(CAPTURE_FOLLOW_UP_MS)
            // 这期间背景又重画过：放回去，下一轮照常走「等稳 → 抓」；没有就补抓
            if (glass.dirty.tryReceive().isSuccess) glass.dirty.trySend(Unit) else capture()
        }
    }
    return glass
}

/** 这台设备能不能跑玻璃着色器（Android 13 起）。 */
internal val homeGlassSupported: Boolean by lazy { isRuntimeShaderSupported() }

/** 全进程一份：每次画之前设好参数再取快照，录下来的是当时那份，几张卡共用不会串。 */
private object HomeGlassShader {
    private var shader: RuntimeShader? = null
    private var failed = false

    fun obtain(): RuntimeShader? {
        if (failed || !homeGlassSupported) return null
        shader?.let { return it }
        return runCatching { RuntimeShader(HomeGlassShaderString) }
            .onFailure { failed = true }
            .getOrNull()
            ?.also { shader = it }
    }
}

/**
 * 液态卡的玻璃面：画在卡片背后单独的一层里（见 [HomeTileFaceBox]），位置一变只重画这一层。
 * [tint] 是卡面铺的那层白（预乘后在着色器里叠），[rimAlpha] 是轮廓高光的强度。
 */
internal fun Modifier.liquidGlass(glass: HomeGlass, radius: Dp, tint: Color, rimAlpha: Float): Modifier =
    this then LiquidGlassElement(glass, radius, tint, rimAlpha)

private data class LiquidGlassElement(
    val glass: HomeGlass,
    val radius: Dp,
    val tint: Color,
    val rimAlpha: Float,
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create() = LiquidGlassNode(glass, radius, tint, rimAlpha)

    override fun update(node: LiquidGlassNode) {
        node.glass = glass
        node.radius = radius
        node.tint = tint
        node.rimAlpha = rimAlpha
        node.invalidateDraw()
    }
}

private class LiquidGlassNode(
    var glass: HomeGlass,
    var radius: Dp,
    var tint: Color,
    var rimAlpha: Float,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    var mapping: GlassMapping? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val next = glass.mappingOf(coordinates) ?: return
        if (next.movedFrom(mapping)) {
            mapping = next
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        // 读快照（状态）：抓到新快照时这一层自动重画
        val clear = glass.clearImage?.let { glass.clearShader }
        val frost = glass.frostImage?.let { glass.frostShader }
        val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
        // 第一次画时位置回调可能还没来（或背景层的坐标晚一步）：现算一次
        val map = mapping ?: glass.mappingOf(requireLayoutCoordinates())?.also { mapping = it }
        val shader = HomeGlassShader.obtain()
        if (clear == null || frost == null || map == null || shader == null) {
            // 快照还没到的那几帧：先铺一层更浓的白，别透出没糊的壁纸
            drawRoundRect(tint.copy(alpha = (tint.alpha * 1.8f).coerceAtMost(0.9f)), cornerRadius = CornerRadius(r))
            drawContent()
            return
        }
        androidx.compose.ui.util.trace("xjtu:draw:liquidGlass") {
            shader.setInputShader("frost", frost)
            shader.setInputShader("clear", clear)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("cornerRadius", r)
            shader.setFloatUniform("origin", map.origin.x, map.origin.y)
            shader.setFloatUniform("axis", map.axis.x, map.axis.y)
            shader.setFloatUniform("frostScale", FROST_SCALE)
            shader.setFloatUniform("refractionHeight", REFRACTION_HEIGHT.toPx())
            shader.setFloatUniform("refractionAmount", -REFRACTION_AMOUNT.toPx())
            shader.setFloatUniform("saturation", GLASS_SATURATION)
            shader.setFloatUniform("dispersion", GLASS_DISPERSION)
            val a = tint.alpha
            shader.setFloatUniform("tint", tint.red * a, tint.green * a, tint.blue * a, a)
            shader.setFloatUniform("rimAlpha", rimAlpha)
            shader.setFloatUniform("rimWidth", RIM_WIDTH.toPx())
            drawRoundRect(ShaderBrush(shader.asComposeShader()), cornerRadius = CornerRadius(r))
        }
        drawContent()
    }
}

/** 折射带宽度与最大位移（与原来 lens(10dp, 14dp) 同一组）。 */
private val REFRACTION_HEIGHT = 10.dp
private val REFRACTION_AMOUNT = 14.dp

/** 透出来的背景提多少饱和度（与 backdrop 的 `vibrancy()` 相同）。 */
private const val GLASS_SATURATION = 1.5f

/** 边缘色散：红、蓝两路相对折射位移各偏出的比例。 */
private const val GLASS_DISPERSION = 0.12f

/** 轮廓高光那一圈的宽度。 */
private val RIM_WIDTH = 1.4.dp

/**
 * 玻璃着色器。本地坐标 c 对应背景像素 `origin + c × axis`。
 * - 折射带以外：模糊快照；
 * - 折射带里：按圆角矩形的法线往里取（边缘把背景扭弯），越靠轮廓越多取清晰快照，清晰那份带一点色散；
 * - 提饱和度、叠卡面那层白，最后加一圈朝左上 / 右下亮的轮廓高光。
 */
private const val HomeGlassShaderString = """
uniform shader frost;
uniform shader clear;
uniform float2 size;
uniform float cornerRadius;
uniform float2 origin;
uniform float2 axis;
uniform float frostScale;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float saturation;
uniform float dispersion;
uniform float4 tint;
uniform float rimAlpha;
uniform float rimWidth;

float sdRoundedRect(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    return length(max(q, 0.0)) - radius + min(max(q.x, q.y), 0.0);
}

float2 gradRoundedRect(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    if (q.x >= 0.0 && q.y >= 0.0) {
        return sign(p) * normalize(q);
    }
    if (q.x >= q.y) return float2(sign(p.x), 0.0);
    return float2(0.0, sign(p.y));
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

float2 bg(float2 c) {
    return origin + c * axis;
}

half4 frostAt(float2 c) {
    return frost.eval(bg(c) * frostScale);
}

half4 clearAt(float2 c) {
    return clear.eval(bg(c));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float radius = min(cornerRadius, min(halfSize.x, halfSize.y));
    float sd = sdRoundedRect(p, halfSize, radius);
    float inside = max(-sd, 0.0);

    half4 color;
    if (inside >= refractionHeight) {
        color = frostAt(coord);
    } else {
        float edge = 1.0 - inside / refractionHeight;
        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
        float2 n = gradRoundedRect(p, halfSize, gradRadius);
        float2 shift = circleMap(edge) * refractionAmount * n;
        float2 rc = coord + shift;
        half4 f = frostAt(rc);
        half4 c = clearAt(rc);
        if (dispersion > 0.0) {
            float2 k = shift * dispersion;
            c = half4(clearAt(rc + k).r, c.g, clearAt(rc - k).b, c.a);
        }
        color = mix(f, c, half(smoothstep(0.0, 1.0, edge)));
    }

    half luma = dot(color.rgb, half3(0.213, 0.715, 0.072));
    color = half4(clamp(mix(half3(luma), color.rgb, half(saturation)), half3(0.0), half3(color.a)), color.a);
    color = color * half(1.0 - tint.a) + half4(tint);

    float2 rn = gradRoundedRect(p, halfSize, min(radius * 1.5, min(halfSize.x, halfSize.y)));
    float facing = abs(dot(rn, float2(-0.7071, -0.7071)));
    float rim = (1.0 - smoothstep(0.0, rimWidth, inside)) * (0.35 + 0.65 * facing) * rimAlpha;
    color.rgb = min(color.rgb + half3(rim) * color.a, half3(color.a));
    return color;
}"""
