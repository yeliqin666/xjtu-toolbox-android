package com.xjtu.toolbox.agent.bot.cast

import com.xjtu.toolbox.agent.bot.clamp01
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/*
 * 屁岱角色的统一画法。底栏 60 点、聊天头像 26 点，用户只看得清大块形状和眼睛，所以：
 * - 每个角色只截取最好认的部分，一两块纯色大形状，不描边、不渐变、不画缩小后会糊的细节；
 * - 眼睛统一用经典屁岱的竖胶囊眼；身体颜色够深够艳时镂空（和经典一样），浅色脸上改用深色填充；
 * - 笑点靠动作演，不靠画细节。
 */

const val EYE_DARK = 0xFF2B2024
const val WHITE = 0xFFFFFFFF

/** 竖胶囊眼的半宽、半高、两眼中心距的一半。比例取自经典屁岱（EYE_W / EYE_H / EYE_SPLIT）。 */
const val EYE_HW = 9.5
const val EYE_HH = 20.5
const val EYE_GAP = 27.0

/** [ink] = 0 时镂空，否则用这个颜色填。 */
private fun Sketch.eyeFill(ink: Long) = if (ink == 0L) fill(Tone.HOLE) else fill(ink)

private fun Sketch.eyeStroke(width: Double, ink: Long) =
    if (ink == 0L) stroke(width, Tone.HOLE) else stroke(width, ink)

/** 两只竖胶囊眼。[openL] / [openR] 0..1（眨眼是竖直压扁），[size] 整体缩放。 */
fun Sketch.eyes(
    cx: Double, cy: Double, openL: Double, openR: Double,
    size: Double = 1.0, gap: Double = EYE_GAP, ink: Long = 0L,
) {
    for (i in 0..1) {
        val o = clamp01(if (i == 0) openL else openR)
        val hw = EYE_HW * size
        val h = maxOf(EYE_HH * 2 * size * o, 5.0 * size)
        rrect(cx + (2 * i - 1) * gap * size, cy, hw * 2, h, minOf(hw, h / 2))
        eyeFill(ink)
    }
}

/** 挤眼「> <」。 */
fun Sketch.squint(cx: Double, cy: Double, size: Double = 1.0, gap: Double = EYE_GAP, ink: Long = 0L) {
    val w = 13.0 * size
    for (side in intArrayOf(-1, 1)) {
        val x = cx + side * gap * size
        poly(x - side * w, cy - w, x + side * w * 0.6, cy, x - side * w, cy + w, closed = false)
        eyeStroke(8.0 * size, ink)
    }
}

/** 开心眯眼「^ ^」。 */
fun Sketch.joy(cx: Double, cy: Double, size: Double = 1.0, gap: Double = EYE_GAP, ink: Long = 0L) {
    val w = 13.0 * size
    for (side in intArrayOf(-1, 1)) {
        val x = cx + side * gap * size
        moveTo(x - w, cy + w * 0.4)
        quadTo(x, cy - w * 1.1, x + w, cy + w * 0.4)
        eyeStroke(8.0 * size, ink)
    }
}

/** 睡着的眼「︶ ︶」。 */
fun Sketch.asleep(cx: Double, cy: Double, size: Double = 1.0, gap: Double = EYE_GAP, ink: Long = 0L) {
    val w = 13.0 * size
    for (side in intArrayOf(-1, 1)) {
        val x = cx + side * gap * size
        moveTo(x - w, cy - w * 0.2)
        quadTo(x, cy + w * 0.9, x + w, cy - w * 0.2)
        eyeStroke(8.0 * size, ink)
    }
}

/**
 * 提醒角标：所有角色同一个位置（画布右上角、身体外面）、同一个样子（白边蓝心），
 * 弹出时冲高一点。必须在所有变换之外调用。
 */
fun Sketch.badge(amount: Double, t: Double) {
    if (amount <= 0.01) return
    val rr = 10.0 * (1.0 + 0.18 * bump(t / 0.35)) * amount
    dot(BADGE_X, BADGE_Y, rr + 3.5, Tone.COLOR, 1.0, WHITE)
    dot(BADGE_X, BADGE_Y, rr, Tone.COLOR, 1.0, ALERT_BLUE)
}

private const val BADGE_X = 84.0
private const val BADGE_Y = -86.0

/** 超椭圆半径：[n] = 2 是椭圆，越大越方。给 [Sketch.blob] 用。 */
fun superR(a: Double, rx: Double, ry: Double, n: Double): Double {
    val c = abs(cos(a)) / rx
    val s = abs(sin(a)) / ry
    return (c.pow(n) + s.pow(n)).pow(-1.0 / n)
}
