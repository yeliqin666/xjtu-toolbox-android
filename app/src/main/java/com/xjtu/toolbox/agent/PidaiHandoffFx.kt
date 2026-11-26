package com.xjtu.toolbox.agent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「这一页交给小岱」的转场，一气呵成：
 * 四边亮起、一道高光绕边跑，页面微暗 → 网格从顶上扫下来，扫过的地方留下网格 → 那圈亮边框带着满屏网格
 * 收缩、飞进小岱球 → 在球上炸开一圈光。大半屏在落进球的那一刻升起（[HANDOFF_OPEN_MS]）。
 * [serial] 每变一次播一遍；不拦触摸。减少动效时由调用方不播。
 */
@Composable
internal fun PidaiHandoffFx(serial: Int, anchor: () -> Rect?) {
    val t = remember { Animatable(1f) }
    LaunchedEffect(serial) {
        if (serial == 0) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, tween(HANDOFF_MS, easing = LinearEasing))
    }
    val accent = MiuixTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        if (p >= 1f) return@Canvas
        val target = anchor() ?: Rect(Offset(size.width - 68.dp.toPx(), size.height - 120.dp.toPx()), Size(56.dp.toPx(), 56.dp.toPx()))
        // 页面微暗，衬出亮边和网格
        val dim = win(p, 0f, 0.08f, 0.6f, 0.85f)
        if (dim > 0f) drawRect(Color.Black.copy(alpha = 0.22f * dim))
        // 收缩进度：0 = 还是整屏，1 = 落进球
        val c = ease3(((p - COLLAPSE_AT) / (LAND_AT - COLLAPSE_AT)).coerceIn(0f, 1f))
        val frame = Rect(
            lerp(0f, target.left, c), lerp(0f, target.top, c),
            lerp(size.width, target.right, c), lerp(size.height, target.bottom, c),
        )
        val corner = lerp(30.dp.toPx(), target.width / 2, c)
        grid(p, c, frame, accent)
        border(p, c, frame, corner, accent)
        burst(p, target)
    }
}

/** 整段时长与大半屏升起的时刻：升起正好接在落进球的那一下。 */
internal const val HANDOFF_MS = 1300
internal const val HANDOFF_OPEN_MS = 1000

private const val COLLAPSE_AT = 0.5f
private const val LAND_AT = 0.8f
private val CYAN = Color(0xFF22D3EE)

private fun win(p: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
    p <= a || p >= d -> 0f
    p < b -> (p - a) / (b - a)
    p <= c -> 1f
    else -> 1f - (p - c) / (d - c)
}

private fun ease(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

/** 先慢后快，像被吸进去。 */
private fun ease3(x: Float): Float = x * x * x

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * 网格：扫描线从顶上往下走，前沿一条亮带，扫过的地方留下淡网格；扫完后网格铺满整屏，
 * 随 [frame] 收缩等比缩小，格子跟着变密，看得出是同一张网被收走。
 */
private fun DrawScope.grid(p: Float, c: Float, frame: Rect, accent: Color) {
    val a = win(p, 0.04f, 0.1f, 0.7f, LAND_AT)
    if (a <= 0f) return
    val h = size.height
    val front = ease(((p - 0.06f) / 0.4f).coerceIn(0f, 1f)) * (h + 60.dp.toPx())
    val scale = frame.width / size.width
    val cell = 28.dp.toPx() * scale
    val line = 1.2.dp.toPx()
    // 只画扫过的部分：收缩开始时已经扫完，整张都在
    val visibleBottom = frame.top + (front.coerceAtMost(h) / h) * frame.height
    clipRect(frame.left, frame.top, frame.right, visibleBottom.coerceAtLeast(frame.top)) {
        var x = frame.left
        while (x <= frame.right + 0.5f) {
            drawLine(accent.copy(alpha = 0.34f * a), Offset(x, frame.top), Offset(x, visibleBottom), line)
            x += cell
        }
        var y = frame.top
        var row = 0
        while (y <= visibleBottom) {
            // 离扫描线越近越亮
            val near = 1f - ((visibleBottom - y) / (frame.height * 0.3f)).coerceIn(0f, 1f)
            drawLine(CYAN.copy(alpha = (0.28f + 0.6f * near * (1 - c)) * a), Offset(frame.left, y), Offset(frame.right, y), line)
            // 交点上隔几个亮一个，像在采样
            if (near > 0.2f && c < 0.5f) {
                var cx = frame.left
                var col = 0
                while (cx <= frame.right) {
                    if ((col + row) % 3 == 0) drawCircle(Color.White.copy(alpha = 0.9f * a * near), 1.8.dp.toPx(), Offset(cx, y))
                    cx += cell
                    col++
                }
            }
            y += cell
            row++
        }
    }
    // 扫描线前沿：一道亮带 + 一条白线
    if (front in 0f..h && c == 0f) {
        val band = 56.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, CYAN.copy(alpha = 0.45f * a), Color.White.copy(alpha = 0.85f * a)), front - band, front),
            Offset(0f, front - band), Size(size.width, band),
        )
        drawLine(Color.White.copy(alpha = a), Offset(0f, front), Offset(size.width, front), 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * 亮边框：开场是贴着屏幕四边、向内渐隐的一圈光，外加一道绕边跑的高光；收缩时这圈边框本身就是那个往球里飞的框。
 */
private fun DrawScope.border(p: Float, c: Float, frame: Rect, corner: Float, accent: Color) {
    val a = win(p, 0f, 0.06f, 0.78f, LAND_AT + 0.02f)
    if (a <= 0f) return
    // 开场的内发光：收缩一开始就淡掉，交给框本身
    val glow = a * (1f - ease((c * 4f).coerceIn(0f, 1f)))
    if (glow > 0f) {
        val w = size.width
        val h = size.height
        val band = 48.dp.toPx()
        val edge = listOf(accent.copy(alpha = 0.9f * glow), CYAN.copy(alpha = 0.4f * glow), Color.Transparent)
        drawRect(Brush.verticalGradient(edge, 0f, band), Offset.Zero, Size(w, band))
        drawRect(Brush.verticalGradient(edge, h, h - band), Offset(0f, h - band), Size(w, band))
        drawRect(Brush.horizontalGradient(edge, 0f, band), Offset.Zero, Size(band, h))
        drawRect(Brush.horizontalGradient(edge, w, w - band), Offset(w - band, 0f), Size(band, h))
    }
    // 框：整个过程都在，收缩时越来越亮越来越粗
    val stroke = lerp(3f, 4.5f, c).dp.toPx()
    drawRoundRect(accent.copy(alpha = 0.10f * a * c), frame.topLeft, frame.size, CornerRadius(corner, corner))
    drawRoundRect(CYAN.copy(alpha = 0.95f * a), frame.topLeft, frame.size, CornerRadius(corner, corner), style = Stroke(stroke))
    // 绕边跑的高光：开场跑一圈，收缩时继续绕着缩小的框跑，一直跑进球里
    val per = 2 * (frame.width + frame.height)
    val head = ease((p / 0.5f).coerceIn(0f, 1f)) * per + (p - 0.5f).coerceAtLeast(0f) * per * 3f
    val len = per * 0.2f
    for (i in 0 until 16) {
        val pt = perimeter(((head - len * i / 16f) % per + per) % per, frame)
        drawCircle(Color.White.copy(alpha = a * (1f - i / 16f)), (6f - i * 0.3f).dp.toPx() * lerp(1f, 0.6f, c), pt)
    }
    // 收缩时四角拖出的光尾，指向球
    if (c > 0f) {
        val tail = (1f - c) * 0.4f
        val to = Offset((frame.left + frame.right) / 2, (frame.top + frame.bottom) / 2)
        for (k in listOf(frame.topLeft, frame.topRight, frame.bottomLeft, frame.bottomRight)) {
            drawLine(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.7f * a)), k + (k - to) * tail, k),
                k + (k - to) * tail, k, 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

/** [frame] 周长上第 [s] 像素处的点（左上角起，顺时针）。 */
private fun perimeter(s: Float, frame: Rect): Offset {
    val w = frame.width
    val h = frame.height
    return when {
        s < w -> Offset(frame.left + s, frame.top)
        s < w + h -> Offset(frame.right, frame.top + s - w)
        s < 2 * w + h -> Offset(frame.right - (s - w - h), frame.bottom)
        else -> Offset(frame.left, frame.bottom - (s - 2 * w - h))
    }
}

/** 落进球：两圈光环先后炸开，中间一下白闪。 */
private fun DrawScope.burst(p: Float, target: Rect) {
    for ((k, at) in listOf(LAND_AT, LAND_AT + 0.06f).withIndex()) {
        val b = ((p - at) / 0.2f).coerceIn(0f, 1f)
        if (b <= 0f || b >= 1f) continue
        val r = target.width / 2 * (1f + (1.4f - 0.4f * k) * ease(b))
        drawCircle(CYAN.copy(alpha = (1f - b) * 0.95f), r, target.center, style = Stroke(((5f - k) * (1f - b) + 1f).dp.toPx()))
    }
    val f = win(p, LAND_AT - 0.02f, LAND_AT + 0.02f, LAND_AT + 0.04f, LAND_AT + 0.14f)
    if (f > 0f) drawCircle(Color.White.copy(alpha = 0.75f * f), target.width / 2 * 0.95f, target.center)
}
