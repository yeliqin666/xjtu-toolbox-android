package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 仓鼠：一颗奶茶色的圆脑袋，小圆耳、白嘴套、两团腮帮，怀里抱着一颗瓜子。屯粮的梗全在腮帮上：
 * - 待命：隔一阵嚼两口，两边腮帮轮流一鼓一鼓；
 * - 微动：把瓜子塞进嘴里，一边腮帮鼓起来，过一会儿又摸出一颗新的；
 * - 思考：跑滚轮，身后的轮子转起来，脑袋跟着步子左右颠、两只爪子轮流刨；
 * - 提醒：耳朵竖起来往上一蹿，把瓜子举高递给你；
 * - 被戳：一口把瓜子吞了，两边腮帮瞬间鼓成球、眼睛挤成「> <」，再慢慢瘪回去；
 * - 彩蛋：腮帮憋到最大直哆嗦，然后噗噗噗把屯的瓜子全喷出来，喷完腮帮瘪了、一脸懵地眨两下眼，
 *   再摸出一颗新的；喷的时候再戳，每下多喷一颗。
 */
object Hamster : CastCharacter("hamster", "仓鼠") {
    override val microSec = 2.0
    override val pokeSec = 1.4
    override val comboSec = 3.2

    private const val FUR = 0xFFE6AC72
    private const val CHEEK = 0xFFF5CB9C
    private const val CAP = 0xFFC98A52
    private const val CREAM = 0xFFFFF5E6
    private const val EAR_IN = 0xFFFFB3B8
    private const val NOSE = 0xFFFF8A95
    private const val BLUSH = 0xFFFF9C9C
    private const val MOUTH = 0xFF6B3324
    private const val PAW = 0xFFFFDCC6
    private const val SEED = 0xFF4B4038
    private const val SEED_STRIPE = 0xFFEDE3D0
    private const val WHEEL = 0xFF9FB0C8

    /** 脚底：压扁、蹿高、摇晃都以这里为支点。 */
    private const val BASE = 100.0
    private const val EYE_Y = 10.0
    private const val EYE_X = 32.0

    /** 彩蛋里开始喷、喷完的时刻，一共喷几颗、每颗隔多久。 */
    private const val SPRAY_AT = 0.5
    private const val SPRAY_END = 1.6
    private const val SHOTS = 7
    private const val SHOT_GAP = 0.16

    private val chewAt = Beats(0x4a57, 3.0, 6.5, 1.2)
    private val twitchL = Beats(0x4a58, 5.0, 10.0, 2.6)
    private val twitchR = Beats(0x4a59, 6.0, 11.0, 5.0)

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        // 微动：瓜子举到嘴边、塞进一边腮帮
        val lift = micro * window(tm, 0.1, 0.45, 0.6, 0.85)
        val stuffed = micro * window(tm, 0.45, 0.6, 1.3, 1.7)
        // 被戳：一口吞下，腮帮鼓成球还颤两下
        val gulp = poke * window(tp, 0.0, 0.1, 0.7, 1.3)
        val jiggle = poke * 0.18 * wobble(tp - 0.1, 20.0, 6.0)
        val press = poke * window(tp, 0.0, 0.06, 0.15, 0.4)
        // 彩蛋：憋气 → 连喷 → 发懵
        val charge = combo * ss(ramp(tc, 0.0, 0.45)) * (1 - ss(ramp(tc, SPRAY_AT, SPRAY_END)))
        val spraying = combo * window(tc, SPRAY_AT, SPRAY_AT + 0.05, SPRAY_END, SPRAY_END + 0.15)
        val dazed = combo * window(tc, SPRAY_END, SPRAY_END + 0.2, 2.5, 2.8)
        var recoil = 0.0
        for (k in 0 until SHOTS) recoil += wobble(tc - SPRAY_AT - k * SHOT_GAP, 26.0, 14.0)
        recoil *= combo
        // 喷的时候又被戳：腮帮再鼓一下、多喷一颗
        val again = p.kick(0.05, 0.12, 0.45)

        val spring = alert * wobble(ta, 15.0, 4.5)
        val stride = think * sin(tt * 16)
        val busy = maxOf(think, alert, poke, combo, micro)
        val chew = (1 - busy) * window(chewAt.since(now), 0.0, 0.15, 1.1, 1.3)
        val munch = sin(now * 19)

        val both = gulp + jiggle + charge * (1.0 + 0.03 * sin(tc * 45)) + 0.5 * again - 0.12 * dazed
        val puffL = (0.1 * chew * max(0.0, munch) + 0.55 * stuffed + both).coerceAtMost(1.05)
        val puffR = (0.1 * chew * max(0.0, -munch) + both).coerceAtMost(1.05)

        // 怀里那颗瓜子：被吃掉、吞掉、喷光、跑轮子时都没有
        val gone = maxOf(
            micro * window(tm, 0.45, 0.55, 1.5, 1.85),
            poke * window(tp, 0.0, 0.06, 1.0, 1.3),
            combo * window(tc, 0.0, 0.1, 2.6, 3.0),
            think,
        )
        val seedY = mix(76.0, 48.0, lift) - 20 * alert

        // 滚轮：在身后转，脑袋缩小一圈站在轮子底
        if (think > 0.01) {
            s.circle(0.0, 12.0, 104.0)
            s.stroke(9.0, WHEEL, think)
            for (k in 0 until 12) {
                val a = k * Sketch.TAU / 12 + tt * 5
                s.line(cos(a) * 88, 12 + sin(a) * 88, cos(a) * 104, 12 + sin(a) * 104)
                s.stroke(6.0, WHEEL, think)
            }
        }

        val sy = 1 + 0.08 * spring - 0.07 * press + 0.05 * recoil
        val size = 1 - 0.1 * think
        s.group {
            translate(3 * stride, BASE - 3 * think * (1 - stride * stride))
            rotate(p.lookX * 3 + 3 * stride + 8 * dazed + 1.2 * charge * sin(tc * 50))
            scale(size * (1 + (1 - sy) * 0.5), size * sy)
            translate(0.0, -BASE)

            val perk = maxOf(alert, again)
            for (side in intArrayOf(-1, 1)) {
                val tw = 14 * wobble((if (side < 0) twitchL else twitchR).since(now), 30.0, 8.0)
                s.group {
                    translate(side * 56.0, -46.0 - 7 * perk)
                    rotate(side * tw)
                    s.circle(0.0, 0.0, 22.0)
                    s.fill(FUR)
                    s.circle(0.0, 3.0, 11.5)
                    s.fill(EAR_IN)
                }
            }

            s.blob(0.0, 20.0, 64) { th -> superR(th, 90.0, 80.0 * (1 + 0.01 * p.breath), 2.3) }
            s.fill(FUR)
            // 头顶一块深色的毛
            s.ellipse(0.0, -50.0, 21.0, 12.0)
            s.fill(CAP)

            // 腮帮：越鼓越往两边撑，把整张脸撑成梨形
            for (side in intArrayOf(-1, 1)) {
                val pf = if (side < 0) puffL else puffR
                s.ellipse(side * (48 + 6 * pf), 54 - 2 * pf, 36 + 20 * pf, 30 + 16 * pf)
                s.fill(CHEEK)
            }
            s.ellipse(0.0, 52.0, 30.0, 24.0)
            s.fill(CREAM)
            for (side in intArrayOf(-1, 1)) {
                val pf = if (side < 0) puffL else puffR
                s.ellipse(side * (60 + 10 * pf), 46.0, 11.0, 6.5)
                s.fill(BLUSH, 0.7)
            }

            val ex = p.lookX * 7
            val ey = EYE_Y + p.lookY * 3
            if (gulp > 0.4 || charge > 0.4) {
                s.squint(ex, ey, 0.9, EYE_X / 0.9, EYE_DARK)
            } else {
                val k = 0.82 + 0.12 * perk
                // 发懵：眨两下
                val blank = 1 - combo * max(bump((tc - 1.95) / 0.22), bump((tc - 2.3) / 0.22))
                val lid = blank * (1 - 0.2 * think)
                s.eyes(ex, ey, p.eye[0] * lid, p.eye[1] * lid, k, EYE_X / k, EYE_DARK)
                for (i in 0..1) {
                    if (p.eye[i] * lid > 0.6) s.dot(ex + (2 * i - 1) * EYE_X + 2.5, ey - 9 * k, 3.4, Tone.COLOR, 1.0, WHITE)
                }
            }

            s.ellipse(ex * 0.4, 37.0, 6.0, 4.5)
            s.fill(NOSE)
            if (spraying > 0.3 || again > 0.3) {
                s.ellipse(0.0, 48.0, 6.0, 7.0)
                s.fill(MOUTH)
            } else {
                val my = 44 + 1.5 * chew * munch
                s.moveTo(-8.0, my)
                s.quadTo(-4.0, my + 6, 0.0, my)
                s.quadTo(4.0, my + 6, 8.0, my)
                s.stroke(3.5, MOUTH)
            }

            if (gone < 0.95) seed(s, 0.0, seedY, 0.0, 1 - gone, 1.0)
            for (side in intArrayOf(-1, 1)) {
                s.circle(side * (15.0 + 7 * think), seedY + 12 + side * 7 * stride, 10.0)
                s.fill(PAW)
            }
        }

        // 喷出去的瓜子：左右交替、一颗颗抛出去
        if (combo > 0.01) for (k in 0 until SHOTS) fly(s, tc - SPRAY_AT - k * SHOT_GAP, k)
        for (k in 0 until POKE_MEMORY) fly(s, p.kickAgo[k] - 0.05, k + 3)
        s.badge(alert, ta)
    }

    /** 一颗瓜子：深色壳，中间一道浅纹。 */
    private fun seed(s: Sketch, x: Double, y: Double, rot: Double, k: Double, alpha: Double) {
        s.group {
            translate(x, y)
            rotate(rot)
            scale(k)
            s.ellipse(0.0, 0.0, 8.5, 14.0)
            s.fill(SEED, alpha)
            s.line(0.0, -7.0, 0.0, 7.0)
            s.stroke(3.5, SEED_STRIPE, alpha)
        }
    }

    /** 从嘴里喷出去的第 [k] 颗瓜子，喷出 [age] 秒：抛物线飞出去、边飞边转，落下时淡掉。 */
    private fun fly(s: Sketch, age: Double, k: Int) {
        if (age < 0 || age > FLIGHT) return
        val u = age / FLIGHT
        val dir = if (k % 2 == 0) -1 else 1
        val x = dir * (14 + (66 + 8 * (k % 3)) * u)
        val y = 46 - (200 + 20 * (k % 2)) * u + 230 * u * u
        seed(s, x, y, dir * (300 * u + 40 * k), 1.0, 1 - ss(ramp(u, 0.7, 1.0)))
    }

    private const val FLIGHT = 0.75
}
