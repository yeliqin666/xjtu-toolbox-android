package com.xjtu.toolbox.agent.bot

import kotlin.math.cos
import kotlin.math.sin

/**
 * 状态与姿态。移植自 src/bot/states.ts，只保留底栏四态对应的子集：
 * idle（待命）/ wink（微动）/ notify（提醒）/ burst（点击）。
 * 所有常量是对参考视频的逐帧实测值，不要凭感觉取整。
 */

class EyeCfg(
    /** 胶囊短轴，单位为球半径 */
    val w: Double,
    /** 胶囊长轴 */
    val h: Double,
    /** 1 = 睁开，0 = 闭合 */
    val open: Double = 1.0,
    /** 胶囊自转，度，正 = 顶端向右。允许两眼镜像倾斜（生气/难过用，本子集未用到）。 */
    val tilt: Double = 0.0,
)

class Pose(
    val sil: Silhouette,
    val offX: Double,
    val offY: Double,
    val gaze: HeadGaze,
    /** 两眼在球面上的半张角，度 */
    val split: Double,
    /** [内侧眼, 外侧眼] */
    val eyes: List<EyeCfg>,
    val eyeAlpha: Double,
    val bodyAlpha: Double,
    val dots: List<DotSpec>,
    val arcs: List<ArcSpec>,
    val notif: NotifSpec?,
    /** true = 装饰画在身体后面（burst 的粒子） */
    val dotsBehind: Boolean,
)

/** 状态声明的弧线：几何在种子上，采样时刻与透明度随姿态走。 */
class ArcSpec(val seed: ArcSeed, val t: Double, val opacity: Double)

private fun eyePair(w: Double, h: Double): List<EyeCfg> = listOf(EyeCfg(w, h), EyeCfg(w, h))

/** 默认姿态（静息脸），[over] 覆盖任意字段。 */
private fun base(
    sil: Silhouette = circle(1.0),
    offX: Double = 0.0,
    offY: Double = 0.0,
    gaze: HeadGaze = REST_GAZE,
    split: Double = EYE_SPLIT,
    eyes: List<EyeCfg> = eyePair(EYE_W, EYE_H),
    eyeAlpha: Double = 1.0,
    bodyAlpha: Double = 1.0,
    dots: List<DotSpec> = emptyList(),
    arcs: List<ArcSpec> = emptyList(),
    notif: NotifSpec? = null,
    dotsBehind: Boolean = false,
): Pose = Pose(sil, offX, offY, gaze, split, eyes, eyeAlpha, bodyAlpha, dots, arcs, notif, dotsBehind)

class StateDef(
    val id: String,
    /** 完整播放时的保持时长（秒） */
    val duration: Double,
    /** 入场形变时长 */
    val morph: Double,
    /** true = 入场由一次眨眼掩护 */
    val blinkIn: Boolean,
    /** true = 身体是静息轮廓（圆），可被自定义形状替换（本移植只有圆形，恒等） */
    val baseBody: Boolean,
    /** true = 状态带静息脸 */
    val baseFace: Boolean,
    /**
     * 循环周期（秒）。非空时姿态时间按整周期取模，供思考这类时长不定的状态
     * 无限循环；每个接缝由引擎的一次眨眼掩护。空 = 播完保持末帧。
     */
    val loop: Double? = null,
    val pose: (t: Double) -> Pose,
)

val STATE_IDLE = StateDef(
    id = "idle",
    duration = 2.4,
    morph = 0.45,
    blinkIn = false,
    baseBody = true,
    baseFace = true,
    pose = { _ -> base() },
)

val STATE_WINK = StateDef(
    id = "wink",
    duration = 1.6,
    morph = 0.3,
    blinkIn = true,
    baseBody = true,
    baseFace = false,
    pose = { _ ->
        base(
            gaze = HeadGaze(yaw = -5.37, pitch = 4.55, roll = 6.7),
            split = 16.25,
            // 闭上的眼不是睁眼压扁：是比睁眼更宽的横杠（0.447 对 0.236）。
            eyes = listOf(EyeCfg(0.236, 0.464), EyeCfg(0.447, 0.089)),
        )
    },
)

val STATE_NOTIFY = StateDef(
    id = "notify",
    duration = 2.2,
    morph = 0.5,
    blinkIn = true,
    baseBody = true,
    baseFace = false,
    pose = { t ->
        // 蓝点 pop：0.3s 处冲到 +14%，随后稳定。
        val p = clamp01(t / 0.45)
        val pop = 1.0 + (NOTIF_POP - 1.0) * sin(p * PI) * (1.0 - p * 0.35)
        val r = NOTIF_R * (if (p < 1.0) pop else 1.0)
        val a = NOTIF_ANGLE.deg2rad()
        base(
            // 脸用「好奇」表情（歪头打量），取代视频实测的瞟开脸；蓝点是提醒的语义，保留
            gaze = EXPRESSION_CURIEUX.gaze,
            split = EXPRESSION_CURIEUX.split,
            eyes = EXPRESSION_CURIEUX.eyes,
            notif = NotifSpec(
                x = cos(a) * NOTIF_DIST,
                y = sin(a) * NOTIF_DIST,
                r = r,
                notch = r + NOTIF_MARGIN,
            ),
        )
    },
)

val STATE_BURST = StateDef(
    id = "burst",
    duration = 2.6,
    morph = 0.4,
    blinkIn = false,
    baseBody = false,
    baseFace = false,
    pose = { t ->
        // 实测塌缩：1.0 -> 0.166 用时 0.7s，ease-out，无回弹。
        val collapse = 1.0 - 0.834 * Easings.easeOutQuint(clamp01(t / 0.7))
        val regrow = Easings.easeOutQuint(clamp01((t - 1.7) / 0.7))
        base(
            sil = circle(collapse + (1.0 - collapse) * regrow),
            eyeAlpha = clamp01((t - 1.85) / 0.4),
            dots = particles(t, 1.0),
            dotsBehind = true,
        )
    },
)

val STATE_COMET = StateDef(
    id = "comet",
    duration = 2.4,
    morph = 0.45,
    blinkIn = false,
    baseBody = false,
    baseFace = false,
    pose = { t ->
        val collapse = 1.0 - (1.0 - COMET_DOT) * Easings.easeOutQuint(clamp01(t / 0.55))
        val regrow = Easings.easeOutQuint(clamp01((t - 1.85) / 0.6))
        val fade = clamp01((t - 0.15) / 0.25) * clamp01((1.95 - t) / 0.3)
        base(
            // 点先缩成 0.129 的核（带 0.035 的垂直微漂），彩带绕着它转，最后长回。
            sil = circle(collapse + (1.0 - collapse) * regrow, cy = sin(clamp01(t / 1.7) * PI) * 0.035),
            eyeAlpha = clamp01((t - 2.0) / 0.35),
            arcs = COMET_RIBBONS.map { ArcSpec(seed = it, t = t, opacity = fade) },
        )
    },
)

val STATE_THINKING = StateDef(
    id = "orbit",
    // 思考可能比一个周期长：整周期循环，接缝由引擎的眨眼掩护（见 BotEngine）
    duration = 3.4,
    loop = 3.4,
    morph = 0.6,
    blinkIn = false,
    baseBody = false,
    baseFace = false,
    pose = { t ->
        // 实测旋转：0.35s 起步，随后 1.25 圈/s 逆时针
        val ramp = Easings.easeInOutCubic(clamp01(t / 0.35))
        val rot = -TAU * 1.25 * t * ramp
        // 身体在轨道中从三角松弛回圆球
        val back = Easings.easeInOutCubic(clamp01((t - 1.6) / 0.9))
        val tri = spinningTriangle(rot)
        val ball = circle(1.0, rot = rot)
        val radii = DoubleArray(PROFILE_SAMPLES) { i ->
            tri.radii[i] + (ball.radii[i] - tri.radii[i]) * back
        }
        // 环一条接一条进场（每条错 0.13s），离场前整体淡出
        val fade = clamp01(t / 0.8) * clamp01((3.6 - t) / 0.9)
        base(
            sil = Silhouette(radii, rot = rot, cx = tri.cx * (1 - back), cy = tri.cy * (1 - back)),
            // 眼睛以约 3 倍于轮廓的速度绕球飞
            gaze = HeadGaze(
                yaw = REST_GAZE.yaw + sin(t * 6.5) * 65.0 * (1.0 - back),
                pitch = -4.0 + back * 32.0,
                roll = -13.0,
            ),
            eyes = eyePair(0.18, 0.34 + back * 0.07),
            arcs = RINGS.mapIndexed { i, s ->
                ArcSpec(seed = s, t = t, opacity = fade * clamp01((t - i * 0.13) / 0.3))
            },
        )
    },
)

val BOT_STATES: Map<String, StateDef> = mapOf(
    STATE_IDLE.id to STATE_IDLE,
    STATE_WINK.id to STATE_WINK,
    STATE_THINKING.id to STATE_THINKING,
    STATE_NOTIFY.id to STATE_NOTIFY,
    STATE_BURST.id to STATE_BURST,
    STATE_COMET.id to STATE_COMET,
)

/* ------------------------------------------------- 轨道用的旋转三角 */

/** 三角轮廓 r(theta)，tools/extract-profiles.py 逐帧实测，勿手改。 */
private val TRIANGLE_RADII = doubleArrayOf(
    0.7819, 0.8211, 0.8747, 0.9440, 1.0223, 1.0960, 1.1401, 1.1340, 1.0808, 1.0047,
    0.9265, 0.8603, 0.8104, 0.7730, 0.7450, 0.7273, 0.7151, 0.7118, 0.7148, 0.7245,
    0.7427, 0.7680, 0.8037, 0.8518, 0.9148, 0.9876, 1.0583, 1.1073, 1.1109, 1.0667,
    0.9940, 0.9164, 0.8482, 0.7948, 0.7555, 0.7261, 0.7056, 0.6925, 0.6859, 0.6869,
    0.6938, 0.7084, 0.7305, 0.7615, 0.8040, 0.8595, 0.9311, 1.0092, 1.0791, 1.1171,
    1.1054, 1.0501, 0.9779, 0.9050, 0.8450, 0.7990, 0.7656, 0.7413, 0.7258, 0.7160,
    0.7146, 0.7204, 0.7330, 0.7528,
)

/** 三角不自转：其中心绕原点画一个 0.213R 的圆——这正是「翻滚」而非「旋转」的来源。 */
private const val TRI_ORBIT = 0.213

private fun spinningTriangle(rot: Double): Silhouette = Silhouette(
    TRIANGLE_RADII,
    rot = rot,
    cx = -TRI_ORBIT * sin(rot),
    cy = TRI_ORBIT * cos(rot),
)

private val PI = kotlin.math.PI
