package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 首页头卡的「像素」背景：useplanes CTA 的 dither 着色器。
 * 黑 / 深蓝 / 浅蓝三色按 interleaved gradient noise 落在细像素格上，配色与算法照搬站点。
 */
@Composable
expect fun HeroPixelBackground(modifier: Modifier = Modifier, animated: Boolean = true)

/** 像素背景上承托白字的暗色罩。 */
val HeroPixelScrim: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.22f),
    0.5f to Color.Black.copy(alpha = 0.12f),
    1f to Color.Black.copy(alpha = 0.40f),
)

/** 没有 RuntimeShader 的端（以及 Android 12 及以下）用同一套算法逐格渲染成一张位图。 */
@Composable
internal fun CpuHeroPixelBackground(modifier: Modifier) {
    val cell = with(LocalDensity.current) { 1.dp.toPx() }
    Box(
        modifier.fillMaxSize().drawWithCache {
            val cols = ceil(size.width / cell).toInt().coerceAtLeast(1)
            val rows = ceil(size.height / cell).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(cols, rows)
            val bitmapCanvas = Canvas(bitmap)
            val paint = Paint()
            for (y in 0 until rows) {
                // 站点着色器用的是左下角原点，这里按同样的方向取格子，渐变方向才对。
                val ciY = rows - 1 - y
                val uvY = (ciY + 0.5f) / rows
                for (x in 0 until cols) {
                    val uvX = (x + 0.5f) / cols
                    paint.color = pixelColor(pixelLevel(uvX, uvY, cols, rows, x, ciY))
                    bitmapCanvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
                }
            }
            onDrawBehind {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.None,
                )
            }
        },
    )
}

private const val SEED = 3.7f
private const val CREASE = 1f
private const val PIXEL_TIME = 0f
private val PIXEL_DARK = Color(0xFF000000)
private val PIXEL_MID = Color(0xFF1D4ED8)
private val PIXEL_LIGHT = Color(0xFF93C5FD)

private fun pixelColor(level: Float): Color = when {
    level < 0.5f -> PIXEL_DARK
    level < 1.5f -> PIXEL_MID
    else -> PIXEL_LIGHT
}

private fun pixelLevel(uvX: Float, uvY: Float, cols: Int, rows: Int, ciX: Int, ciY: Int): Float {
    val aspect = cols.toFloat() / rows
    val px = (uvX - 0.5f) * aspect * 1.6f + SEED
    val py = (uvY - 0.5f) * 1.6f + SEED
    val t = PIXEL_TIME * 0.12f
    val qx = fbm(px + t, py + t)
    val qy = fbm(px + 3.1f - t, py + 1.7f - t)
    val f = fbm(px + 2.2f * qx, py + 2.2f * qy)
    val k = smoothstep(-0.45f, 0.55f, f + 0.3f * uvY - 0.1f) * (1f + 0.3f * CREASE) - 0.15f * CREASE
    val s = k.coerceIn(0f, 1f) * 2f
    return floor(s) + if (ign(ciX, ciY) < (s - floor(s))) 1f else 0f
}

private fun hashX(x: Int, y: Int): Float =
    fract(sin(x * 127.1f + y * 311.7f) * 43758.5453f)

private fun hashY(x: Int, y: Int): Float =
    fract(sin(x * 269.5f + y * 183.3f) * 43758.5453f)

private fun noise(x: Float, y: Float): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val ux = fx * fx * (3f - 2f * fx)
    val uy = fy * fy * (3f - 2f * fy)
    val a = (hashX(ix, iy) - 0.5f) * fx + (hashY(ix, iy) - 0.5f) * fy
    val b = (hashX(ix + 1, iy) - 0.5f) * (fx - 1f) + (hashY(ix + 1, iy) - 0.5f) * fy
    val c = (hashX(ix, iy + 1) - 0.5f) * fx + (hashY(ix, iy + 1) - 0.5f) * (fy - 1f)
    val d = (hashX(ix + 1, iy + 1) - 0.5f) * (fx - 1f) + (hashY(ix + 1, iy + 1) - 0.5f) * (fy - 1f)
    return mix(mix(a, b, ux), mix(c, d, ux), uy) * 2f
}

private fun fbm(x: Float, y: Float): Float {
    var value = 0f
    var amplitude = 0.5f
    var px = x
    var py = y
    repeat(4) {
        value += amplitude * noise(px, py)
        px = px * 2.02f + 7.3f
        py = py * 2.02f + 7.3f
        amplitude *= 0.5f
    }
    return value
}

private fun ign(x: Int, y: Int): Float =
    fract(52.9829189f * fract(0.06711056f * x + 0.00583715f * y))

private fun fract(value: Float): Float = value - floor(value)

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
