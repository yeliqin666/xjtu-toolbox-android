package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * 咕：侧身朝右的一只胖鸽子，脑袋直接长在圆滚滚的身子上、没有脖子。喉咙一圈绿紫流光、翅尖两道黑杠、
 * 小黑喙加白鼻瘤、黑豆豆眼。
 * 待命原地走、脑袋一伸一缩（鸽子走路的招牌动作）。动作都是整只鸽子在动：
 * - 微动：「咕——」，胸鼓起来一大圈、深深鞠两个躬；
 * - 思考：整只往前一栽一栽地啄地，啄一下溅起几粒米；
 * - 提醒：蹦一下，挺直伸长脖子瞪眼；
 * - 被戳：放你鸽子，扑棱着飞到角落里悬着看你，再若无其事地落回来；
 * - 彩蛋：直接飞走，只留一根羽毛，过一会儿从左边踱回来。
 */
object Pigeon : CastCharacter("pigeon", "咕") {
    override val microSec = 1.6
    override val pokeSec = 2.4
    override val comboSec = 4.2
    override val leavesCanvas = true

    private const val BODY = 0xFFAFB9CC
    private const val WING = 0xFF8E9AB2
    private const val BAR = 0xFF4D5462
    private const val TAIL = 0xFF6E7787
    private const val HEAD = 0xFFB4BED1
    private const val TEAL = 0xFF46B29A
    private const val PURPLE = 0xFF9A7AD6
    private const val CERE = 0xFFF2EFE8
    private const val BEAK = 0xFF4A4C57
    private const val FOOT = 0xFFFF7E8A
    private const val SEED = 0xFFE2B866

    /** 啄一下的周期。 */
    private const val PECK = 0.42

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        var px = 0.0
        var py = 0.0
        var k = 1.0
        var flap = 0.0
        var walk = 1.0
        var gone = false
        if (poke > 0.01) {
            when {
                tp < 0.12 -> py += 8 * ss(tp / 0.12)
                tp < 0.7 -> {
                    val u = ss(ramp(tp, 0.12, 0.7))
                    px = 44 * u; py = -48 * u; k = 1 - 0.45 * u; flap = 1.0; walk = 0.0
                }
                tp < 1.4 -> { px = 44.0; py = -48.0 + 4 * sin(tp * 18); k = 0.55; flap = 1.0; walk = 0.0 }
                tp < 2.1 -> {
                    val u = ss(ramp(tp, 1.4, 2.1))
                    px = 44 * (1 - u); py = -48 * (1 - u); k = 0.55 + 0.45 * u; flap = 1 - ramp(tp, 1.9, 2.1); walk = 0.0
                }
                else -> py += 6 * bump((tp - 2.1) / 0.25)
            }
        }
        if (combo > 0.01) {
            when {
                tc < 0.1 -> py += 8 * ss(tc / 0.1)
                tc < 0.8 -> {
                    val u = ss(ramp(tc, 0.1, 0.8))
                    px = 170 * u.pow(1.5); py = -110 * u; k = 1 - 0.4 * u; flap = 1.0; walk = 0.0
                }
                tc < 2.4 -> gone = true
                // 从左边踱回来，步子快一点
                else -> { px = -180 * (1 - ss(ramp(tc, 2.4, 3.9))); walk = if (tc < 3.9) 1.5 else 1.0 }
            }
        }

        // 掉下来的一根羽毛
        val fu = if (poke > 0.01) ramp(tp, 0.3, 1.9) else if (combo > 0.01) ramp(tc, 0.3, 2.6) else 0.0
        if (fu > 0 && fu < 1) {
            s.group {
                translate(14 * sin(fu * Sketch.TAU * 1.5), -40 + fu * 120)
                rotate(35 * sin(fu * Sketch.TAU * 1.5))
                s.moveTo(0.0, -20.0)
                s.quadTo(13.0, -2.0, 0.0, 20.0)
                s.quadTo(-13.0, -2.0, 0.0, -20.0)
                s.close()
                s.fill(BODY, 1 - ss(ramp(fu, 0.7, 1.0)))
            }
        }
        if (gone) {
            s.badge(alert, ta)
            return
        }

        // 走路：脑袋先往前伸、再缩回来，身子跟着一步一晃
        val period = if (walk > 1.2) 0.5 else 0.8
        val hb = (now % period) / period
        val w = walk.coerceAtMost(1.0) * (1 - think)
        val thrust = (if (hb < 0.22) mix(-9.0, 11.0, ss(hb / 0.22)) else mix(11.0, -9.0, (hb - 0.22) / 0.78)) * w
        val step = sin(now * Sketch.TAU / period) * w
        val pp = (tt % PECK) / PECK
        val peck = think * abs(sin(pp * Math.PI)).pow(3)
        // 咕——：胸鼓起来，深深鞠两个躬
        val coo = micro * window(tm, 0.0, 0.2, 1.3, 1.6)
        val bow = micro * (bump(tm / 0.7) + bump((tm - 0.75) / 0.7))
        val up = alert * (0.8 + 0.2 * sin(ta * 5))
        val hopA = alert * 14 * bump(ta / 0.35)
        val wide = maxOf(poke * window(tp, 0.0, 0.05, 0.5, 0.7), alert)

        // 啄一下溅起几粒米
        if (think > 0.01 && pp > 0.5) {
            val age = (pp - 0.5) * PECK
            for (i in 0 until 3) {
                val vx = 30.0 + i * 22
                val vy = -60.0 - i * 18
                s.dot(92 + vx * age, 96 + vy * age + 260 * age * age, 4.0, Tone.COLOR, think * (1 - ramp(age, 0.12, 0.21)), SEED)
            }
        }

        s.group {
            translate(px, py - hopA)
            scale(k)
            // 整只鸽子以脚为支点：啄的时候往前栽，咕的时候鞠躬，提醒时挺直伸长
            translate(0.0, 100.0)
            rotate(11 * peck + 14 * bow)
            scale(1 - 0.05 * up, 1 + 0.1 * up)
            translate(0.0, -100.0)

            // 粉脚丫，一抬一落
            if (flap < 0.5) {
                for ((i, fx) in doubleArrayOf(-12.0, 16.0).withIndex()) {
                    val lift = maxOf(0.0, step * (if (i == 0) 1 else -1)) * 6
                    s.line(fx, 86.0, fx + 4, 100.0 - lift)
                    s.stroke(7.0, FOOT)
                    s.line(fx - 2, 101.0 - lift, fx + 14, 101.0 - lift)
                    s.stroke(7.0, FOOT)
                }
            }

            // 短尾巴
            s.poly(-80.0, 30.0, -112.0, 42.0, -108.0, 60.0, -78.0, 56.0)
            s.fill(TAIL)
            s.stroke(8.0, TAIL)

            // 胖身子：一整块圆面包，鼓胸时再胀一圈
            val puff = 1 + 0.16 * coo
            s.blob(-8.0, 38.0 + step * 1.5) { superR(it, 90.0 * puff, 62.0 * puff, 2.4) }
            s.fill(BODY)

            // 翅膀：叠在身侧，翅尖两道黑杠；飞的时候绕肩膀扑棱
            s.group {
                translate(-2.0, 22.0 + step * 1.5)
                rotate(-6.0 + flap * (-55 + 45 * sin(now * 38)))
                s.moveTo(14.0, 0.0)
                s.cubicTo(10.0, 34.0, -40.0, 44.0, -80.0, 22.0)
                s.cubicTo(-56.0, 2.0, -20.0, -10.0, 14.0, 0.0)
                s.close()
                s.fill(WING)
                for (bx in doubleArrayOf(-42.0, -60.0)) {
                    s.moveTo(bx + 6, 4.0)
                    s.quadTo(bx - 4, 18.0, bx + 2, 32.0)
                    s.stroke(8.0, BAR)
                }
            }

            // 脑袋：长在身子上的一个圆包，没有脖子；走路一伸一缩，啄的时候往前下方扎，提醒时抬头
            s.group {
                translate(38.0 + thrust + 10 * peck, -24.0 + 40 * peck + 6 * bow - 16 * up)
                rotate(30 * peck + 12 * bow - 8 * up)
                s.circle(0.0, 0.0, 44.0)
                s.fill(HEAD)
                // 喉咙一圈绿紫流光
                s.ellipse(-10.0, 34.0, 28.0 * puff, 12.0)
                s.fill(PURPLE)
                s.ellipse(-6.0, 24.0, 30.0 * puff, 12.0)
                s.fill(TEAL)
                // 小黑喙 + 白鼻瘤
                s.poly(38.0, -4.0, 58.0, 4.0, 38.0, 10.0)
                s.fill(BEAK)
                s.stroke(6.0, BEAK)
                s.ellipse(39.0, -4.0, 8.0, 6.0)
                s.fill(CERE)
                // 豆豆眼；吓到、提醒时眼白一圈瞪圆
                val ex = 16.0 + p.lookX * 3
                val ey = -10.0 + p.lookY * 2
                val open = if (peck > 0.6) 0.0 else p.blink
                if (open < 0.3) {
                    s.moveTo(ex - 8, ey)
                    s.quadTo(ex, ey + 6, ex + 8, ey)
                    s.stroke(5.0, EYE_DARK)
                } else {
                    if (wide > 0.3) {
                        s.ellipse(ex, ey, 14.0 * wide, 14.0 * wide * open)
                        s.fill(WHITE)
                    }
                    val r = 8.0 * (1 - 0.25 * wide)
                    s.ellipse(ex, ey, r, r * 1.15 * open)
                    s.fill(EYE_DARK)
                    if (open > 0.6) s.dot(ex + 2.5, ey - 3.0, 2.6, Tone.COLOR, 1.0, WHITE)
                }
            }
        }
        s.badge(alert, ta)
    }
}
