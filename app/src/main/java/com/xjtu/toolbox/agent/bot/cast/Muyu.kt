package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.max
import kotlin.math.sin

/**
 * 木鱼：电子木鱼那一只，红漆圆木鱼、一道横缝、右上一把木槌，敲一下飘一个金色「+1」。
 * 平时佛系闭着眼，隔一阵偷偷睁一只看一眼；微动轻敲一下；思考是赛博念经，木槌自己有节奏地敲个不停；
 * 提醒木槌跃跃欲试地抖、两眼睁开；
 * 被戳敲一下、木鱼一震、眯眼笑，连着戳每下各飘一个「+1」；彩蛋连敲三下功德圆满，头顶长出光环飘起来。
 */
object Muyu : CastCharacter("muyu", "木鱼") {
    override val microSec = 1.2
    override val pokeSec = 1.0
    override val comboSec = 3.0

    private const val WOOD = 0xFF9E3F25
    private const val SHEEN = 0xFFC9704C
    private const val SLOT = 0xFF4A1A0E
    private const val CUSHION = 0xFFD94848
    private const val STICK = 0xFFE3B378
    private const val HEAD = 0xFF8B4A22
    private const val GOLD = 0xFFFFB627
    private const val GOLD_EDGE = 0xFF8A5A00

    private const val CX = -12.0
    private const val CY = 36.0
    private const val PIVOT_X = 100.0
    private const val PIVOT_Y = -22.0

    private const val EYE_INK = 0xFFFFE2B0

    private val peekAt = Beats(0x3a2, 6.0, 11.0, 3.5)

    /** 闭着的佛系眼「︶」。 */
    private fun zenEye(s: Sketch, x: Double, y: Double) {
        s.moveTo(x - 12, y - 2)
        s.quadTo(x, y + 9, x + 12, y - 2)
        s.stroke(6.5, EYE_INK)
    }

    /** 一次敲击的木槌落下量：快落、慢抬。 */
    private fun strike(u: Double) = if (u < 0 || u > 1) 0.0 else if (u < 0.16) ss(u / 0.16) else 1 - ss((u - 0.16) / 0.5)

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        val beat = 0.55
        val comboKnock = (0 until 3).maxOf { strike((tc - it * 0.28) / 0.5) }
        val knock = maxOf(
            micro * 0.6 * strike(tm / 1.0),
            think * strike((tt % beat) / beat),
            poke * strike(tp / 1.0),
            combo * comboKnock,
        )
        val ready = alert * sin(ta * 26) * 4
        val angle = -128 + 2 * sin(now * 1.2) - 38 * knock + ready
        // 被敲的那一下木鱼一震
        val hitAge = minOf(if (poke > 0.01) tp - 0.08 else 9.0, if (think > 0.01) (tt % beat) - 0.08 else 9.0)
        val sq = 0.1 * wobble(hitAge, 22.0, 7.0) + combo * 0.08 * wobble((tc - 0.08) % 0.28, 22.0, 7.0) * (if (tc < 0.9) 1.0 else 0.0)

        val float = combo * window(tc, 0.9, 1.4, 2.3, 2.9)
        val fy = -22 * float + float * 3 * sin(tc * 4)
        val halo = combo * window(tc, 0.85, 1.1, 2.4, 2.9)
        val happy = max(poke * window(tp, 0.06, 0.12, 0.55, 0.65), combo * window(tc, 0.85, 1.0, 2.6, 2.8))

        s.ellipse(CX, 106.0, 66.0, 11.0)
        s.fill(CUSHION)

        s.group {
            translate(CX, CY + 70 + fy)
            scale(1 + sq, 1 - sq)
            translate(0.0, -70.0)
            // 顶上的小提手
            s.rrect(0.0, -66.0, 40.0, 26.0, 12.0)
            s.fill(WOOD)
            s.blob(0.0, 0.0) { superR(it, 86.0, 70.0 * (1 + 0.008 * p.breath), 2.3) }
            s.fill(WOOD)
            // 漆面反光
            s.moveTo(-70.0, -14.0)
            s.quadTo(-62.0, -52.0, -24.0, -60.0)
            s.stroke(10.0, SHEEN)
            // 开口：一道横贯的细长缝，木鱼最认人的地方
            s.moveTo(-66.0, 26.0)
            s.quadTo(0.0, 40.0, 66.0, 26.0)
            s.stroke(11.0, SLOT)

            // 眼睛：平时佛系闭着，隔一阵偷偷睁一只看一眼；提醒才两只都睁开
            val ex = p.lookX * 8
            val ey = -14.0
            val peek = (1 - think) * (1 - alert) * window(peekAt.since(now), 0.0, 0.12, 0.9, 1.05)
            when {
                happy > 0.4 -> s.joy(ex, ey - 2, 0.9, EYE_GAP, EYE_INK)
                alert > 0.4 -> for (side in intArrayOf(-1, 1)) {
                    s.circle(ex + side * 25 + p.lookX * 4, ey, 9.5 * p.eye[(side + 1) / 2].coerceAtLeast(0.3))
                    s.fill(EYE_INK)
                }
                else -> {
                    zenEye(s, ex - 25, ey)
                    if (peek > 0.5) {
                        s.circle(ex + 25 + p.lookX * 3, ey, 8.5)
                        s.fill(EYE_INK)
                    } else {
                        zenEye(s, ex + 25, ey)
                    }
                }
            }
        }

        if (halo > 0.01) {
            s.ellipse(CX, CY - 52 + fy, 36.0, 9.0)
            s.stroke(8.0, GOLD, halo)
        }

        // 木槌
        s.group {
            translate(PIVOT_X, PIVOT_Y + fy)
            rotate(angle)
            s.line(0.0, 0.0, 58.0, 0.0)
            s.stroke(11.0, STICK)
            s.ellipse(70.0, 0.0, 15.0, 20.0)
            s.fill(HEAD)
        }

        // 功德 +1：最近每一下敲击各飘一个
        for (k in 0 until POKE_MEMORY) plusOne(s, p.pokeAgo[k] - 0.08, k)
        if (micro > 0.01) plusOne(s, tm - 0.16, 0)
        if (think > 0.01) for (k in 0..1) plusOne(s, (tt % beat) + k * beat - 0.08, k)
        if (combo > 0.01) for (k in 0..2) plusOne(s, tc - k * 0.28 - 0.08, k)
        s.badge(alert, ta)
    }

    /** 金色「+1」，深边托底，底栏小尺寸也看得清。敲完 [age] 秒，往上飘 0.9 秒消失。 */
    private fun plusOne(s: Sketch, age: Double, k: Int) {
        if (age < 0 || age > 0.9) return
        val a = 1 - ss(ramp(age, 0.5, 0.9))
        val x = -40.0 + (k % 3) * 14
        val y = -54 - age * 44
        for (pass in 0..1) {
            val w = if (pass == 0) 15.0 else 8.0
            val c = if (pass == 0) GOLD_EDGE else GOLD
            s.line(x - 15, y, x + 5, y); s.stroke(w, c, a)
            s.line(x - 5, y - 10, x - 5, y + 10); s.stroke(w, c, a)
            s.line(x + 18, y - 13, x + 18, y + 13); s.stroke(w, c, a)
            s.line(x + 12, y - 7, x + 18, y - 13); s.stroke(w, c, a)
        }
    }
}
