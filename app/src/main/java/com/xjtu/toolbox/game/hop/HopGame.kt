package com.xjtu.toolbox.game.hop

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 跳台种类。普通台之外八种会改规则的台子，分数上来后才开始出现：
 * 弹簧（这一跳 ×1.5）、冰面（落地前滑）、易碎（站久会塌）、移动（来回晃）、
 * 传送门（送到前面更远一块）、旋转（站着会被带着转）、幽灵（忽隐忽现，隐身时踩空）、蹦床（自动再弹一跳）。
 */
enum class PadKind { NORMAL, SPRING, ICE, CRUMBLE, MOVING, PORTAL, SPIN, GHOST, TRAMPOLINE }

enum class HopPhase { IDLE, CHARGING, FLYING, SLIDING, WARPING, FALLING, OVER }

internal const val JUMP_SPEED = 3.0f        // 每蓄力一秒飞多远
internal const val MAX_CHARGE = 1.3f
internal const val FLIGHT_TIME = 0.5f
internal const val SLIDE_TIME = 0.35f
internal const val FALL_TIME = 0.45f
internal const val TIP_FALL_TIME = 0.7f
internal const val ICE_SLIDE = 0.42f
internal const val CRUMBLE_TIME = 1.8f
/** 易碎台之后那块的最远中心距：蓄力约 0.75 秒，给看清和起跳留出余量。 */
internal const val RUSHED_GAP = 2.2f
internal const val SPRING_BOOST = 1.5f
internal const val PAD_HEIGHT = 0.55f
/** 棋子底座半径：落点出了台面但离边不到这么远，是半个身子悬空、往外倒下去。 */
internal const val PLAYER_RADIUS = 0.14f
/** 落点离中心不到半径的这个比例，算正中。 */
internal const val CENTER_RATIO = 0.3f
internal const val FORK_BONUS = 5
/** 方台、圆台各有几种造型，界面按 [Pad.style] 取对应的画法。 */
internal const val BOX_STYLES = 5
internal const val ROUND_STYLES = 4
internal const val MOVE_AMPLITUDE = 0.55f
private const val MOVE_SPEED = 1.7f
/** 在彩蛋台上停这么久拿奖励。 */
internal const val STAY_TIME = 2f
internal const val WARP_TIME = 0.7f
internal const val PORTAL_BONUS = 3
/** 旋转台转速（弧度 / 秒），站在上面的人跟着转。 */
internal const val SPIN_SPEED = 1.3f
/** 蹦床落稳后隔这么久自动起跳。 */
internal const val BOUNCE_DELAY = 0.3f

/** 彩蛋台：黑胶（圆台 1）停够两秒 +10，咖啡杯（圆台 2）、魔方（方台 3）+5，别的台子 0。 */
fun stayBonusOf(pad: Pad): Int = when {
    pad.kind != PadKind.NORMAL || pad.bonus > 0 -> 0
    pad.round && pad.style == 1 -> 10
    pad.round && pad.style == 2 -> 5
    !pad.round && pad.style == 3 -> 5
    else -> 0
}

/**
 * 一块跳台。世界坐标 x/z 是地面，y 朝上，台面在 y = 0。
 * [axis] 是它相对上一块的方向：0 沿 x，1 沿 z；移动台沿另一条轴来回晃。
 */
class Pad(
    val baseX: Float,
    val baseZ: Float,
    /** 方台是半边长，圆台是半径。 */
    val half: Float,
    val round: Boolean,
    val kind: PadKind,
    val axis: Int,
    /** 造型编号：方台 0 until [BOX_STYLES]，圆台 0 until [ROUND_STYLES]。 */
    val style: Int,
    /** 分叉险路额外给的分。 */
    val bonus: Int = 0,
) {
    var moving = kind == PadKind.MOVING
    var phase = 0f
    /** 易碎台塌掉 / 分叉没选的那块淡出，0→1。 */
    var fade = 0f
    var fading = false

    private val offset get() = if (kind == PadKind.MOVING) MOVE_AMPLITUDE * sin(phase) else 0f
    val x get() = baseX + if (axis == 1) offset else 0f
    val z get() = baseZ + if (axis == 0) offset else 0f

    /** 幽灵台此刻有多实：1 实体、0 透明；一个周期里大约六成时间踩得住。落稳后就不再闪。 */
    var settled = false
    val solidity: Float
        get() = if (kind != PadKind.GHOST || settled) 1f else ((sin(phase * 2.4f) + 0.35f) * 2.5f).coerceIn(0f, 1f)

    fun contains(px: Float, pz: Float): Boolean = edgeDistance(px, pz) <= 0f && solidity > 0.5f

    /** 点到台面的水平距离，在台面上为 0。 */
    fun edgeDistance(px: Float, pz: Float): Float {
        val dx = px - x
        val dz = pz - z
        return if (round) max(0f, hypot(dx, dz) - half)
        else hypot(max(0f, abs(dx) - half), max(0f, abs(dz) - half))
    }
}

/**
 * 一局跳一跳。界面每帧调 [update]，按下 / 瞄准 / 松开调 [press] / [aim] / [release]，再照着字段画。
 *
 * 在原版「蓄力越久跳越远、落正中连击翻倍」之上加了三样：
 * - 特殊台，见 [PadKind]；
 * - 彩蛋台：在黑胶、咖啡杯、魔方上停够 [STAY_TIME] 秒有额外分，见 [stayBonusOf]；
 * - 分叉路：偶尔同时给出左右两块，小而远的那块多给 [FORK_BONUS] 分。按住后手指往哪边挪就瞄哪块，
 *   界面据 [aimTarget] 画出瞄准线。
 */
class HopGame(private val random: Random = Random.Default) {

    val pads = mutableListOf<Pad>()
    var current: Pad; private set
    /** 下一跳可选的台子：一般一块，分叉时两块。 */
    var targets: List<Pad> = emptyList(); internal set

    var phase = HopPhase.IDLE; private set
    var charge = 0f; private set
    var score = 0; internal set
    var streak = 0; private set
    /** 最近一次得分，和它的序号（界面据此飘字）。 */
    var lastGain = 0; private set
    var gainSerial = 0; private set
    var lastWasCenter = false; private set
    var crumbleLeft = 0f; private set

    /** 每次落地（含落回原地）加一，界面在 [px]/[pz] 处扬尘。 */
    var landSerial = 0; private set
    /** 每次起跳加一；[launchPad] 是起跳的那块（它要回弹），[launchCharge] 是当时压了多深 0..1。 */
    var releaseSerial = 0; private set
    var launchPad: Pad? = null; private set
    var launchCharge = 0f; private set

    /** 在彩蛋台上停了多久；[stayBonusSerial] 每拿到一次停留奖励加一，[lastStayBonus] 是给了几分。 */
    var stayTime = 0f; private set
    var stayBonusSerial = 0; private set
    var lastStayBonus = 0; private set
    private var stayAwarded = false
    /** 传送进度 0..1：前一半在入口缩没，后一半在出口冒出来。 */
    var warpProgress = 0f; private set
    /** ≥ 0 表示站在蹦床上、正在等着自动起跳。 */
    private var bounceTimer = -1f

    // 小人位置
    var px = 0f; private set
    var py = 0f; private set
    var pz = 0f; private set
    /** 起跳方向在屏幕上朝右为 1、朝左为 -1，翻跟头按这个方向转。 */
    var jumpSide = 1; private set
    /** 本次跳跃 / 下落的进度 0..1。 */
    var flightProgress = 0f; private set
    var fallProgress = 0f; private set
    /** 半个身子悬空往外倒：倒向 ([tiltX], [tiltZ])。 */
    var tipping = false; private set
    var tiltX = 0f; private set
    var tiltZ = 0f; private set

    private var fromX = 0f
    private var fromZ = 0f
    private var toX = 0f
    private var toZ = 0f
    private var dirX = 0f
    private var dirZ = 0f
    private var timer = 0f
    private var side = 1
    private var fallFromY = 0f

    init {
        current = Pad(0f, 0f, 0.6f, round = false, kind = PadKind.NORMAL, axis = 0, style = 0)
        pads += current
        targets = generate(current)
        pads += targets
    }

    /** 站在弹簧台上，这一跳会被放大。 */
    val springActive get() = current.kind == PadKind.SPRING && (phase == HopPhase.IDLE || phase == HopPhase.CHARGING)

    /** 这一跳会朝哪块跳：分叉时看手指在哪边，沿 x 的那块在屏幕右边、沿 z 的在左边。 */
    val aimTarget: Pad
        get() = if (targets.size > 1) targets.firstOrNull { (it.axis == 0) == (side > 0) } ?: targets[0] else targets[0]

    /** 镜头该对准的点：脚下和下一跳之间（传送途中还没有下一块，就对准脚下）。 */
    val focusX get() = if (targets.isEmpty()) current.x else (current.x + targets.map { it.x }.average().toFloat()) / 2
    val focusZ get() = if (targets.isEmpty()) current.z else (current.z + targets.map { it.z }.average().toFloat()) / 2

    /** [screenSide] 按下的是屏幕左半（-1）还是右半（1），只在分叉时有用。 */
    fun press(screenSide: Int) {
        if (phase != HopPhase.IDLE || bounceTimer >= 0f) return
        side = screenSide
        charge = 0f
        phase = HopPhase.CHARGING
    }

    /** 蓄力中改瞄准方向。 */
    fun aim(screenSide: Int) {
        if (phase == HopPhase.CHARGING) side = screenSide
    }

    fun release() {
        if (phase != HopPhase.CHARGING) return
        launch()
    }

    /** 切到后台时丢掉这次蓄力，别回来就自己跳了。 */
    fun cancelCharge() {
        if (phase == HopPhase.CHARGING) {
            charge = 0f
            phase = HopPhase.IDLE
        }
    }

    fun update(dt: Float) {
        for (p in pads) {
            when {
                p.moving -> p.phase += dt * MOVE_SPEED
                p.kind == PadKind.SPIN -> p.phase += dt * SPIN_SPEED
                p.kind == PadKind.GHOST && !p.settled -> p.phase += dt
            }
            if (p.fading) p.fade = min(1f, p.fade + dt * 2.5f)
        }
        pads.removeAll { it.fading && it.fade >= 1f && it !== current }
        when (phase) {
            HopPhase.IDLE -> {
                tickStanding(dt)
                tickBounce(dt)
            }
            HopPhase.CHARGING -> {
                charge = min(MAX_CHARGE, charge + dt)
                tickStanding(dt)
            }
            HopPhase.WARPING -> {
                timer += dt
                warpProgress = min(1f, timer / WARP_TIME)
                if (warpProgress >= 0.5f && targets.isEmpty()) warpOut()
                if (warpProgress >= 1f) phase = HopPhase.IDLE
            }
            HopPhase.FLYING -> {
                timer += dt
                val t = min(1f, timer / FLIGHT_TIME)
                flightProgress = t
                px = fromX + (toX - fromX) * t
                pz = fromZ + (toZ - fromZ) * t
                py = 4f * (0.9f + 0.4f * hypot(toX - fromX, toZ - fromZ) / (JUMP_SPEED * MAX_CHARGE)) * t * (1 - t)
                if (t >= 1f) land()
            }
            HopPhase.SLIDING -> {
                timer += dt
                val t = min(1f, timer / SLIDE_TIME)
                // 减速滑行：先快后慢
                val e = 1 - (1 - t) * (1 - t)
                px = fromX + (toX - fromX) * e
                pz = fromZ + (toZ - fromZ) * e
                if (t >= 1f) {
                    if (current.contains(px, pz)) phase = HopPhase.IDLE else fall(edgeOf(current))
                }
            }
            HopPhase.FALLING -> {
                timer += dt
                val total = if (tipping) TIP_FALL_TIME else FALL_TIME
                val t = min(1f, timer / total)
                fallProgress = t
                // 倒下的前一段绕着台边转，后一段才往下掉
                val drop = if (tipping) max(0f, (t - 0.35f) / 0.65f) else t
                py = fallFromY - (fallFromY + PAD_HEIGHT) * drop * drop
                if (tipping) {
                    px += tiltX * dt * 0.25f
                    pz += tiltZ * dt * 0.25f
                }
                if (t >= 1f) phase = HopPhase.OVER
            }
            HopPhase.OVER -> Unit
        }
    }

    /** 站着（含蓄力）时每帧：易碎台倒计时、彩蛋台计时、旋转台带着人转。 */
    private fun tickStanding(dt: Float) {
        tickCrumble(dt)
        val bonus = stayBonusOf(current)
        if (bonus > 0 && !stayAwarded) {
            stayTime += dt
            if (stayTime >= STAY_TIME) {
                stayAwarded = true
                score += bonus
                lastStayBonus = bonus
                stayBonusSerial++
            }
        }
        if (current.kind == PadKind.SPIN) {
            val a = dt * SPIN_SPEED
            val dx = px - current.x
            val dz = pz - current.z
            px = current.x + dx * cos(a) - dz * sin(a)
            pz = current.z + dx * sin(a) + dz * cos(a)
        }
    }

    /** 蹦床：落稳一会儿后自动朝下一块正中起跳。 */
    private fun tickBounce(dt: Float) {
        if (bounceTimer < 0f) return
        bounceTimer += dt
        if (bounceTimer < BOUNCE_DELAY) return
        bounceTimer = -1f
        val target = targets.firstOrNull { it.bonus == 0 } ?: targets.firstOrNull() ?: return
        side = if (target.axis == 0) 1 else -1
        charge = hypot(target.x - px, target.z - pz) / JUMP_SPEED
        launch()
    }

    /** 传送到一半：在前面更远处放一块出口，人挪过去，再出下一块。 */
    private fun warpOut() {
        val dist = 3.2f + random.nextFloat() * 0.6f
        val exit = Pad(
            if (current.axis == 0) current.x + dist else current.x,
            if (current.axis == 0) current.z else current.z + dist,
            0.55f, round = true, kind = PadKind.PORTAL, axis = current.axis, style = 0,
        )
        pads += exit
        current = exit
        px = exit.x
        pz = exit.z
        py = 0f
        score += PORTAL_BONUS
        lastGain = PORTAL_BONUS
        lastWasCenter = false
        gainSerial++
        targets = generate(exit)
        pads += targets
        while (pads.size > 8) pads.removeAt(0)
    }

    private fun tickCrumble(dt: Float) {
        if (current.kind != PadKind.CRUMBLE || crumbleLeft <= 0f) return
        crumbleLeft -= dt
        if (crumbleLeft <= 0f) {
            current.fading = true
            charge = 0f
            fall(null)
        }
    }

    private fun launch() {
        val target = aimTarget
        // 移动台瞄轨道中线：落点只由蓄力决定，靠松手时机让台子正好晃到落点。
        // 瞄它此刻的位置的话，方向跟着台子歪，飞行中台子又挪开，落点就无从预判。
        var dx = (if (target.moving) target.baseX else target.x) - px
        var dz = (if (target.moving) target.baseZ else target.z) - pz
        val len = hypot(dx, dz).coerceAtLeast(1e-4f)
        dx /= len
        dz /= len
        val power = charge * JUMP_SPEED * if (current.kind == PadKind.SPRING) SPRING_BOOST else 1f
        dirX = dx
        dirZ = dz
        fromX = px
        fromZ = pz
        toX = px + dx * power
        toZ = pz + dz * power
        jumpSide = if (dx - dz >= 0) 1 else -1
        launchPad = current
        launchCharge = charge / MAX_CHARGE
        releaseSerial++
        timer = 0f
        flightProgress = 0f
        charge = 0f
        phase = HopPhase.FLYING
    }

    private fun land() {
        py = 0f
        val hit = targets.firstOrNull { it.contains(px, pz) }
        when {
            hit != null -> arrive(hit)
            current.contains(px, pz) && !current.fading -> {
                landSerial++
                phase = HopPhase.IDLE
            }
            else -> {
                // 落在某块台边外不到一个底座半径：半个身子悬空，往外倒
                // 隐身的幽灵台不算，直接穿过去掉下
                val edge = (targets + current).filter { !it.fading && it.solidity > 0.5f }.minByOrNull { it.edgeDistance(px, pz) }
                fall(edge?.takeIf { it.edgeDistance(px, pz) < PLAYER_RADIUS })
            }
        }
    }

    private fun arrive(pad: Pad) {
        val d = hypot(px - pad.x, pz - pad.z)
        lastWasCenter = d <= pad.half * CENTER_RATIO
        streak = if (lastWasCenter) streak + 1 else 0
        lastGain = (if (lastWasCenter) 2 * streak else 1) + pad.bonus
        score += lastGain
        gainSerial++
        landSerial++

        pad.moving = false
        pad.settled = true
        targets.filter { it !== pad }.forEach { it.fading = true }
        current = pad
        stayTime = 0f
        stayAwarded = false
        if (pad.kind == PadKind.PORTAL) {
            // 下一块等传送到出口再出
            targets = emptyList()
            timer = 0f
            warpProgress = 0f
            phase = HopPhase.WARPING
            return
        }
        targets = generate(pad)
        pads += targets
        // 只留最近几块，身后太远的不画了
        while (pads.size > 8) pads.removeAt(0)

        if (pad.kind == PadKind.TRAMPOLINE) bounceTimer = 0f
        crumbleLeft = if (pad.kind == PadKind.CRUMBLE) CRUMBLE_TIME else 0f
        if (pad.kind == PadKind.ICE) {
            fromX = px
            fromZ = pz
            toX = px + dirX * ICE_SLIDE
            toZ = pz + dirZ * ICE_SLIDE
            timer = 0f
            phase = HopPhase.SLIDING
        } else {
            phase = HopPhase.IDLE
        }
    }

    /** 从冰面滑出去时倒向滑出的那条边。 */
    private fun edgeOf(pad: Pad): Pad? = pad.takeIf { it.edgeDistance(px, pz) < PLAYER_RADIUS }

    /** [edge] 不为空时是从这块台的边上倒下去，否则直接往下掉。 */
    private fun fall(edge: Pad?) {
        fallFromY = py
        timer = 0f
        fallProgress = 0f
        streak = 0
        tipping = edge != null
        if (edge != null) {
            // 倒向：从台面上离落点最近的那一点指向落点
            val cx = if (edge.round) edge.x else px.coerceIn(edge.x - edge.half, edge.x + edge.half)
            val cz = if (edge.round) edge.z else pz.coerceIn(edge.z - edge.half, edge.z + edge.half)
            var tx = px - cx
            var tz = pz - cz
            val len = hypot(tx, tz)
            if (len < 1e-4f) { tx = dirX; tz = dirZ } else { tx /= len; tz /= len }
            tiltX = tx
            tiltZ = tz
        }
        phase = HopPhase.FALLING
    }

    /** 特殊台的出现比例：5 分起有前四种，15 分起再加传送门、旋转、幽灵、蹦床。 */
    private fun pickKind(): PadKind {
        val r = random.nextInt(100)
        return if (score < 15) when {
            r < 60 -> PadKind.NORMAL
            r < 70 -> PadKind.SPRING
            r < 80 -> PadKind.ICE
            r < 90 -> PadKind.CRUMBLE
            else -> PadKind.MOVING
        } else when {
            r < 48 -> PadKind.NORMAL
            r < 55 -> PadKind.SPRING
            r < 62 -> PadKind.ICE
            r < 69 -> PadKind.CRUMBLE
            r < 76 -> PadKind.MOVING
            r < 82 -> PadKind.PORTAL
            r < 88 -> PadKind.SPIN
            r < 94 -> PadKind.GHOST
            else -> PadKind.TRAMPOLINE
        }
    }

    /** 按当前分数出下一块（或分叉两块）。越往后台子越小、越远，特殊台越多。 */
    private fun generate(from: Pad): List<Pad> {
        val d = min(score, 120) / 120f
        // 易碎台站不久：下一块不远、不分叉，也不出要等时机的移动台和幽灵台
        val rushed = from.kind == PadKind.CRUMBLE
        // 站在台子最后沿也要够得着下一块的中心
        val reach = JUMP_SPEED * MAX_CHARGE - from.half
        fun half() = random.nextFloat() * (0.14f - 0.04f * d) + 0.48f - 0.18f * d
        fun gap(h: Float): Float {
            val far = 1.3f + random.nextFloat() * (0.9f + 0.9f * d)
            return max(from.half + h + 0.25f, if (rushed) min(far, RUSHED_GAP) else far)
        }
        fun at(axis: Int, dist: Float) = if (axis == 0) from.x + dist to from.z else from.x to from.z + dist
        // 同形状的连续两块别撞同一个造型
        fun style(round: Boolean): Int {
            val n = if (round) ROUND_STYLES else BOX_STYLES
            var s = random.nextInt(n)
            if (round == from.round && s == from.style) s = (s + 1) % n
            return s
        }

        // 蹦床会自动跳过去，下一块只出普通台，保证落得住
        val plain = from.kind == PadKind.TRAMPOLINE
        if (!plain && !rushed && score >= 8 && targets.size < 2 && random.nextFloat() < 0.18f) {
            val riskyAxis = random.nextInt(2)
            return (0..1).map { axis ->
                val risky = axis == riskyAxis
                val h = if (risky) half() * 0.6f else half()
                val dist = if (risky) min(gap(h) + 0.5f, reach) else gap(h)
                val (x, z) = at(axis, dist)
                val round = if (risky) true else random.nextBoolean()
                Pad(x, z, h, round, PadKind.NORMAL, axis, style(round), if (risky) FORK_BONUS else 0)
            }
        }
        val kind = when {
            plain || score < 5 -> PadKind.NORMAL
            else -> pickKind().takeUnless { rushed && (it == PadKind.MOVING || it == PadKind.GHOST) } ?: PadKind.NORMAL
        }
        val axis = random.nextInt(2)
        val h = half()
        val (x, z) = at(axis, gap(h))
        // 传送门、旋转台、蹦床是圆的
        val round = kind == PadKind.PORTAL || kind == PadKind.SPIN || kind == PadKind.TRAMPOLINE || random.nextBoolean()
        return listOf(Pad(x, z, h, round, kind, axis, style(round)).also { it.phase = random.nextFloat() * 6.28f })
    }
}
