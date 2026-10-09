package com.xjtu.toolbox.game.hop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/*
 * 跳一跳的画法。等距投影：地面 x 轴朝右上、z 轴朝左上、y 朝上；光从左上来，
 * 所以看得见的两个侧面里左边（朝 -x）亮、右边（朝 -z）暗，影子往右下落。
 */

const val COS30 = 0.8660254f
const val SIN30 = 0.5f
/** 世界里半径 r 的圆投到屏幕上是横 r·√2·cos30、竖 r·√2·sin30 的椭圆。 */
private const val ELLIPSE_X = 1.2247449f
private const val ELLIPSE_Y = 0.70710677f

class Iso(private val ox: Float, private val oy: Float, val scale: Float) {
    fun p(x: Float, z: Float, y: Float) = Offset(ox + (x - z) * COS30 * scale, oy - (x + z) * SIN30 * scale - y * scale)
}

private fun Color.shade(f: Float) = Color((red * f).coerceIn(0f, 1f), (green * f).coerceIn(0f, 1f), (blue * f).coerceIn(0f, 1f), alpha)

// ── 背景：昼夜 ──

/** 分数每 160 分走一个来回：白天 → 黄昏 → 夜晚 → 天亮。 */
private const val DAY_CYCLE = 160

fun smoothstep(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

/** 夜色浓度 0..1。 */
fun nightOf(score: Int): Float {
    val p = score % DAY_CYCLE
    return smoothstep(
        when {
            p < 80 -> 0f
            p < 105 -> (p - 80) / 25f
            p < 140 -> 1f
            else -> 1f - (p - 140) / 20f
        }
    )
}

/** 黄昏的橙色浓度 0..1，夜里退掉。 */
private fun duskOf(score: Int): Float {
    val p = score % DAY_CYCLE
    return smoothstep(
        when {
            p < 50 -> 0f
            p < 80 -> (p - 50) / 30f
            p < 105 -> 1f - (p - 80) / 25f
            else -> 0f
        }
    )
}

private val DAY = Color(0xFF86C5F0) to Color(0xFFEAF5FC)
private val DUSK = Color(0xFFF2A07B) to Color(0xFFFCE1C4)
private val NIGHT = Color(0xFF0F1733) to Color(0xFF2A2E58)

fun DrawScope.drawSky(score: Int, time: Float) {
    val dusk = duskOf(score)
    val night = nightOf(score)
    val top = lerp(lerp(DAY.first, DUSK.first, dusk), NIGHT.first, night)
    val bottom = lerp(lerp(DAY.second, DUSK.second, dusk), NIGHT.second, night)
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
    if (night <= 0.01f) return
    // 夜里：一片会眨的星星，右上角一轮月亮
    val rnd = Random(11)
    repeat(70) {
        val x = rnd.nextFloat() * size.width
        val y = rnd.nextFloat() * size.height * 0.55f
        val twinkle = 0.5f + 0.5f * sin(time * (1f + rnd.nextFloat() * 2f) + it)
        drawCircle(Color.White, size.width * (0.002f + rnd.nextFloat() * 0.003f), Offset(x, y), alpha = night * (0.35f + 0.65f * twinkle))
    }
    val moon = Offset(size.width * 0.8f, size.height * 0.16f)
    val r = size.width * 0.055f
    drawCircle(Color(0xFFFFF4D6), r * 2.4f, moon, alpha = 0.08f * night)
    drawCircle(Color(0xFFFFF4D6), r, moon, alpha = night)
    drawCircle(Color(0xFFE9DDBB), r * 0.22f, moon + Offset(-r * 0.3f, r * 0.2f), alpha = 0.5f * night)
}

/**
 * 远景里的校园地标：立在地平线上，白天淡淡的粉色插画，夜里成了深色剪影。
 * [fade] 是新换上这座的淡入进度；[shift] 随镜头平移一点点，做出远景的视差。
 */
fun DrawScope.drawLandmark(image: ImageBitmap, fade: Float, night: Float, shift: Float) {
    val maxW = size.width * 0.92f
    val h = minOf(size.height * 0.2f, maxW * image.height / image.width)
    val w = h * image.width / image.height
    val left = (size.width - w) / 2 + shift
    val bottom = size.height * 0.42f
    val dst = IntOffset(left.toInt(), (bottom - h).toInt())
    val dstSize = IntSize(w.toInt(), h.toInt())
    val a = fade.coerceIn(0f, 1f)
    if (night < 1f) drawImage(image, dstOffset = dst, dstSize = dstSize, alpha = a * 0.6f * (1f - night))
    if (night > 0f) drawImage(image, dstOffset = dst, dstSize = dstSize, alpha = a * 0.8f * night, colorFilter = ColorFilter.tint(Color(0xFF1B2146)))
}

// ── 台子 ──

/** 一种台子的长相：顶面、左侧（亮）、右侧（暗）三色，外加一段装饰。 */
private class Look(val top: Color, val left: Color, val right: Color) {
    fun dim(f: Float) = if (f >= 1f) this else Look(top.shade(f), left.shade(f), right.shade(f))
}

private val BOX_LOOKS = listOf(
    Look(Color(0xFFEBC693), Color(0xFFD9AC71), Color(0xFFBD8E55)), // 纸箱
    Look(Color(0xFFF77F8E), Color(0xFFE85C6D), Color(0xFFC94454)), // 礼物盒
    Look(Color(0xFF5B8DEF), Color(0xFF4E7FE0), Color(0xFF3D66BD)), // 书堆
    Look(Color(0xFF2A2F3D), Color(0xFF232733), Color(0xFF1B1F29)), // 魔方
    Look(Color(0xFF86D16F), Color(0xFFA2724A), Color(0xFF855B39)), // 草方块
)
private val ROUND_LOOKS = listOf(
    Look(Color(0xFFFFA8C5), Color(0xFFFFF4EA), Color(0xFFF1DCCB)), // 蛋糕
    Look(Color(0xFF23252E), Color(0xFF3A3E4C), Color(0xFF2A2D38)), // 黑胶
    Look(Color(0xFF7A5238), Color(0xFFF7F8FB), Color(0xFFD9DCE4)), // 马克杯
    Look(Color(0xFFF8F1E1), Color(0xFFE25555), Color(0xFFBC3B3B)), // 鼓
)
private val SPRING_LOOK = Look(Color(0xFFFF8FB1), Color(0xFFB9C1D1), Color(0xFF8E97AA))
private val ICE_LOOK = Look(Color(0xFFDDF5FF), Color(0xFFB2E3F7), Color(0xFF8CCDEB))
private val CRUMBLE_LOOK = Look(Color(0xFFC9B8A2), Color(0xFFB09D86), Color(0xFF8F7E69))
private val GOLD_LOOK = Look(Color(0xFFFFD45C), Color(0xFFF2B730), Color(0xFFD29A1C))
private val PORTAL_LOOK = Look(Color(0xFF3B2E6E), Color(0xFF34295F), Color(0xFF261E48))
private val SPIN_LOOK = Look(Color(0xFF8BDCC8), Color(0xFF5CBFA9), Color(0xFF43A08C))
private val GHOST_LOOK = Look(Color(0xFFEDF0FF), Color(0xFFD3DAF6), Color(0xFFB7C1E8))
private val TRAMPOLINE_LOOK = Look(Color(0xFF262B3B), Color(0xFF4B86F2), Color(0xFF3466C9))

private fun lookOf(pad: Pad): Look = when {
    pad.bonus > 0 -> GOLD_LOOK
    pad.kind == PadKind.SPRING -> SPRING_LOOK
    pad.kind == PadKind.ICE -> ICE_LOOK
    pad.kind == PadKind.CRUMBLE -> CRUMBLE_LOOK
    pad.kind == PadKind.PORTAL -> PORTAL_LOOK
    pad.kind == PadKind.SPIN -> SPIN_LOOK
    pad.kind == PadKind.GHOST -> GHOST_LOOK
    pad.kind == PadKind.TRAMPOLINE -> TRAMPOLINE_LOOK
    pad.round -> ROUND_LOOKS[pad.style % ROUND_LOOKS.size]
    else -> BOX_LOOKS[pad.style % BOX_LOOKS.size]
}

/** 台子落在地面上的影子，往右下偏一点。 */
fun DrawScope.drawPadShadow(pad: Pad, iso: Iso) {
    val alpha = 0.13f * (1f - pad.fade)
    if (alpha <= 0f) return
    val sx = pad.x + 0.08f
    val sz = pad.z - 0.22f
    val y = -PAD_HEIGHT
    if (pad.round) {
        val c = iso.p(sx, sz, y)
        val rx = pad.half * ELLIPSE_X * iso.scale * 1.05f
        val ry = pad.half * ELLIPSE_Y * iso.scale * 1.05f
        drawOval(Color.Black, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = alpha)
    } else {
        val h = pad.half * 1.05f
        quad(iso.p(sx - h, sz - h, y), iso.p(sx + h, sz - h, y), iso.p(sx + h, sz + h, y), iso.p(sx - h, sz + h, y), Color.Black, alpha)
    }
    if (pad.kind == PadKind.MOVING && pad.moving) {
        // 移动台下面画一条轨道
        val a = if (pad.axis == 0) iso.p(pad.baseX, pad.baseZ - MOVE_AMPLITUDE - pad.half, y) else iso.p(pad.baseX - MOVE_AMPLITUDE - pad.half, pad.baseZ, y)
        val b = if (pad.axis == 0) iso.p(pad.baseX, pad.baseZ + MOVE_AMPLITUDE + pad.half, y) else iso.p(pad.baseX + MOVE_AMPLITUDE + pad.half, pad.baseZ, y)
        drawLine(Color.Black, a, b, iso.scale * 0.05f, StrokeCap.Round, alpha = 0.12f)
    }
}

/**
 * 画一块台子。[press] 是被压下去的深度（世界单位），[shakeX] 是易碎台快塌时的抖动，
 * [night] 夜色浓度（台子变暗、台面亮一盏暖灯），[stay] 彩蛋台上停留的进度 0..1（不在计时就是 -1）。
 */
fun DrawScope.drawPad(pad: Pad, iso: Iso, press: Float, shakeX: Float, time: Float, night: Float, stay: Float) {
    // 幽灵台隐身时只剩一层淡影
    val alpha = (1f - pad.fade) * (0.2f + 0.8f * pad.solidity)
    if (alpha <= 0f) return
    val look = lookOf(pad).dim(1f - 0.38f * night)
    val sink = pad.fade * 0.8f
    val top = -press - sink
    val bottom = -PAD_HEIGHT - sink
    val cx = pad.x + shakeX
    val cz = pad.z
    if (pad.round) {
        val cyl = Cyl(iso, cx, cz, pad.half, top, bottom)
        cyl.body(this, look, alpha)
        decorateRound(pad, cyl, look, alpha, time, stay)
    } else {
        val box = BoxShape(iso, cx, cz, pad.half, top, bottom)
        box.body(this, look, alpha)
        decorateBox(pad, box, alpha, time, stay)
    }
    val c = iso.p(cx, cz, top)
    val rx = pad.half * ELLIPSE_X * iso.scale
    val ry = pad.half * ELLIPSE_Y * iso.scale
    if (night > 0.05f && pad.kind != PadKind.GHOST) {
        drawOval(
            Brush.radialGradient(listOf(Color(0xFFFFD27A).copy(alpha = 0.45f * night), Color.Transparent), c, rx),
            Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = alpha,
        )
    }
    // 彩蛋台停留进度：台面上一圈白环慢慢画满
    if (stay >= 0f) {
        val k = 0.92f
        drawArc(
            Color.White, -90f, 360f * stay, false, Offset(c.x - rx * k, c.y - ry * k), Size(rx * k * 2, ry * k * 2),
            alpha = 0.9f, style = Stroke(iso.scale * 0.02f, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, color: Color, alpha: Float = 1f) =
    quad(a, b, c, d, SolidColor(color), alpha)

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, brush: Brush, alpha: Float = 1f) {
    drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }, brush, alpha = alpha)
}

/** 方台的几何：顶面坐标 (u, v)、左面 (v, t)、右面 (u, t)，u v ∈ [-1, 1]，t ∈ [0, 1] 从底到顶。 */
private class BoxShape(val iso: Iso, val cx: Float, val cz: Float, val h: Float, val top: Float, val bottom: Float) {
    fun t(u: Float, v: Float) = iso.p(cx + u * h, cz + v * h, top)
    fun l(v: Float, t: Float) = iso.p(cx - h, cz + v * h, bottom + (top - bottom) * t)
    fun r(u: Float, t: Float) = iso.p(cx + u * h, cz - h, bottom + (top - bottom) * t)

    fun body(scope: DrawScope, look: Look, alpha: Float) = with(scope) {
        val lt = l(1f, 1f); val lb = l(1f, 0f)
        val ft = t(-1f, -1f); val fb = l(-1f, 0f)
        val rt = r(1f, 1f); val rb = r(1f, 0f)
        quad(lt, ft, fb, lb, Brush.verticalGradient(listOf(look.left, look.left.shade(0.9f)), ft.y, fb.y), alpha)
        quad(ft, rt, rb, fb, Brush.verticalGradient(listOf(look.right, look.right.shade(0.88f)), ft.y, fb.y), alpha)
        quad(t(-1f, -1f), t(1f, -1f), t(1f, 1f), t(-1f, 1f), Brush.linearGradient(listOf(look.top.shade(1.06f), look.top.shade(0.97f)), ft, t(1f, 1f)), alpha)
        // 棱线：顶面两条前沿、正前方竖棱提亮，边缘更利落
        val w = iso.scale * 0.012f
        drawLine(Color.White, ft, rt, w, alpha = 0.45f * alpha)
        drawLine(Color.White, ft, lt, w, alpha = 0.45f * alpha)
        drawLine(Color.White, ft, fb, w, alpha = 0.18f * alpha)
    }

    fun topRect(scope: DrawScope, u0: Float, v0: Float, u1: Float, v1: Float, color: Color, alpha: Float) =
        scope.quad(t(u0, v0), t(u1, v0), t(u1, v1), t(u0, v1), color, alpha)

    fun leftRect(scope: DrawScope, v0: Float, t0: Float, v1: Float, t1: Float, color: Color, alpha: Float) =
        scope.quad(l(v0, t1), l(v1, t1), l(v1, t0), l(v0, t0), color, alpha)

    fun rightRect(scope: DrawScope, u0: Float, t0: Float, u1: Float, t1: Float, color: Color, alpha: Float) =
        scope.quad(r(u0, t1), r(u1, t1), r(u1, t0), r(u0, t0), color, alpha)
}

/** 圆台的几何：顶面同方台用 (u, v)；侧面按高度 t 取一圈椭圆。 */
private class Cyl(val iso: Iso, val cx: Float, val cz: Float, val h: Float, val top: Float, val bottom: Float) {
    val rx = h * ELLIPSE_X * iso.scale
    val ry = h * ELLIPSE_Y * iso.scale
    fun t(u: Float, v: Float) = iso.p(cx + u * h, cz + v * h, top)
    fun center(t: Float) = iso.p(cx, cz, bottom + (top - bottom) * t)

    /** 侧面从高度 t0 到 t1 的一圈带子：上沿下半椭圆 + 下沿下半椭圆围成。 */
    fun band(t0: Float, t1: Float): Path {
        val a = center(t1)
        val b = center(t0)
        return Path().apply {
            moveTo(a.x - rx, a.y)
            arcTo(Rect(a.x - rx, a.y - ry, a.x + rx, a.y + ry), 180f, -180f, false)
            lineTo(b.x + rx, b.y)
            arcTo(Rect(b.x - rx, b.y - ry, b.x + rx, b.y + ry), 0f, 180f, false)
            close()
        }
    }

    fun body(scope: DrawScope, look: Look, alpha: Float) = with(scope) {
        val c = center(1f)
        drawPath(band(0f, 1f), Brush.horizontalGradient(listOf(look.left.shade(1.04f), look.left, look.right, look.right.shade(0.85f)), c.x - rx, c.x + rx), alpha = alpha)
        drawOval(Brush.linearGradient(listOf(look.top.shade(1.06f), look.top.shade(0.95f)), Offset(c.x - rx, c.y + ry), Offset(c.x + rx, c.y - ry)), Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = alpha)
        drawOval(Color.White, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = 0.35f * alpha, style = Stroke(iso.scale * 0.012f))
    }

    fun topOval(scope: DrawScope, k: Float, color: Color, alpha: Float, style: Stroke? = null) {
        val c = center(1f)
        if (style == null) scope.drawOval(color, Offset(c.x - rx * k, c.y - ry * k), Size(rx * k * 2, ry * k * 2), alpha = alpha)
        else scope.drawOval(color, Offset(c.x - rx * k, c.y - ry * k), Size(rx * k * 2, ry * k * 2), alpha = alpha, style = style)
    }
}

private fun DrawScope.decorateBox(pad: Pad, b: BoxShape, alpha: Float, time: Float, stay: Float) {
    val s = b.iso.scale
    when {
        pad.bonus > 0 -> star(b.t(0f, 0f), s * pad.half * 0.45f, Color.White, alpha * 0.9f)
        pad.kind == PadKind.GHOST -> ghostFace(b.r(-0.28f, 0.62f), b.r(0.28f, 0.62f), s, alpha)
        pad.kind == PadKind.SPRING -> springMarks(b.t(0f, 0f), s, alpha, coil = { i ->
            val y = 0.15f + i * 0.2f
            b.leftRect(this, -0.6f, y, 0.6f, y + 0.06f, Color(0xFF6B7385), alpha)
            b.rightRect(this, -0.6f, y, 0.6f, y + 0.06f, Color(0xFF596073), alpha)
        })
        pad.kind == PadKind.ICE -> {
            b.topRect(this, -0.7f, -0.9f, -0.45f, 0.9f, Color.White, alpha * 0.5f)
            b.leftRect(this, 0.1f, 0.1f, 0.3f, 0.9f, Color.White, alpha * 0.35f)
            b.rightRect(this, -0.2f, 0.1f, 0f, 0.9f, Color.White, alpha * 0.25f)
        }
        pad.kind == PadKind.CRUMBLE -> cracks(b.t(0f, 0f), s * pad.half, alpha)
        else -> when (pad.style % BOX_LOOKS.size) {
            0 -> { // 纸箱：封箱胶带 + 侧面标签和向上箭头
                b.topRect(this, -1f, -0.18f, 1f, 0.18f, Color(0xFFF6DFB6), alpha)
                b.rightRect(this, -1f, 0.55f, 1f, 1f, Color(0xFFF6DFB6), alpha * 0.9f)
                b.leftRect(this, -0.55f, 0.25f, 0.35f, 0.62f, Color.White, alpha * 0.85f)
                val a = b.r(0.45f, 0.2f); val tip = b.r(0.45f, 0.45f)
                drawLine(Color(0xFF7A5A34), a, tip, s * 0.02f, StrokeCap.Round, alpha = alpha)
                drawLine(Color(0xFF7A5A34), tip, b.r(0.3f, 0.34f), s * 0.02f, StrokeCap.Round, alpha = alpha)
                drawLine(Color(0xFF7A5A34), tip, b.r(0.6f, 0.34f), s * 0.02f, StrokeCap.Round, alpha = alpha)
            }
            1 -> { // 礼物盒：十字丝带 + 蝴蝶结
                val gold = Color(0xFFFFD166)
                b.topRect(this, -0.16f, -1f, 0.16f, 1f, gold, alpha)
                b.topRect(this, -1f, -0.16f, 1f, 0.16f, gold, alpha)
                b.leftRect(this, -0.16f, 0f, 0.16f, 1f, gold.shade(0.92f), alpha)
                b.rightRect(this, -0.16f, 0f, 0.16f, 1f, gold.shade(0.8f), alpha)
                val c = b.t(0f, 0f)
                val r = s * pad.half * 0.28f
                drawOval(gold.shade(1.05f), Offset(c.x - r * 2f, c.y - r * 1.4f), Size(r * 1.8f, r * 1.3f), alpha = alpha)
                drawOval(gold.shade(1.05f), Offset(c.x + r * 0.2f, c.y - r * 1.4f), Size(r * 1.8f, r * 1.3f), alpha = alpha)
                drawCircle(gold.shade(0.85f), r * 0.45f, c + Offset(0f, -r * 0.6f), alpha = alpha)
            }
            2 -> { // 书堆：三本书，侧面分层，书页露白
                val colors = listOf(Color(0xFFF2994A), Color(0xFF6FCF97), Color(0xFF5B8DEF))
                for (i in 0..2) {
                    val t0 = i / 3f
                    val t1 = (i + 1) / 3f - 0.03f
                    b.leftRect(this, -1f, t0, 1f, t1, colors[i].shade(0.95f), alpha)
                    b.rightRect(this, -1f, t0, 1f, t1, colors[i].shade(0.78f), alpha)
                    b.rightRect(this, -0.92f, t0 + 0.04f, 0.92f, t1 - 0.04f, Color(0xFFFBF7EE), alpha)
                }
                b.topRect(this, -0.7f, -0.55f, 0.7f, -0.42f, Color.White, alpha * 0.8f)
                b.topRect(this, -0.7f, -0.25f, 0.2f, -0.15f, Color.White, alpha * 0.55f)
                b.rightRect(this, 0.55f, 0.45f, 0.72f, 1f, Color(0xFFE84A5F), alpha)
            }
            3 -> { // 魔方：三面各九格；有人站着计时时色块一直在打乱重排
                val tile = listOf(Color(0xFFFFD43B), Color(0xFFFF6B6B), Color(0xFF4DABF7), Color(0xFF51CF66), Color(0xFFFFFFFF), Color(0xFFFF922B))
                val rnd = Random(pad.baseX.toBits() xor pad.baseZ.toBits() xor if (stay >= 0f) (time * 6f).toInt() else 0)
                for (i in 0..2) for (j in 0..2) {
                    val a0 = -1f + i * 2f / 3f + 0.08f
                    val a1 = a0 + 2f / 3f - 0.16f
                    val b0 = -1f + j * 2f / 3f + 0.08f
                    val b1 = b0 + 2f / 3f - 0.16f
                    b.topRect(this, a0, b0, a1, b1, tile[rnd.nextInt(tile.size)], alpha)
                    val t0 = j / 3f + 0.04f
                    val t1 = t0 + 1f / 3f - 0.08f
                    b.leftRect(this, a0, t0, a1, t1, tile[rnd.nextInt(tile.size)].shade(0.9f), alpha)
                    b.rightRect(this, a0, t0, a1, t1, tile[rnd.nextInt(tile.size)].shade(0.75f), alpha)
                }
            }
            else -> { // 草方块：侧面顶上一圈草皮、泥土斑点，顶上几丛草
                b.leftRect(this, -1f, 0.8f, 1f, 1f, Color(0xFF6DBB57), alpha)
                b.rightRect(this, -1f, 0.8f, 1f, 1f, Color(0xFF5AA046), alpha)
                val rnd = Random(pad.baseX.toBits())
                repeat(6) {
                    val v = rnd.nextFloat() * 1.6f - 0.8f
                    val t = rnd.nextFloat() * 0.55f + 0.1f
                    b.leftRect(this, v, t, v + 0.18f, t + 0.1f, Color(0xFF7D5533), alpha)
                    b.rightRect(this, -v, t, -v + 0.18f, t + 0.1f, Color(0xFF6A4629), alpha)
                }
                repeat(5) {
                    val u = rnd.nextFloat() * 1.4f - 0.7f
                    val v = rnd.nextFloat() * 1.4f - 0.7f
                    val base = b.t(u, v)
                    val hgt = s * 0.06f
                    for (k in -1..1) drawLine(Color(0xFF4E9A3C), base, base + Offset(k * hgt * 0.4f, -hgt), s * 0.012f, StrokeCap.Round, alpha = alpha)
                }
            }
        }
    }
}

private fun DrawScope.decorateRound(pad: Pad, c: Cyl, look: Look, alpha: Float, time: Float, stay: Float) {
    val s = c.iso.scale
    val center = c.center(1f)
    when {
        pad.kind == PadKind.PORTAL -> { // 传送门：一圈圈彩光在转
            val colors = listOf(Color(0xFF7DF9FF), Color(0xFFB388FF), Color(0xFFFF7EDB))
            for (i in 0..2) {
                val k = 0.85f - i * 0.22f
                rotate(time * (120f + i * 60f) * if (i % 2 == 0) 1f else -1f, center) {
                    drawArc(
                        colors[i], 0f, 250f, false, Offset(center.x - c.rx * k, center.y - c.ry * k), Size(c.rx * k * 2, c.ry * k * 2),
                        alpha = alpha * 0.9f, style = Stroke(s * 0.025f, cap = StrokeCap.Round),
                    )
                }
            }
            c.topOval(this, 0.2f, Color.White, alpha * (0.6f + 0.3f * sin(time * 6f)))
        }
        pad.kind == PadKind.SPIN -> { // 旋转台：三道弧形箭头跟着台面转
            for (i in 0..2) {
                val start = pad.phase * 57.3f + i * 120f
                drawArc(
                    Color.White, start, 70f, false, Offset(center.x - c.rx * 0.75f, center.y - c.ry * 0.75f), Size(c.rx * 1.5f, c.ry * 1.5f),
                    alpha = alpha * 0.85f, style = Stroke(s * 0.022f, cap = StrokeCap.Round),
                )
                val a = (start + 70f) * PI.toFloat() / 180f
                drawCircle(Color.White, s * 0.022f, Offset(center.x + c.rx * 0.75f * cos(a), center.y + c.ry * 0.75f * sin(a)), alpha = alpha)
            }
        }
        pad.kind == PadKind.GHOST -> ghostFace(c.center(0.6f) + Offset(-c.rx * 0.28f, c.ry * 0.9f), c.center(0.6f) + Offset(c.rx * 0.28f, c.ry * 0.9f), s, alpha)
        pad.kind == PadKind.TRAMPOLINE -> { // 蹦床：蓝色边框、黑网面、几道网线
            c.topOval(this, 1f, Color(0xFF7FB0FF), alpha, Stroke(s * 0.035f))
            for (i in -2..2) {
                val u = i * 0.3f
                drawLine(Color.White, c.t(u, -0.85f), c.t(u, 0.85f), s * 0.006f, alpha = alpha * 0.25f)
                drawLine(Color.White, c.t(-0.85f, u), c.t(0.85f, u), s * 0.006f, alpha = alpha * 0.25f)
            }
        }
        pad.bonus > 0 -> {
            c.topOval(this, 0.78f, Color.White, alpha * 0.6f, Stroke(s * 0.015f))
            star(center, s * pad.half * 0.5f, Color.White, alpha * 0.95f)
            drawPath(c.band(0.35f, 0.5f), look.right.shade(0.9f), alpha = alpha)
        }
        pad.kind == PadKind.SPRING -> springMarks(center, s, alpha, coil = { i ->
            val y = 0.15f + i * 0.2f
            drawPath(c.band(y, y + 0.06f), Color(0xFF626A7C), alpha = alpha)
        })
        pad.kind == PadKind.ICE -> {
            c.topOval(this, 0.6f, Color.White, alpha * 0.35f)
            drawLine(Color.White, center + Offset(-c.rx * 0.5f, c.ry * 0.1f), center + Offset(-c.rx * 0.1f, -c.ry * 0.4f), s * 0.02f, StrokeCap.Round, alpha = alpha * 0.8f)
        }
        pad.kind == PadKind.CRUMBLE -> cracks(center, s * pad.half, alpha)
        else -> when (pad.style % ROUND_LOOKS.size) {
            0 -> { // 蛋糕：中间一圈粉色夹层、糖霜垂下来、顶上一颗樱桃
                drawPath(c.band(0.42f, 0.55f), Color(0xFFFF8FB1), alpha = alpha)
                val drip = Path()
                val topC = c.center(1f)
                val n = 9
                for (i in 0..n) {
                    val ang = PI.toFloat() * i / n
                    val x = topC.x - c.rx * cos(ang)
                    val y = topC.y + c.ry * sin(ang)
                    val d = s * (0.05f + 0.05f * ((i * 37) % 3))
                    if (i == 0) drip.moveTo(x, y) else drip.lineTo(x, y + if (i % 2 == 0) d else 0f)
                }
                drip.lineTo(topC.x + c.rx, topC.y)
                drip.close()
                drawPath(drip, look.top, alpha = alpha)
                val cherry = center + Offset(0f, -s * 0.07f)
                drawLine(Color(0xFF3F7A3A), cherry, cherry + Offset(s * 0.04f, -s * 0.09f), s * 0.012f, StrokeCap.Round, alpha = alpha)
                drawCircle(Color(0xFFD7263D), s * 0.055f, cherry, alpha = alpha)
                drawCircle(Color.White, s * 0.016f, cherry + Offset(-s * 0.02f, -s * 0.02f), alpha = alpha * 0.8f)
            }
            1 -> { // 黑胶：一圈圈唱纹、红色中标、一道反光
                for (k in listOf(0.9f, 0.78f, 0.66f, 0.54f)) c.topOval(this, k, Color.White, alpha * 0.08f, Stroke(s * 0.006f))
                c.topOval(this, 0.34f, Color(0xFFE4572E), alpha)
                c.topOval(this, 0.06f, Color(0xFF111111), alpha)
                // 反光：平时微微晃，有人站着计时时唱片转起来
                rotate(if (stay >= 0f) time * 240f else -25f + sin(time) * 3f, center) {
                    drawOval(Color.White, Offset(center.x - c.rx * 0.85f, center.y - c.ry * 0.12f), Size(c.rx * 0.5f, c.ry * 0.24f), alpha = alpha * if (stay >= 0f) 0.3f else 0.12f)
                }
                if (stay >= 0f) notes(center, s, time, alpha)
            }
            2 -> { // 马克杯：杯口白边、拿铁拉花、右侧把手、杯身一道色条
                drawPath(c.band(0.55f, 0.68f), Color(0xFF5B8DEF), alpha = alpha)
                val handleC = c.center(0.5f) + Offset(c.rx * 1.02f, 0f)
                drawOval(Color(0xFFD9DCE4), Offset(handleC.x - s * 0.1f, handleC.y - s * 0.16f), Size(s * 0.2f, s * 0.3f), alpha = alpha, style = Stroke(s * 0.05f))
                c.topOval(this, 1f, Color(0xFFF7F8FB), alpha)
                c.topOval(this, 0.84f, look.top, alpha)
                val heart = center
                val r = s * pad.half * 0.18f
                drawCircle(Color(0xFFF3E1C7), r, heart + Offset(-r * 0.7f, -r * 0.2f), alpha = alpha)
                drawCircle(Color(0xFFF3E1C7), r, heart + Offset(r * 0.7f, -r * 0.2f), alpha = alpha)
                drawPath(Path().apply {
                    moveTo(heart.x - r * 1.6f, heart.y); lineTo(heart.x + r * 1.6f, heart.y); lineTo(heart.x, heart.y + r * 1.6f); close()
                }, Color(0xFFF3E1C7), alpha = alpha)
                if (stay >= 0f) steam(center, s, time, alpha)
            }
            else -> { // 鼓：侧面白色 Z 字绳、顶上一圈金色鼓圈
                val n = 7
                val up = c.center(0.85f)
                val dn = c.center(0.15f)
                for (i in 0 until n) {
                    val a0 = PI.toFloat() * i / n
                    val a1 = PI.toFloat() * (i + 1) / n
                    val p0 = Offset(up.x - c.rx * cos(a0), up.y + c.ry * sin(a0))
                    val p1 = Offset(dn.x - c.rx * cos((a0 + a1) / 2), dn.y + c.ry * sin((a0 + a1) / 2))
                    val p2 = Offset(up.x - c.rx * cos(a1), up.y + c.ry * sin(a1))
                    drawLine(Color.White, p0, p1, s * 0.012f, alpha = alpha * 0.9f)
                    drawLine(Color.White, p1, p2, s * 0.012f, alpha = alpha * 0.9f)
                }
                drawPath(c.band(0.88f, 1f), Color(0xFFE0B04C), alpha = alpha)
                c.topOval(this, 0.25f, Color.Black, alpha * 0.05f)
            }
        }
    }
}

/** 幽灵台侧面的一对小眼睛。 */
private fun DrawScope.ghostFace(left: Offset, right: Offset, s: Float, alpha: Float) {
    for (e in listOf(left, right)) drawOval(Color(0xFF3A3F66), Offset(e.x - s * 0.025f, e.y - s * 0.04f), Size(s * 0.05f, s * 0.08f), alpha = alpha * 0.8f)
}

/** 黑胶转起来时往上飘的音符。 */
private fun DrawScope.notes(c: Offset, s: Float, time: Float, alpha: Float) {
    for (i in 0..2) {
        val t = (time * 0.7f + i / 3f) % 1f
        val p = c + Offset((i - 1) * s * 0.18f + sin(t * 6f + i) * s * 0.05f, -s * (0.1f + t * 0.6f))
        val a = alpha * (1f - t)
        drawOval(Color.White, Offset(p.x - s * 0.03f, p.y - s * 0.02f), Size(s * 0.06f, s * 0.045f), alpha = a)
        drawLine(Color.White, p + Offset(s * 0.028f, 0f), p + Offset(s * 0.028f, -s * 0.1f), s * 0.012f, alpha = a)
        drawLine(Color.White, p + Offset(s * 0.028f, -s * 0.1f), p + Offset(s * 0.07f, -s * 0.08f), s * 0.012f, StrokeCap.Round, alpha = a)
    }
}

/** 咖啡冒的热气：三缕弯弯的白线往上飘。 */
private fun DrawScope.steam(c: Offset, s: Float, time: Float, alpha: Float) {
    for (i in 0..2) {
        val t = (time * 0.6f + i / 3f) % 1f
        val base = c + Offset((i - 1) * s * 0.07f, -s * (0.05f + t * 0.3f))
        val path = Path().apply {
            moveTo(base.x, base.y)
            for (k in 1..6) lineTo(base.x + sin(time * 3f + k + i) * s * 0.025f, base.y - k * s * 0.03f)
        }
        drawPath(path, Color.White, alpha = alpha * 0.55f * (1f - t), style = Stroke(s * 0.014f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.star(c: Offset, r: Float, color: Color, alpha: Float) {
    val path = Path()
    for (i in 0 until 10) {
        val ang = -PI.toFloat() / 2 + i * PI.toFloat() / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        // 顶面是斜着看的，竖向压扁
        val p = Offset(c.x + rr * cos(ang), c.y + rr * sin(ang) * 0.6f)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color, alpha = alpha)
}

private fun DrawScope.springMarks(c: Offset, s: Float, alpha: Float, coil: DrawScope.(Int) -> Unit) {
    for (i in 0..3) coil(i)
    // 顶面一个朝上的双箭头
    val w = s * 0.08f
    for (k in 0..1) {
        val y = c.y - k * s * 0.06f
        drawLine(Color.White, Offset(c.x - w, y + w * 0.5f), Offset(c.x, y - w * 0.2f), s * 0.02f, StrokeCap.Round, alpha = alpha)
        drawLine(Color.White, Offset(c.x, y - w * 0.2f), Offset(c.x + w, y + w * 0.5f), s * 0.02f, StrokeCap.Round, alpha = alpha)
    }
}

private fun DrawScope.cracks(c: Offset, r: Float, alpha: Float) {
    val ink = Color(0xFF5E4E3C)
    val path = Path().apply {
        moveTo(c.x - r * 0.9f, c.y - r * 0.05f); lineTo(c.x - r * 0.35f, c.y + r * 0.08f); lineTo(c.x - r * 0.05f, c.y - r * 0.15f)
        lineTo(c.x + r * 0.35f, c.y + r * 0.1f); lineTo(c.x + r * 0.85f, c.y - r * 0.02f)
        moveTo(c.x - r * 0.05f, c.y - r * 0.15f); lineTo(c.x + r * 0.08f, c.y - r * 0.4f)
        moveTo(c.x - r * 0.35f, c.y + r * 0.08f); lineTo(c.x - r * 0.45f, c.y + r * 0.3f)
    }
    drawPath(path, ink, alpha = alpha * 0.7f, style = Stroke(r * 0.05f, cap = StrokeCap.Round))
}

/** 棋子在脚下台面（或地面）上的影子：离得越高越小越淡。 */
fun DrawScope.drawPieceShadow(at: Offset, scale: Float, height: Float) {
    val k = (1f - height * 0.45f).coerceIn(0.35f, 1f)
    val rx = PLAYER_RADIUS * scale * ELLIPSE_X * 1.25f * k
    val ry = PLAYER_RADIUS * scale * ELLIPSE_Y * 1.25f * k
    val c = at + Offset(rx * 0.25f, ry * 0.15f)
    drawOval(Color.Black, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = 0.28f * k)
}

// ── 特效 ──

/** 世界坐标里的小颗粒：落地扬尘、蓄力时往身上聚的光点、台子塌掉的碎块。 */
class Mote(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    var life: Float, val maxLife: Float, val color: Color, val size: Float, val gravity: Float,
)

/** 落地在台面上荡开的一圈。 */
class Ripple(val x: Float, val z: Float, val y: Float, var t: Float, val strong: Boolean)

/** 世界坐标里往上飘的字。 */
class Popup(val text: String, val x: Float, val y: Float, val z: Float, var t: Float, val color: Color)

fun stepMotes(motes: MutableList<Mote>, dt: Float) {
    val it = motes.iterator()
    while (it.hasNext()) {
        val m = it.next()
        m.life -= dt
        if (m.life <= 0f) { it.remove(); continue }
        m.vy -= m.gravity * dt
        m.x += m.vx * dt
        m.y += m.vy * dt
        m.z += m.vz * dt
    }
}

fun DrawScope.drawMotes(motes: List<Mote>, iso: Iso) {
    for (m in motes) {
        val k = (m.life / m.maxLife).coerceIn(0f, 1f)
        drawCircle(m.color, m.size * iso.scale * (0.5f + 0.5f * k), iso.p(m.x, m.z, m.y), alpha = k)
    }
}

fun DrawScope.drawRipples(ripples: List<Ripple>, iso: Iso) {
    for (r in ripples) {
        val k = r.t
        val rad = (0.12f + k * if (r.strong) 0.55f else 0.3f) * iso.scale
        val c = iso.p(r.x, r.z, r.y)
        drawOval(
            Color.White, Offset(c.x - rad * ELLIPSE_X, c.y - rad * ELLIPSE_Y), Size(rad * ELLIPSE_X * 2, rad * ELLIPSE_Y * 2),
            alpha = (1f - k) * if (r.strong) 0.9f else 0.5f, style = Stroke(iso.scale * 0.02f * (1f - k * 0.5f)),
        )
    }
}

/** 分叉时标出两块候选台：瞄着的那块一圈亮环 + 上下跳的箭头，另一块淡淡一圈。 */
fun DrawScope.drawForkMarks(game: HopGame, iso: Iso, time: Float) {
    if (game.targets.size < 2 || game.phase == HopPhase.FLYING || game.phase == HopPhase.FALLING) return
    val aim = game.aimTarget
    for (t in game.targets) {
        val chosen = t === aim
        val c = iso.p(t.x, t.z, 0f)
        val r = t.half * 1.15f * iso.scale
        val pulse = if (chosen) 1f + 0.06f * sin(time * 8f) else 1f
        drawOval(
            Color.White, Offset(c.x - r * ELLIPSE_X * pulse, c.y - r * ELLIPSE_Y * pulse), Size(r * ELLIPSE_X * 2 * pulse, r * ELLIPSE_Y * 2 * pulse),
            alpha = if (chosen) 0.95f else 0.35f, style = Stroke(iso.scale * if (chosen) 0.025f else 0.012f),
        )
        if (chosen) {
            val bob = abs(sin(time * 5f)) * iso.scale * 0.08f
            val tip = c + Offset(0f, -iso.scale * 0.35f - bob)
            val w = iso.scale * 0.08f
            drawPath(Path().apply {
                moveTo(tip.x - w, tip.y - w); lineTo(tip.x + w, tip.y - w); lineTo(tip.x, tip.y + w * 0.3f); close()
            }, Color.White)
        }
    }
    // 蓄力时从脚下到瞄准那块画一道虚线，一眼看出往哪跳
    if (game.phase == HopPhase.CHARGING) {
        val from = iso.p(game.px, game.pz, 0f)
        val to = iso.p(aim.x, aim.z, 0f)
        val n = 10
        for (i in 1 until n) {
            if (i % 2 == 0) continue
            drawLine(Color.White, from + (to - from) * (i / n.toFloat()), from + (to - from) * ((i + 1) / n.toFloat()), iso.scale * 0.02f, StrokeCap.Round, alpha = 0.8f)
        }
    }
}

/** 起跳后脚下那块台子弹回来：衰减振荡。 */
fun bounceOffset(t: Float, amp: Float): Float = amp * exp(-7f * t) * cos(22f * t)
