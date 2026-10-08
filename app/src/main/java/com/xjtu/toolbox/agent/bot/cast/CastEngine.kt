package com.xjtu.toolbox.agent.bot.cast

import com.xjtu.toolbox.agent.bot.Easings
import com.xjtu.toolbox.agent.bot.clamp01
import com.xjtu.toolbox.agent.bot.liveliness
import com.xjtu.toolbox.agent.bot.winkLid
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 角色的动作。待命不算动作：什么都没在播就是待命。
 * 一次性动作（微动、戳、连戳彩蛋）从静息姿态开始、回到静息姿态结束；持续动作（思考、提醒）淡入淡出。
 */
enum class Act(val persistent: Boolean) {
    MICRO(false),
    THINK(true),
    ALERT(true),
    POKE(false),
    COMBO(false),
}

/** 交给角色作画的一帧输入。对象复用，角色只读。 */
class CastPose {
    var now = 0.0

    /** 共用眨眼时刻表：1 睁，0 闭。 */
    var blink = 1.0

    /** 每只眼的睁度（含眨眼与单眼眨），0 = 屏幕左边那只。 */
    val eye = DoubleArray(2)

    /** 视线，-1..1，负为左 / 上，含静息漂移。 */
    var lookX = 0.0
    var lookY = 0.0

    /** 慢半拍的视线：慢性子的角色用。 */
    var slowX = 0.0
    var slowY = 0.0

    /** 呼吸，-1..1，周期 3.4s。 */
    var breath = 0.0

    /** 最近几下戳距今多少秒，0 是最近一下；没戳过是一个很大的数。木鱼连敲时每下各飘一个「+1」。 */
    val pokeAgo = DoubleArray(POKE_MEMORY)

    internal val level = DoubleArray(Act.entries.size)
    internal val time = DoubleArray(Act.entries.size)

    /** 这个动作此刻的分量 0..1（淡入淡出中介于两者之间）。 */
    fun amt(a: Act): Double = level[a.ordinal]

    /** 这个动作开播以来的秒数。 */
    fun t(a: Act): Double = time[a.ordinal]
}

/** 一个屁岱角色：给定一帧输入，在 [Sketch] 上画出自己。必须是 [CastPose] 的纯函数。 */
abstract class CastCharacter(val id: String, val label: String) {
    /** 各一次性动作的时长（秒），决定底栏多久落回待命。 */
    open val microSec = 1.6
    open val pokeSec = 0.8
    open val comboSec = 2.6

    /** 戳 / 彩蛋会不会跑出画布（鸽子飞走）。只影响单测的越界检查。 */
    open val leavesCanvas = false

    abstract fun draw(s: Sketch, p: CastPose)

    fun seconds(act: Act): Double = when (act) {
        Act.MICRO -> microSec
        Act.POKE -> pokeSec
        Act.COMBO -> comboSec
        else -> 0.0
    }
}

/**
 * 角色引擎：管动作的切换与淡入淡出、眨眼、视线，然后让角色作画。
 * 与经典屁岱的 BotEngine 同一套时间语义：时刻由调用方给，同一时刻采样结果确定。
 */
class CastEngine(private val cast: CastCharacter) {
    private val n = Act.entries.size
    private val on = BooleanArray(n)
    private val switchAt = DoubleArray(n) { -10.0 }
    private val from = DoubleArray(n)
    private val start = DoubleArray(n) { -10.0 }
    private val pokeTimes = DoubleArray(POKE_MEMORY) { NEVER }

    private var lookTX = 0.0
    private var lookTY = 0.0
    private var lx = 0.0
    private var ly = 0.0
    private var sx = 0.0
    private var sy = 0.0
    private var last = -1.0
    private var winkEye = -1
    private var winkAt = -10.0

    val pose = CastPose()

    /** 切到 [act]（null = 待命）。[replay] 为真时同一个一次性动作从头再播。 */
    fun play(act: Act?, now: Double, replay: Boolean = false) {
        for (a in Act.entries) {
            val i = a.ordinal
            if (a == act) {
                if (!on[i]) {
                    from[i] = if (a.persistent) level(i, now) else 1.0
                    switchAt[i] = now
                    on[i] = true
                    start[i] = now
                    if (a == Act.POKE) remember(now)
                } else if (replay && !a.persistent) {
                    start[i] = now
                    if (a == Act.POKE) remember(now)
                }
            } else if (on[i]) {
                from[i] = level(i, now)
                switchAt[i] = now
                on[i] = false
            }
        }
    }

    private fun remember(now: Double) {
        System.arraycopy(pokeTimes, 0, pokeTimes, 1, POKE_MEMORY - 1)
        pokeTimes[0] = now
    }

    /** 回到初始：缩略图冻结采样前用。 */
    fun reset() {
        on.fill(false)
        from.fill(0.0)
        switchAt.fill(-10.0)
        start.fill(-10.0)
        pokeTimes.fill(NEVER)
        lx = 0.0; ly = 0.0; sx = 0.0; sy = 0.0
        last = -1.0
        winkEye = -1
    }

    fun lookAt(x: Double, y: Double) {
        lookTX = x
        lookTY = y
    }

    fun wink(eye: Int, now: Double) {
        winkEye = eye
        winkAt = now
    }

    private fun level(i: Int, now: Double): Double {
        val k = now - switchAt[i]
        val a = Act.entries[i]
        return if (on[i]) {
            if (!a.persistent) 1.0
            else from[i] + (1.0 - from[i]) * Easings.easeInOutCubic(clamp01(k / FADE_IN))
        } else {
            from[i] * (1.0 - Easings.easeInOutCubic(clamp01(k / FADE_OUT)))
        }
    }

    fun sample(now: Double, s: Sketch) {
        val life = liveliness(now)
        val dt = if (last < 0) 1.0 else (now - last).coerceIn(0.0, 0.1)
        last = now
        val fast = if (dt >= 1.0) 1.0 else 1.0 - exp(-dt * 10.0)
        val slow = if (dt >= 1.0) 1.0 else 1.0 - exp(-dt * 1.4)
        lx += (lookTX - lx) * fast
        ly += (lookTY - ly) * fast
        sx += (lookTX - sx) * slow
        sy += (lookTY - sy) * slow

        val p = pose
        p.now = now
        p.blink = life.lid
        for (i in 0..1) p.eye[i] = min(life.lid, if (i == winkEye) winkLid(now - winkAt) else 1.0)
        p.lookX = (lx + life.dYaw / 40.0).coerceIn(-1.2, 1.2)
        p.lookY = (ly - life.dPitch / 40.0).coerceIn(-1.2, 1.2)
        p.slowX = sx
        p.slowY = sy
        p.breath = sin(now / 3.4 * Sketch.TAU)
        for (k in 0 until POKE_MEMORY) p.pokeAgo[k] = now - pokeTimes[k]
        for (i in 0 until n) {
            p.level[i] = level(i, now)
            p.time[i] = (now - start[i]).coerceAtLeast(0.0)
        }
        s.reset()
        cast.draw(s, p)
    }

    private companion object {
        const val FADE_IN = 0.4
        const val FADE_OUT = 0.3
        const val NEVER = -1e6
    }
}

const val POKE_MEMORY = 4
