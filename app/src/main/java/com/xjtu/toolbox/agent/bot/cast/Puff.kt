package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 噗：一团嫩绿色的屁，头上飘着三道臭味线，屁股后面拖两小团。待命慢慢胀缩、臭味线一缕缕往上飘；
 * 微动打个小嗝漏一小团；思考憋气越憋越鼓、憋不住了漏一点；提醒鼓起来；
 * 被戳「噗」地泄气，像松了口的气球乱窜再瘪着鼓回来；彩蛋炸成一圈小屁，聚回来还打个嗝。
 */
object Puff : CastCharacter("puff", "噗") {
    override val microSec = 1.4
    override val pokeSec = 1.9
    override val comboSec = 2.8

    private const val GAS = 0xFF9CD45C
    private const val STINK = 0xFF6FA83A

    private const val R = 74.0

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        // 憋气：3 秒一轮，越憋越鼓，最后 15% 漏掉
        val hu = (tt % 3.0) / 3.0
        val hold = think * (if (hu < 0.85) ss(hu / 0.85) else 1 - ss((hu - 0.85) / 0.15))
        val leakT = think * (if (hu >= 0.85) (hu - 0.85) / 0.15 else -1.0)
        val hiccup = micro * bump((tm - 0.1) / 0.4)

        // 泄气乱窜
        val fu = ramp(tp, 0.1, 1.05)
        val flying = poke * (if (tp in 0.1..1.05) 1.0 else 0.0)
        val deflate = poke * (0.42 * ss(ramp(tp, 0.1, 0.95)) - 0.42 * ss(ramp(tp, 1.1, 1.6)) +
            0.06 * wobble(tp - 1.5, 14.0, 6.0) * (if (tp > 1.5) 1.0 else 0.0))
        val fx = poke * 30 * sin(fu * Sketch.TAU * 2.2 + 0.3) * bump(fu)
        val fy = poke * 24 * sin(fu * Sketch.TAU * 3.1 + 1.2) * bump(fu)
        val spin = poke * 720 * ss(fu)

        // 彩蛋：散成一圈再聚回来
        val gone = combo * window(tc, 0.12, 0.28, 1.7, 1.9)
        val spread = combo * 78 * ss(ramp(tc, 0.15, 0.6)) * (1 - ss(ramp(tc, 1.4, 1.9)))
        val burp = combo * bump((tc - 2.1) / 0.35)

        val S = (1 + 0.03 * sin(now * 1.6) + 0.11 * hold + 0.1 * alert + 0.06 * hiccup) * (1 - deflate) * (1 - gone)
        val tremble = hold * 2.5 + alert * 1.5
        val cx = fx + sin(now * 61) * tremble
        val cy = 12 + 4 * sin(now * 1.1) - 8 * hiccup - 8 * burp + fy + cos(now * 53) * tremble

        // 臭味线：三道波浪往上飘，一道接一道
        val stinkA = (1 - flying) * (1 - gone) * (1 - hold)
        if (stinkA > 0.01) {
            for (k in 0 until 3) {
                val ph = (now * 0.45 + k / 3.0) % 1.0
                val x0 = cx + (k - 1) * 34.0
                val y0 = cy - R * S - 2 - ph * 12
                s.trace(11, false, { u -> x0 + 5 * sin(u * Sketch.TAU * 1.5 + now * 2 + k) }, { u -> y0 - u * 32 })
                s.stroke(7.0, STINK, stinkA * bump(ph))
            }
        }

        if (S > 0.02) {
            s.group {
                translate(cx, cy)
                rotate(spin)
                // 屁股后面拖着的两小团
                s.circle(-R * S * 0.98, R * S * 0.62, 17 * S)
                s.fill(GAS)
                s.circle(-R * S * 1.22, R * S * 0.86, 10 * S)
                s.fill(GAS)
                // 云边：几个往外鼓的小团
                s.blob(0.0, 0.0, 70) { th ->
                    R * S * (0.93 + 0.1 * abs(sin(3.5 * th + now * 0.3)) + 0.025 * sin(2 * th - now * 0.25))
                }
                s.fill(GAS)

                val ex = p.lookX * 12 * S
                val ey = (-2 + p.lookY * 6) * S
                when {
                    flying > 0.5 || hold > 0.35 -> s.squint(ex, ey, S * 0.9)
                    burp > 0.3 -> s.joy(ex, ey, S * 0.9)
                    else -> s.eyes(ex, ey, p.eye[0], p.eye[1], S * 0.9 * (1 + 0.1 * alert + 0.15 * hiccup))
                }
            }
        }

        // 乱窜时身后一串小气团
        if (flying > 0.01) {
            for (k in 1..3) {
                val u = (fu - k * 0.05).coerceAtLeast(0.0)
                val x = 30 * sin(u * Sketch.TAU * 2.2 + 0.3) * bump(u)
                val y = 12 + 24 * sin(u * Sketch.TAU * 3.1 + 1.2) * bump(u)
                s.circle(x, y, 11.0 - k * 2.2)
                s.fill(GAS, flying * (0.85 - k * 0.2))
            }
        }
        // 嗝、漏气：一小团往后上方飘走
        val belch = maxOf(if (micro > 0.01) ramp(tm, 0.2, 1.2) else 0.0, leakT)
        if (belch > 0 && belch < 1) {
            s.circle(cx - 80 - belch * 20, cy + 40 - belch * 40, 9 + belch * 8)
            s.fill(GAS, 0.85 * (1 - belch))
        }
        if (combo > 0.01) {
            val u = ramp(tc, 2.1, 2.7)
            if (u > 0 && u < 1) {
                s.circle(cx + 4, cy - R - u * 30, 8 + u * 8)
                s.fill(GAS, combo * 0.85 * (1 - u))
            }
        }
        // 散开的一圈小屁，每团都有一双小眼睛
        if (spread > 2) {
            for (k in 0 until 7) {
                val a = k * Sketch.TAU / 7 + tc * 0.6
                val x = cos(a) * spread
                val y = 12 + sin(a) * spread * 0.85
                s.circle(x, y, 20.0)
                s.fill(GAS)
                s.eyes(x, y, 1.0, 1.0, 0.32, 22.0)
            }
        }
        s.badge(alert, ta)
    }
}
