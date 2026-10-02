package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.cos
import kotlin.math.sin

/**
 * 喵：一颗橘色虎斑猫头。粉耳朵、额头三道虎斑、白嘴套、粉鼻头、「ω」嘴，黄绿色竖瞳猫眼。
 * 待命偶尔抖耳朵、瞳孔跟着视线；微动是猫式慢眨眼；思考时盯着绕头转的毛线球、瞳孔放大成圆的；
 * 提醒竖耳、瞳孔放大；被戳是「猫猫震惊」表情包：一蹦、瞪圆眼、瞳孔缩成一点、小嘴「o」；彩蛋缩成一块猫猫面包。
 */
object Cat : CastCharacter("cat", "喵") {
    override val microSec = 1.6
    override val pokeSec = 1.0
    override val comboSec = 2.6

    private const val FUR = 0xFFF2A65A
    private const val STRIPE = 0xFFD97F2E
    private const val EAR_IN = 0xFFFFB3B8
    private const val MUZZLE = 0xFFFFF3E3
    private const val NOSE = 0xFFFF8A95
    private const val IRIS = 0xFFB9DA4E
    private const val MOUTH = 0xFF6B3324
    private const val YARN = 0xFFFF7FA8

    private val twitchL = Beats(0xca71, 4.0, 9.0, 2.0)
    private val twitchR = Beats(0xca72, 5.0, 10.0, 4.5)

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        // 震惊：一下蹦起来，定住，再慢慢松下来
        val shock = poke * window(tp, 0.0, 0.06, 0.6, 0.85)
        val hop = poke * 5 * bump(tp / 0.3)
        val loaf = combo * window(tc, 0.0, 0.4, 2.0, 2.5)
        val perk = maxOf(alert, shock) * (1 - loaf)

        val ang = tt * 2.4
        val lookX = mix(p.lookX, cos(ang), think)
        val lookY = mix(p.lookY, 0.5 * sin(ang), think)

        val rx = mix(100.0, 108.0, loaf)
        val ry = mix(76.0, 58.0, loaf) * (1 + 0.012 * p.breath)
        val cy = mix(24.0, 40.0, loaf)

        if (think > 0.01 && sin(ang) < 0) yarn(s, ang, think)

        s.group {
            // 以下巴为支点
            translate(0.0, cy + ry - hop)
            scale(1 + 0.05 * shock, 1 + 0.03 * shock + 0.03 * wobble(tp - 0.06, 30.0, 6.0) * poke)
            rotate(p.lookX * 4 * (1 - shock) + think * 6 * cos(ang))
            translate(0.0, -ry)

            val twL = 16 * wobble(twitchL.since(now), 30.0, 8.0)
            val twR = 16 * wobble(twitchR.since(now), 30.0, 8.0)
            ear(s, -1, rx, ry, twL, perk, loaf)
            ear(s, 1, rx, ry, twR, perk, loaf)
            s.blob(0.0, 0.0) { superR(it, rx, ry, 2.2) }
            s.fill(FUR)

            // 额头三道虎斑
            for (k in -1..1) {
                val x = k * 20.0
                s.line(x, -ry + 6, x * 0.8, -ry + (if (k == 0) 30.0 else 22.0))
                s.stroke(8.0, STRIPE)
            }

            // 白嘴套、粉鼻头、ω 嘴
            val my = ry * 0.42
            for (side in intArrayOf(-1, 1)) {
                s.ellipse(side * 17.0, my, 25.0, 19.0)
                s.fill(MUZZLE)
            }
            s.moveTo(-8.0, my - 14)
            s.lineTo(8.0, my - 14)
            s.lineTo(0.0, my - 5)
            s.close()
            s.fill(NOSE)
            s.stroke(5.0, NOSE)
            if (shock > 0.3) {
                s.ellipse(0.0, my + 10, 6.0, 8.0 * shock)
                s.fill(MOUTH)
            } else {
                s.moveTo(-15.0, my + 1)
                s.quadTo(-7.5, my + 10, 0.0, my - 1)
                s.quadTo(7.5, my + 10, 15.0, my + 1)
                s.stroke(4.5, MOUTH)
            }

            val ex = lookX * 4 * (1 - shock)
            val ey = -ry * 0.12 + 4 * loaf
            when {
                loaf > 0.3 -> s.joy(ex, ey, 1.0, 36.0, MOUTH)
                else -> {
                    // 慢眨眼：慢慢闭上、停一下、慢慢睁开
                    val slow = micro * window(tm, 0.0, 0.5, 0.9, 1.4)
                    // 瞳孔：平时竖条，兴奋（思考盯球、提醒）放大成圆，震惊缩成一点
                    val dilate = maxOf(think, alert) * (1 - shock)
                    for (i in 0..1) {
                        val side = if (i == 0) -1 else 1
                        catEye(s, ex + side * 36.0, ey, p.eye[i] * (1 - slow), lookX, lookY, dilate, shock)
                    }
                }
            }
        }

        if (think > 0.01 && sin(ang) >= 0) yarn(s, ang, think)
        s.badge(alert, ta)
    }

    /** 猫眼：深色眼眶 + 黄绿虹膜 + 竖瞳 + 高光。[open] 太小就画成闭眼弧线。 */
    private fun catEye(s: Sketch, x: Double, y: Double, open: Double, lx: Double, ly: Double, dilate: Double, shock: Double) {
        val r = 16.0 * (1 + 0.25 * shock)
        if (open < 0.22) {
            s.moveTo(x - r, y)
            s.quadTo(x, y + r * 0.7, x + r, y)
            s.stroke(6.0, MOUTH)
            return
        }
        val ry = (r + 2) * open
        s.ellipse(x, y, r + 3.5, ry + 3.5)
        s.fill(EYE_DARK)
        s.ellipse(x, y, r, ry)
        s.fill(if (shock > 0.3) WHITE else IRIS)
        val px = x + lx * r * 0.3 * (1 - shock)
        val py = y + ly * ry * 0.2 * (1 - shock)
        val pw = mix(mix(4.5, 10.0, dilate), 4.0, shock)
        val ph = mix(mix(r * 0.85, r * 0.8, dilate), 4.0, shock) * open
        s.ellipse(px, py, pw, ph)
        s.fill(EYE_DARK)
        s.dot(px + pw * 0.4 + 2, py - ph * 0.45, 3.6, Tone.COLOR, 1.0, WHITE)
    }

    /** 耳朵：底边贴在头顶，内侧一块粉。[twitch] 抖动角度，[perk] 竖高。 */
    private fun ear(s: Sketch, side: Int, rx: Double, ry: Double, twitch: Double, perk: Double, loaf: Double) {
        s.group {
            translate(side * rx * 0.54, -ry * 0.7)
            rotate(side * (16 + 16 * loaf) + twitch * side)
            val h = (48 + 8 * perk) * (1 - 0.35 * loaf)
            s.poly(-30.0, 22.0, -4.0 * side, -h, 30.0, 22.0)
            s.fill(FUR)
            s.stroke(14.0, FUR)
            s.poly(-15.0, 16.0, -3.0 * side, -h * 0.6, 15.0, 16.0)
            s.fill(EAR_IN)
            s.stroke(6.0, EAR_IN)
        }
    }

    private fun yarn(s: Sketch, ang: Double, amount: Double) {
        val r = 13.0 + 3 * sin(ang)
        s.circle(cos(ang) * 94, 24 + 34 * sin(ang), r * amount)
        s.fill(YARN)
    }
}
