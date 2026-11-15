package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * MJ：网上那只胖红头套，一颗又宽又扁的红脑袋加一圈双下巴，白镜片眼，开口就喊「MJ」：
 * - 待命：双下巴随呼吸颤，隔一阵小声嘟囔一句「MJ」；
 * - 微动：吸一口气，脑袋一鼓，张大嘴喊「MJ」；
 * - 思考：面前摆个饭盒，吐出超长的舌头，先甩到脸另一侧再绕回来，在饭盒里一圈圈地舔，边舔边滴口水；
 * - 提醒：两只手掌一上一下地掂，头顶飘「67」；
 * - 被戳：果冻一样晃，挤眼喊「MJ!」，连戳每下各喊一声；
 * - 彩蛋：「MJ! MJ! MJ!」一声比一声大、一下比一下蹦得高，喊完累瘫喘气冒汗；这时再戳，每下再补一声。
 * 镜片眼是唯一不用竖胶囊眼的角色：缩到底栏大小时，红脸配白镜片才认得出是谁。
 */
object Mj : CastCharacter("mj", "MJ") {
    override val microSec = 1.6
    override val pokeSec = 1.0
    override val comboSec = 3.2

    private const val RED = 0xFFE0312F
    private const val CHIN = 0xFFB5222A
    private const val WEB = 0xFF7E1219
    private const val RIM = 0xFF2A1416
    private const val MOUTH = 0xFF3A0A10
    private const val EDGE = 0xFF5A0C14
    private const val SWEAT = 0xFF8FD3FF
    private const val TONGUE = 0xFFFF6F91
    private const val GROOVE = 0xFFD9426A
    private const val TIN = 0xFFB8C2CC
    private const val TIN_DARK = 0xFF7D8894
    private const val RICE = 0xFFFFF8E7
    private const val GREENS = 0xFF6BBF59
    private const val EGG = 0xFFF2C14E

    /** 脚底：形变、蹦跳都以这里为支点。 */
    private const val BASE = 105.0
    private const val HY = 12.0
    private const val HRX = 98.0
    private const val HRY = 78.0
    private const val HN = 2.4
    private const val EYE_Y = 2.0
    private const val LENS_X = 37.0

    /** 彩蛋里第一声的时刻、每声间隔。 */
    private const val SHOUT0 = 0.2
    private const val SHOUT_GAP = 0.55
    private val SHOUT_X = doubleArrayOf(-40.0, 36.0, 0.0)
    private val SHOUT_Y = doubleArrayOf(-78.0, -84.0, -88.0)
    private val SHOUT_Z = doubleArrayOf(0.8, 1.0, 1.25)

    /** 舔饭盒时舌尖绕圈的角速度。 */
    private const val LICK = 5.0
    /** 舔饭盒时脑袋缩小、上移，给饭盒腾地方。 */
    private const val THINK_SHRINK = 0.15
    private const val THINK_RISE = 14.0
    private const val TIN_Y = 88.0

    /** 相邻两个字的中线距离。 */
    private const val ADV = 25.0

    private val mumbleAt = Beats(0x6d6a, 8.0, 14.0, 4.0)

    override fun draw(s: Sketch, p: CastPose) {
        val now = p.now
        val think = p.amt(Act.THINK); val tt = p.t(Act.THINK)
        val alert = p.amt(Act.ALERT); val ta = p.t(Act.ALERT)
        val micro = p.amt(Act.MICRO); val tm = p.t(Act.MICRO)
        val poke = p.amt(Act.POKE); val tp = p.t(Act.POKE)
        val combo = p.amt(Act.COMBO); val tc = p.t(Act.COMBO)

        // 微动：吸气 → 大喊
        val inhale = micro * window(tm, 0.0, 0.25, 0.3, 0.4)
        val yell = micro * window(tm, 0.3, 0.38, 0.95, 1.3)
        // 被戳：果冻晃、挤眼
        val jelly = poke * 0.16 * wobble(tp, 18.0, 5.0)
        val ouch = poke * window(tp, 0.0, 0.05, 0.55, 0.8)
        // 彩蛋：三声一声比一声蹦得高，喊完累瘫喘气
        var hop = 0.0
        var shout = 0.0
        for (k in 0 until 3) {
            val at = SHOUT0 + k * SHOUT_GAP
            hop += (10.0 + 8 * k) * bump((tc - at) / 0.45)
            shout = max(shout, window(tc, at, at + 0.06, at + 0.3, at + 0.45))
        }
        hop *= combo
        shout *= combo
        val tired = combo * window(tc, 1.9, 2.1, 2.85, 3.1)
        val pant = tired * (0.5 + 0.5 * sin(tc * 18))
        val again = p.kick(0.04, 0.1, 0.4)
        // 待命：偶尔嘟囔一句
        val busy = maxOf(think, alert, micro, poke, combo)
        val sinceMumble = mumbleAt.since(now)
        val mumble = (1 - busy) * window(sinceMumble, 0.0, 0.08, 0.35, 0.5)

        val open = maxOf(0.35 * mumble, yell, 0.6 * ouch, 0.85 * think, shout, 0.2 * tired + 0.3 * pant, again)
        val shrink = 1 - THINK_SHRINK * think
        val sx = shrink * (1 + 0.05 * inhale - 0.03 * yell + 0.8 * jelly + 0.05 * tired)
        val sy = shrink * (1 + 0.012 * p.breath + 0.05 * inhale + 0.08 * yell - jelly + 0.07 * shout - 0.08 * tired)
        val rot = p.lookX * 3 + alert * 5 * sin(ta * 10) + think * 3 * sin(tt * LICK)
        val rise = hop + THINK_RISE * think

        s.group {
            translate(0.0, BASE - rise + 3 * tired)
            rotate(rot)
            scale(sx, sy)
            translate(0.0, -BASE)

            // 双下巴：比脸深一号，从脸底下露出一圈
            s.ellipse(0.0, 89.0 + 1.5 * p.breath, 80.0, 17.0)
            s.fill(CHIN)
            s.blob(0.0, HY, 64) { superR(it, HRX, HRY, HN) }
            s.fill(RED)
            // 蛛网：几道放射线加一圈，压暗处理，小尺寸下只当纹理
            for (k in 0 until 8) {
                val th = (k + 0.5) * Sketch.TAU / 8
                val r = superR(th, HRX, HRY, HN) * 0.9
                s.line(0.0, HY, cos(th) * r, HY + sin(th) * r)
                s.stroke(4.5, WEB, 0.45)
            }
            s.blob(0.0, HY, 32) { superR(it, HRX, HRY, HN) * 0.55 }
            s.stroke(4.5, WEB, 0.45)

            if (open > 0.03) {
                s.ellipse(0.0, 52.0 - 2 * open, 9 + 13 * open, 3 + 17 * open)
                s.fill(MOUTH)
            }

            val ex = p.lookX * 7
            val ey = EYE_Y + p.lookY * 4 + 5 * think
            if (ouch > 0.4) {
                s.squint(ex, ey, 0.95, LENS_X / 0.95, WHITE)
            } else {
                val lid = (1 - 0.45 * think) * (1 - 0.55 * tired)
                val z = 1 + 0.12 * max(yell, shout)
                for (i in 0..1) lens(s, ex + (2 * i - 1) * LENS_X, ey, 2 * i - 1, p.eye[i] * lid, z)
            }
        }

        // 饭盒摆在脸前面：盒口、饭菜 → 舌头 → 盒身，舌尖就像探进了盒里
        if (think > 0.01) {
            tinBack(s, think)
            // 嘴的位置：跟着脑袋一起缩小上移
            val mouthY = BASE - rise - (BASE - 50.0) * shrink
            tongue(s, mouthY, tt, think)
            tinFront(s, think)
        }

        // 提醒：两只手掌一上一下地掂，做「67」
        if (alert > 0.01) {
            for (side in intArrayOf(-1, 1)) {
                s.group {
                    translate(side * 98.0, 44.0 + side * 16 * sin(ta * 10))
                    scale(alert)
                    s.ellipse(0.0, 0.0, 19.0, 12.0)
                    s.fill(CHIN)
                }
            }
            shoutText(s, "67", 0.0, -90.0 + 3 * sin(ta * 10), 1.0, alert)
        }

        if (tired > 0.01) {
            s.group {
                translate(-80.0, -30.0 + 6 * ramp(tc, 2.0, 2.9))
                s.circle(0.0, 4.0, 8.0)
                s.fill(SWEAT, tired)
                s.poly(-7.0, 0.0, 0.0, -14.0, 7.0, 0.0)
                s.fill(SWEAT, tired)
            }
        }

        float(s, "MJ", 46.0, -74.0, 0.55, sinceMumble - 0.08, 1 - busy)
        if (micro > 0.01) float(s, "MJ", 0.0, -84.0, 1.15, tm - 0.32)
        for (k in 0 until POKE_MEMORY) {
            float(s, "MJ!", -34.0 + (k % 3) * 34, -80.0, 0.75, p.pokeAgo[k] - 0.05)
            float(s, "MJ!", -34.0 + ((k + 1) % 3) * 34, -80.0, 0.75, p.kickAgo[k] - 0.05)
        }
        if (combo > 0.01) for (k in 0 until 3) {
            float(s, "MJ!", SHOUT_X[k], SHOUT_Y[k], SHOUT_Z[k], tc - SHOUT0 - k * SHOUT_GAP - 0.08, combo)
        }
        s.badge(alert, ta)
    }

    /** 一只镜片：深色框托着白镜面，内眼角往下斜。[open] 压扁表示眨眼、眯眼。 */
    private fun lens(s: Sketch, x: Double, y: Double, side: Int, open: Double, z: Double) {
        s.group {
            translate(x, y)
            rotate(-side * 20.0)
            scale(z, z * max(open, 0.12))
            s.ellipse(0.0, 0.0, 29.0, 21.0)
            s.fill(RIM)
            s.ellipse(0.0, 0.0, 23.0, 15.0)
            s.fill(WHITE)
        }
    }

    /** 饭盒后半：盒口一圈和里面的饭菜。[k] 整只缩放（淡入淡出）。 */
    private fun tinBack(s: Sketch, k: Double) {
        s.group {
            translate(0.0, TIN_Y)
            scale(k)
            s.ellipse(0.0, 0.0, 64.0, 13.0)
            s.fill(TIN_DARK)
            s.ellipse(0.0, 1.0, 57.0, 10.0)
            s.fill(RICE)
            s.ellipse(-27.0, 1.0, 15.0, 5.0)
            s.fill(GREENS)
            s.ellipse(26.0, 1.0, 15.0, 5.0)
            s.fill(EGG)
        }
    }

    /** 饭盒前半：盒身，挡住探进盒里的舌尖。 */
    private fun tinFront(s: Sketch, k: Double) {
        s.group {
            translate(0.0, TIN_Y)
            scale(k)
            s.rrect(0.0, 13.0, 128.0, 24.0, 7.0)
            s.fill(TIN)
            s.line(-56.0, 9.0, 56.0, 9.0)
            s.stroke(4.0, TIN_DARK, 0.6)
        }
    }

    /**
     * 超长舌头：从嘴里往下伸，甩到舌尖对面那一侧，再绕回饭盒里，舌尖沿盒口一圈圈地舔。
     * [k] 0..1 是伸出来多少：所有控制点从嘴里往外长。
     */
    private fun tongue(s: Sketch, mouthY: Double, tt: Double, k: Double) {
        val th = tt * LICK
        val tipX = 38 * cos(th)
        val tipY = TIN_Y - 3 + 5 * sin(th)
        val elbowX = -104 * cos(th + 0.6)
        val elbowY = 26 + 16 * sin(th)
        val xs = doubleArrayOf(0.0, 0.0, elbowX, tipX)
        val ys = doubleArrayOf(mouthY, mouthY + 20, elbowY, tipY)
        for (i in 1..3) {
            xs[i] = mix(xs[0], xs[i], k)
            ys[i] = mix(ys[0], ys[i], k)
        }
        // 口水：从拐弯处一滴滴往下掉
        for (d in 0..1) {
            val age = (tt + d * 0.55) % 1.1
            s.circle(xs[2], ys[2] + 6 + 34 * age * age, 4.5)
            s.fill(SWEAT, k * (1 - ss(ramp(age, 0.7, 1.1))))
        }
        for ((w, c) in listOf(18.0 to TONGUE, 4.0 to GROOVE)) {
            s.trace(4, false, { xs[(it * 3 + 0.5).toInt()] }, { ys[(it * 3 + 0.5).toInt()] })
            s.stroke(w, c)
        }
        s.circle(xs[3], ys[3], 10.0 * k)
        s.fill(TONGUE)
    }

    /** 喊出来的字：喊完 [age] 秒先蹦大一下再往上飘，[life] 秒消失。 */
    private fun float(s: Sketch, text: String, x: Double, y: Double, z: Double, age: Double, alpha: Double = 1.0, life: Double = 0.9) {
        if (age < 0 || age > life) return
        val a = alpha * (1 - ss(ramp(age, life * 0.6, life)))
        shoutText(s, text, x, y - 16 * age / life, z * (1 + 0.3 * bump(age / 0.18)), a)
    }

    /** 白字深边，底栏小尺寸、深浅底都看得清。只认 M J 6 7 !。 */
    private fun shoutText(s: Sketch, text: String, x: Double, y: Double, z: Double, alpha: Double) {
        if (alpha <= 0.01) return
        s.group {
            translate(x, y)
            scale(z)
            val x0 = -(text.length - 1) * ADV / 2
            for (pass in 0..1) {
                val w = if (pass == 0) 13.0 else 7.0
                val c = if (pass == 0) EDGE else WHITE
                for ((i, ch) in text.withIndex()) glyph(s, ch, x0 + i * ADV, w, c, alpha)
            }
        }
    }

    /** 一个字，高 26、以 [x] 为中线。 */
    private fun glyph(s: Sketch, ch: Char, x: Double, w: Double, c: Long, a: Double) {
        when (ch) {
            'M' -> s.poly(x - 10, 13.0, x - 10, -13.0, x, 3.0, x + 10, -13.0, x + 10, 13.0, closed = false)
            'J' -> {
                s.moveTo(x + 6, -13.0)
                s.lineTo(x + 6, 4.0)
                s.quadTo(x + 6, 14.0, x - 2, 14.0)
                s.quadTo(x - 9, 14.0, x - 9, 5.0)
            }
            '6' -> {
                s.moveTo(x + 7, -13.0)
                s.quadTo(x - 9, -9.0, x - 8, 6.0)
                s.stroke(w, c, a)
                s.circle(x, 6.0, 8.0)
            }
            '7' -> s.poly(x - 8, -13.0, x + 8, -13.0, x - 3, 13.0, closed = false)
            '!' -> {
                s.line(x, -13.0, x, 4.0)
                s.stroke(w, c, a)
                s.line(x, 12.5, x, 13.0)
            }
            else -> return
        }
        s.stroke(w, c, a)
    }
}
