package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 早八人：一颗永远睡不醒的大学生脑袋，呆毛、黑眼圈、鼻涕泡。动作都是整颗头在动：
 * - 待命：钓鱼式点头，泡泡跟着胀缩；
 * - 微动：仰头打个大哈欠，嘴张到半张脸；
 * - 思考：火柴棍撑着眼皮硬撑，头还是一点点往下栽，栽到底猛地弹回来，三个「Z」绕着脑袋转；
 * - 提醒：一激灵，整颗头弹一下、泡泡啪地破；
 * - 被戳：像被点名，先一缩再蹦起来举手「到！」，落地一墩，左右张望，然后又慢慢睡回去；
 * - 彩蛋：整颗头侧着栽倒趴在课本上睡死，冒 Zzz，最后爬起来打个哈欠。
 */
object Sleepy : CastCharacter("sleepy", "早八人") {
    override val microSec = 1.8
    override val pokeSec = 2.4
    override val comboSec = 3.4

    private const val HAIR = 0xFF3A3346
    private const val SKIN = 0xFFFFD7B5
    private const val SLEEVE = 0xFF6FA8E8
    private const val BAGS = 0xFFB9A3D6
    private const val BUBBLE = 0xFFAEDBFF
    private const val MOUTH = 0xFF8A3B45
    private const val STICK = 0xFFA8733F
    private const val MATCH = 0xFFE5484D
    private const val Z_COLOR = 0xFF6C7BD0
    private const val BOOK = 0xFF5B8DEF
    private const val PAGES = 0xFFF4F1EA

    private const val EY = 38.0
    /** 下巴：压扁、点头都以这里为支点。 */
    private const val CHIN = 92.0
    /** 思考时栽头的一个来回。 */
    private const val NOD = 2.4

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        // 待命钓鱼：4.8 秒慢慢垂下去，猛一抬头
        val u = (now % 4.8) / 4.8
        val droop = if (u < 0.86) ss(u / 0.86) else 1 - ss((u - 0.86) / 0.06)
        val jerk = if (u > 0.86) bump((u - 0.86) / 0.12) else 0.0

        // 思考：头一点点往下栽，栽到底猛地弹回来
        val c = (tt % NOD) / NOD
        val sink = think * (if (c < 0.72) ss(c / 0.72) else 0.0)
        val snapAge = if (c >= 0.72) (c - 0.72) * NOD else (c + 0.28) * NOD
        val snap = think * wobble(snapAge, 15.0, 5.0)

        // 被点名：先一缩，蹦起来，落地一墩
        val crouch = poke * window(tp, 0.0, 0.05, 0.07, 0.12)
        val air = poke * bump((tp - 0.08) / 0.34)
        val thud = poke * wobble(tp - 0.42, 18.0, 6.0)
        val called = poke * (1 - ss(ramp(tp, 1.1, 2.1)))
        val lookAround = poke * (if (tp in 0.55..1.05) sin((tp - 0.55) / 0.5 * Sketch.TAU) else 0.0)

        // 彩蛋：侧着栽倒趴在课本上，最后爬起来
        val fall = combo * ss(ramp(tc, 0.05, 0.4)) * (1 - ss(ramp(tc, 2.45, 2.9)))
        val dead = combo * window(tc, 0.0, 0.3, 2.4, 2.7)
        val book = combo * window(tc, 0.0, 0.25, 2.8, 3.2)
        val fallBounce = combo * wobble(tc - 0.4, 14.0, 5.0) * (if (tc < 2.4) 1.0 else 0.0)

        val yawn = micro * bump(tm / 1.6) + combo * bump((tc - 2.5) / 0.85)
        val jolt = alert * wobble(ta, 20.0, 5.0)
        val startle = alert * bump(ta / 0.3)
        val awake = max(max(think, alert), called)
        val nod = droop * (1 - awake) * (1 - dead)

        // 整颗头的形变：以下巴为支点
        val ty = 8 + 8 * nod + 6 * sink - 9 * air + 2 * fall - 9 * startle
        val sy = 1 - 0.2 * crouch + 0.03 * air - 0.14 * thud + 0.1 * snap - 0.06 * sink + 0.04 * yawn + 0.08 * jolt
        val sx = 1 + (1 - sy) * 0.6
        val rot = 6 * nod + 16 * sink - 10 * snap - 9 * yawn + 9 * lookAround - 6 * jolt +
            think * sin(now * 23) * 0.6
        // 侧倒：绕脸中心转，不然整颗头会甩出画布
        val topple = -78 * fall + 6 * fallBounce

        // 被戳时的眼皮：全睁，越到后面越困
        val tph = if (c < 0.72) mix(0.95, 0.35, ss(c / 0.72)) else 0.95
        var open = mix(0.12 + 0.35 * jerk, tph, think)
        open = max(open, max(alert, called))
        open *= p.blink * (1 - dead) * (1 - yawn)

        val glance = poke * (if (tp in 0.55..0.8) -1.0 else if (tp in 0.8..1.05) 1.0 else 0.0)
        val lookX = p.lookX * 0.6 + glance

        // 课本：趴着睡的那本
        if (book > 0.01) {
            s.rrect(-10.0, 104.0, 170.0 * book, 18.0, 5.0)
            s.fill(BOOK)
            s.rrect(-10.0, 98.0, 156.0 * book, 8.0, 3.0)
            s.fill(PAGES)
        }

        // 绕着脑袋转的三个 Z：转到后面的先画
        if (think > 0.01) for (k in 0 until 3) {
            val a = tt * 2.6 + k * Sketch.TAU / 3
            if (sin(a) < 0) zOrbit(s, a, think)
        }

        s.group {
            translate(0.0, CHIN + ty)
            rotate(rot)
            scale(sx, sy)
            translate(0.0, -CHIN)
            translate(0.0, 10.0)
            rotate(topple)
            translate(0.0, -10.0)

            // 举手「到！」：从脑袋后面高高举起来
            val hand = poke * window(tp, 0.1, 0.26, 1.0, 1.4)
            if (hand > 0.01) {
                s.group {
                    translate(76.0, mix(40.0, -60.0, hand))
                    rotate(12 * (1 - hand) + 5 * sin(tp * 16) * hand)
                    s.rrect(4.0, 44.0, 28.0, 60.0, 13.0)
                    s.fill(SLEEVE)
                    s.rrect(0.0, 0.0, 32.0, 38.0, 15.0)
                    s.fill(SKIN)
                }
            }

            // 呆毛：吓到时「啵」地弹
            val boing = 24 * wobble(tp - 0.08, 22.0, 5.0) * poke + 20 * jolt + 14 * snap
            s.group {
                translate(4.0, -70.0)
                rotate(boing - 8 * nod)
                s.moveTo(0.0, 0.0)
                s.cubicTo(4.0, -16.0, 22.0, -26.0, 20.0, -35.0)
                s.cubicTo(18.0, -41.0, 9.0, -39.0, 11.0, -32.0)
                s.stroke(9.0, HAIR)
            }

            s.circle(0.0, 6.0, 84.0)
            s.fill(HAIR)
            s.ellipse(0.0, 34.0, 70.0, 58.0)
            s.fill(SKIN)
            // 刘海
            s.moveTo(-72.0, 12.0)
            val bangs = doubleArrayOf(-52.0, -6.0, -38.0, 12.0, -18.0, -8.0, 2.0, 10.0, 22.0, -8.0, 40.0, 12.0, 54.0, -6.0, 72.0, 12.0)
            var i = 0
            while (i < bangs.size) { s.lineTo(bangs[i], bangs[i + 1]); i += 2 }
            s.lineTo(70.0, -36.0)
            s.lineTo(-70.0, -36.0)
            s.close()
            s.fill(HAIR)
            s.stroke(6.0, HAIR)

            val ex = lookX * 10
            for (side in intArrayOf(-1, 1)) {
                s.moveTo(ex + side * 25 - 13, EY + 18)
                s.quadTo(ex + side * 25, EY + 27, ex + side * 25 + 13, EY + 18)
                s.stroke(6.0, BAGS)
            }
            when {
                dead > 0.2 || yawn > 0.3 -> s.asleep(ex, EY + 4, 0.85, 25.0, EYE_DARK)
                open < 0.18 -> s.asleep(ex, EY + 2, 0.85, 25.0, EYE_DARK)
                else -> for (side in intArrayOf(-1, 1)) droopyEye(s, ex + side * 25, EY, open, max(called, alert))
            }

            // 火柴棍：一端红头，竖着卡在眼皮之间
            if (think > 0.01) {
                val h = EYE_HH * 0.85 * tph + 10
                for (side in intArrayOf(-1, 1)) {
                    val x = ex + side * 27
                    s.line(x, EY - h, x, EY + h)
                    s.stroke(6.5, STICK, think)
                    s.dot(x, EY - h - 2, 5.0, Tone.COLOR, think, MATCH)
                }
            }

            // 哈欠：嘴张到半张脸
            if (yawn > 0.02) {
                s.ellipse(lookX * 4, 70.0, 10 + 16 * yawn, 4 + 18 * yawn)
                s.fill(MOUTH)
            }

            // 鼻涕泡：跟着点头胀缩；醒着就没有，被吓醒时啪地破，睡回去再慢慢吹起来
            val regrow = 1 - poke * (1 - ss(ramp(tp, 1.5, 2.3)))
            val br = (6 + 16 * droop + 10 * dead) * (1 - think) * (1 - alert) * regrow * (1 - yawn)
            val bx = 14.0 + br * 0.7
            val by = 64.0 + br * 0.2
            if (br > 1.5) {
                s.circle(bx, by, br)
                s.fill(BUBBLE, 0.85)
                s.dot(bx - br * 0.35, by - br * 0.35, br * 0.22, Tone.COLOR, 0.95, WHITE)
            }
            val pop = max(poke * (if (tp < 0.25) ramp(tp, 0.0, 0.25) else 0.0), alert * (if (ta < 0.25) ramp(ta, 0.0, 0.25) else 0.0))
            if (pop > 0.0) {
                for (k in 0 until 6) {
                    val a = k * 1.05 + 0.3
                    s.dot(30 + cos(a) * (14 + 30 * pop), 66 + sin(a) * (14 + 30 * pop), 5.0 * (1 - pop), Tone.COLOR, 0.9, BUBBLE)
                }
            }
        }

        if (think > 0.01) for (k in 0 until 3) {
            val a = tt * 2.6 + k * Sketch.TAU / 3
            if (sin(a) >= 0) zOrbit(s, a, think)
        }

        // 睡死时往上飘的 Zzz
        if (dead > 0.01) {
            for (k in 0 until 3) {
                if (tc < 0.4 + k * 0.5) continue
                val ph = ((tc - 0.4) / 1.6 + k / 3.0) % 1.0
                val zs = 8.0 + 8 * ph
                zLetter(s, 40 + 40 * ph + 6 * sin(ph * 6), -10 - 80 * ph, zs, dead * bump(ph))
            }
        }
        s.badge(alert, ta)
    }

    /** 绕着脑袋转的 Z：近大远小。 */
    private fun zOrbit(s: Sketch, a: Double, amount: Double) {
        val depth = sin(a)
        zLetter(s, cos(a) * 98, -62 + depth * 20, 9 + 4 * depth, amount * (0.6 + 0.4 * depth))
    }

    private fun zLetter(s: Sketch, x: Double, y: Double, r: Double, alpha: Double) {
        s.poly(x - r, y - r, x + r, y - r, x - r, y + r, x + r, y + r, closed = false)
        s.stroke(5.0, Z_COLOR, alpha)
    }

    /**
     * 睡不醒的眼：圆眼珠被一道厚眼皮压住上半截，[open] 越小压得越低。
     * [wide] 吓醒时眼珠放大、露出高光。
     */
    private fun droopyEye(s: Sketch, x: Double, y: Double, open: Double, wide: Double) {
        val r = 12.0 * (1 + 0.25 * wide)
        s.circle(x, y + 2, r)
        s.fill(EYE_DARK)
        if (open > 0.6) s.dot(x + r * 0.35, y + 2 - r * 0.35, r * 0.28, Tone.COLOR, 1.0, WHITE)
        if (open < 0.97) {
            val top = y + 2 - r - 6
            val lid = y + 2 + r - 2 * r * open
            s.rrect(x, (top + lid) / 2, 2 * r + 8, lid - top, 2.0)
            s.fill(SKIN)
            s.line(x - r - 3, lid, x + r + 3, lid)
            s.stroke(5.0, EYE_DARK)
        }
    }
}
