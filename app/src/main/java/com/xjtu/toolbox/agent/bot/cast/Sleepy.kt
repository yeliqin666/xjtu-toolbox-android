package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 早八人：一颗永远睡不醒的大学生脑袋，呆毛、黑眼圈、鼻涕泡。
 * 待命钓鱼式点头、泡泡跟着胀缩；微动打哈欠；思考拿两根火柴棍撑着眼皮硬撑；提醒一激灵、泡泡啪地破；
 * 被戳像被点名：弹起来举手「到！」，然后又慢慢睡回去；彩蛋彻底睡死冒 Zzz，最后打个大哈欠。
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

    private const val EY = 38.0

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        // 钓鱼：4.8 秒慢慢垂下去，猛一抬头
        val u = (now % 4.8) / 4.8
        val droop = if (u < 0.86) ss(u / 0.86) else 1 - ss((u - 0.86) / 0.06)
        val jerk = if (u > 0.86) bump((u - 0.86) / 0.12) else 0.0

        val called = poke * (1 - ss(ramp(tp, 1.1, 2.1)))
        val awake = max(max(think, alert), called)
        val dead = combo * window(tc, 0.0, 0.5, 2.3, 2.8)
        val yawn = micro * bump(tm / 1.6) + combo * bump((tc - 2.3) / 1.0)
        val nod = droop * (1 - awake) * (1 - dead) + 1.6 * dead
        val jump = poke * 8 * bump(tp / 0.35) + alert * 6 * bump(ta / 0.3)

        // 火柴棍撑着，眼皮还在一点点往下掉，掉到底又硬撑开
        val tph = (tt % 2.0) / 2.0
        val forced = if (tph < 0.88) 0.95 - 0.45 * ss(tph / 0.88) else mix(0.5, 0.95, ss((tph - 0.88) / 0.12))
        var open = mix(0.12 + 0.35 * jerk, forced, think)
        open = max(open, max(alert, called))
        open *= p.blink * (1 - dead) * (1 - yawn)

        // 被点名：左右张望一下
        val glance = poke * (if (tp in 0.3..0.6) -1.0 else if (tp in 0.6..0.9) 1.0 else 0.0)
        val lookX = p.lookX * 0.6 + glance

        // 举手「到！」：从脑袋后面高高举起来
        val hand = poke * window(tp, 0.06, 0.24, 1.0, 1.4)
        if (hand > 0.01) {
            val hy = mix(40.0, -64.0, hand) - jump
            s.group {
                translate(76.0, hy)
                rotate(12 * (1 - hand) + 4 * sin(tp * 14) * hand)
                s.rrect(4.0, 44.0, 28.0, 60.0, 13.0)
                s.fill(SLEEVE)
                s.rrect(0.0, 0.0, 32.0, 38.0, 15.0)
                s.fill(SKIN)
            }
        }

        s.group {
            translate(0.0, 10 * nod - jump)
            rotate(6 * nod - 4 * yawn + think * sin(now * 23) * 0.6)

            // 呆毛：被吓到时「啵」地弹
            val boing = 26 * wobble(tp, 22.0, 5.0) * poke + 18 * wobble(ta, 22.0, 5.0) * alert
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
            // 黑眼圈
            for (side in intArrayOf(-1, 1)) {
                s.moveTo(ex + side * 25 - 13, EY + 18)
                s.quadTo(ex + side * 25, EY + 27, ex + side * 25 + 13, EY + 18)
                s.stroke(6.0, BAGS)
            }
            when {
                dead > 0.2 || (yawn > 0.3) -> s.asleep(ex, EY + 4, 0.85, 25.0, EYE_DARK)
                open < 0.18 -> s.asleep(ex, EY + 2, 0.85, 25.0, EYE_DARK)
                else -> for (side in intArrayOf(-1, 1)) droopyEye(s, ex + side * 25, EY, open, called)
            }

            // 火柴棍：一端红头，竖着卡在眼皮之间
            if (think > 0.01) {
                val h = EYE_HH * 0.85 * forced + 10
                for (side in intArrayOf(-1, 1)) {
                    val x = ex + side * 25 + side * 2
                    s.line(x, EY - h, x, EY + h)
                    s.stroke(6.5, STICK, think)
                    s.dot(x, EY - h - 2, 5.0, Tone.COLOR, think, MATCH)
                }
            }

            // 哈欠
            if (yawn > 0.02) {
                s.ellipse(lookX * 4, 74.0, 9 + 6 * yawn, 4 + 13 * yawn)
                s.fill(MOUTH)
            }

            // 鼻涕泡：跟着点头胀缩；醒着就没有，被点名时啪地破，睡回去再慢慢吹起来
            val regrow = 1 - poke * (1 - ss(ramp(tp, 1.5, 2.3)))
            val br = (6 + 16 * droop + 8 * dead) * (1 - think) * (1 - alert) * regrow * (1 - yawn)
            val bx = 14.0 + br * 0.7
            val by = 64.0 + br * 0.2
            if (br > 1.5) {
                s.circle(bx, by, br)
                s.fill(BUBBLE, 0.85)
                s.dot(bx - br * 0.35, by - br * 0.35, br * 0.22, Tone.COLOR, 0.95, WHITE)
            }
            val pop = max(poke * ramp(tp, 0.0, 0.25) * (if (tp < 0.25) 1.0 else 0.0), alert * (if (ta < 0.25) ramp(ta, 0.0, 0.25) else 0.0))
            if (pop > 0.0) {
                for (k in 0 until 5) {
                    val a = k * 1.26 + 0.3
                    s.dot(30 + cos(a) * (14 + 26 * pop), 66 + sin(a) * (14 + 26 * pop), 4.0 * (1 - pop), Tone.COLOR, 0.9, BUBBLE)
                }
            }
        }

        // Zzz
        if (dead > 0.01) {
            for (k in 0 until 3) {
                val ph = ((tc - 0.4) / 1.8 + k / 3.0) % 1.0
                if (tc < 0.4 + k * 0.6) continue
                val zs = 8.0 + 6 * ph
                val zx = 48 + 26 * ph + 6 * sin(ph * 6)
                val zy = -40 - 60 * ph
                s.poly(zx - zs, zy - zs, zx + zs, zy - zs, zx - zs, zy + zs, zx + zs, zy + zs, closed = false)
                s.stroke(5.0, Z_COLOR, dead * bump(ph))
            }
        }
        s.badge(alert, ta)
    }

    /**
     * 睡不醒的眼：圆眼珠被一道厚眼皮压住上半截，[open] 越小压得越低。
     * [wide] 被点名吓醒时眼珠放大、露出高光。
     */
    private fun droopyEye(s: Sketch, x: Double, y: Double, open: Double, wide: Double) {
        val r = 12.0 * (1 + 0.2 * wide)
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
