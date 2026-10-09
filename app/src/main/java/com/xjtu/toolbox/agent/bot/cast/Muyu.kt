package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * 木鱼：电子木鱼那一只，红漆圆木鱼、一道横缝、右上一把木槌，敲一下飘一个金色「+1」。平时佛系闭着眼，
 * 隔一阵偷偷睁一只看一眼。动作都是整只木鱼在动：
 * - 微动：轻敲一下，木鱼一缩；
 * - 思考：赛博念经，木槌自己一下下敲，木鱼每挨一下就压扁再弹起来、跟着节奏左右摇，身后转一圈佛光；
 * - 提醒：两眼睁开，原地蹦两下，木槌跃跃欲试地抖；
 * - 被戳：狠狠挨一下，压扁、震出一圈金色冲击波，眯眼笑，连着戳每下各飘一个「+1」；
 * - 彩蛋：连敲三下功德圆满，腾空转一圈、金光四射、长出光环，再落回垫子上。
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
    private const val EYE_INK = 0xFFFFE2B0

    private const val CX = -12.0
    private const val CY = 36.0
    /** 木鱼底边到中心的距离：压扁、摇摆都以底边为支点。 */
    private const val BASE = 70.0
    private const val PIVOT_X = 100.0
    private const val PIVOT_Y = -22.0
    /** 念经的节拍。 */
    private const val BEAT = 0.55

    private val peekAt = Beats(0x3a2, 6.0, 11.0, 3.5)

    /** 一次敲击的木槌落下量：快落、慢抬。 */
    private fun strike(u: Double) = if (u < 0 || u > 1) 0.0 else if (u < 0.16) ss(u / 0.16) else 1 - ss((u - 0.16) / 0.5)

    /** 挨一下之后的形变：先压扁（正）再弹高（负），很快落定。 */
    private fun hit(age: Double, depth: Double) = if (age < 0) 0.0 else depth * exp(-age * 7) * cos(age * 19)

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        val comboKnock = (0 until 3).maxOf { strike((tc - it * 0.28) / 0.5) }
        val knock = maxOf(
            micro * 0.6 * strike(tm / 1.0),
            think * strike((tt % BEAT) / BEAT),
            poke * strike(tp / 1.0),
            combo * comboKnock * (if (tc < 0.9) 1.0 else 0.0),
        )

        // 挨敲的压扁：越敲越狠
        var sq = micro * hit(tm - 0.16, 0.12) + think * hit((tt % BEAT) - 0.08, 0.2) + poke * hit(tp - 0.08, 0.3)
        if (combo > 0.01 && tc < 0.9) for (k in 0..2) sq += combo * hit(tc - k * 0.28 - 0.08, 0.1 + 0.05 * k)
        // 落地那一墩
        sq += combo * hit(tc - 2.55, 0.25)

        // 念经时跟着节拍左右摇
        val sway = think * 9 * sin(tt * PI / BEAT)
        // 提醒：原地蹦两下
        val hop = alert * 16 * (bump(ta / 0.32) + 0.6 * bump((ta - 0.36) / 0.28))
        // 彩蛋：腾空、转一圈、落下
        val lift = combo * (ss(ramp(tc, 0.9, 1.3)) - ss(ramp(tc, 2.25, 2.55)))
        val fy = -30 * lift + lift * 4 * sin(tc * 5)
        val spin = combo * 360 * ss(ramp(tc, 1.0, 1.85))
        val burst = combo * window(tc, 0.95, 1.15, 1.9, 2.4)
        val halo = combo * window(tc, 1.2, 1.5, 2.5, 2.9)
        val happy = max(poke * window(tp, 0.06, 0.12, 0.55, 0.65), combo * window(tc, 0.9, 1.0, 2.7, 2.9))
        val glow = think * 0.85

        s.ellipse(CX, 106.0, 66.0, 11.0)
        s.fill(CUSHION)

        // 身后的佛光：念经时慢慢转的一圈短光芒（只在上半圈，免得戳出画布）
        if (glow > 0.01) {
            for (k in 0 until 7) {
                val a = PI + (k + 0.5) * PI / 7 + 0.25 * sin(tt * 0.9)
                val len = 10 + 6 * sin(tt * 4 + k)
                val r0 = 92.0
                s.line(CX + cos(a) * r0, CY - 6 + sin(a) * r0, CX + cos(a) * (r0 + len), CY - 6 + sin(a) * (r0 + len))
                s.stroke(7.0, GOLD, glow * (0.6 + 0.4 * sin(tt * 3 + k)))
            }
        }
        // 功德圆满：金光四射
        if (burst > 0.01) {
            val r0 = 60 + 30 * ss(ramp(tc, 0.95, 1.5))
            for (k in 0 until 12) {
                val a = k * PI / 6 + tc * 0.8
                s.line(CX + cos(a) * r0, CY + fy + sin(a) * r0, CX + cos(a) * (r0 + 14), CY + fy + sin(a) * (r0 + 14))
                s.stroke(7.0, GOLD, burst)
            }
        }

        s.group {
            translate(CX, CY + BASE + fy - hop)
            rotate(sway)
            scale(1 + 0.6 * sq, 1 - sq)
            translate(0.0, -BASE)
            rotate(spin)
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
                    s.circle(ex + side * 25 + p.lookX * 4, ey, 10.5 * p.eye[(side + 1) / 2].coerceAtLeast(0.3))
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
            s.ellipse(CX, CY - 54 + fy, 38.0 * halo, 9.0 * halo)
            s.stroke(8.0, GOLD, halo)
        }

        // 被戳那一下：挨敲的地方震出一圈金色冲击波
        if (poke > 0.01 && tp in 0.08..0.5) {
            val u = ramp(tp, 0.08, 0.5)
            s.circle(26.0, -20.0, 10 + 40 * u)
            s.stroke(6.0 * (1 - u) + 1, GOLD, poke * (1 - u))
        }

        // 木槌：提醒时举着抖
        val ready = alert * (16 + 5 * sin(ta * 26))
        val angle = -128 + 2 * sin(now * 1.2) - 38 * knock + ready
        s.group {
            translate(PIVOT_X, PIVOT_Y + fy * 0.5 - hop * 0.5)
            rotate(angle)
            s.line(0.0, 0.0, 58.0, 0.0)
            s.stroke(11.0, STICK)
            s.ellipse(70.0, 0.0, 15.0, 20.0)
            s.fill(HEAD)
        }

        // 功德 +1：最近每一下敲击各飘一个
        for (k in 0 until POKE_MEMORY) plusOne(s, p.pokeAgo[k] - 0.08, k)
        if (micro > 0.01) plusOne(s, tm - 0.16, 0)
        if (think > 0.01) for (k in 0..1) plusOne(s, (tt % BEAT) + k * BEAT - 0.08, k)
        if (combo > 0.01) for (k in 0..2) plusOne(s, tc - k * 0.28 - 0.08, k)
        s.badge(alert, ta)
    }

    /** 闭着的佛系眼「︶」。 */
    private fun zenEye(s: Sketch, x: Double, y: Double) {
        s.moveTo(x - 12, y - 2)
        s.quadTo(x, y + 9, x + 12, y - 2)
        s.stroke(6.5, EYE_INK)
    }

    /** 金色「+1」，深边托底，底栏小尺寸也看得清。敲完 [age] 秒，先蹦大一下再往上飘，0.9 秒消失。 */
    private fun plusOne(s: Sketch, age: Double, k: Int) {
        if (age < 0 || age > 0.9) return
        val a = 1 - ss(ramp(age, 0.5, 0.9))
        val z = 1 + 0.35 * bump(age / 0.2)
        val x = -40.0 + (k % 3) * 14
        val y = -54 - age * 44
        s.group {
            translate(x, y)
            scale(z)
            for (pass in 0..1) {
                val w = if (pass == 0) 15.0 else 8.0
                val c = if (pass == 0) GOLD_EDGE else GOLD
                s.line(-15.0, 0.0, 5.0, 0.0); s.stroke(w, c, a)
                s.line(-5.0, -10.0, -5.0, 10.0); s.stroke(w, c, a)
                s.line(18.0, -13.0, 18.0, 13.0); s.stroke(w, c, a)
                s.line(12.0, -7.0, 18.0, -13.0); s.stroke(w, c, a)
            }
        }
    }
}
