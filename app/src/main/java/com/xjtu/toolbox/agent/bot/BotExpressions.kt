package com.xjtu.toolbox.agent.bot

/**
 * 待机表情。移植自 src/bot/expressions.ts 的子集。
 *
 * bloub 的设计：只有待机状态（baseFace）佩戴所选表情；眨眼、通知等动画状态
 * 各自带着从视频逐帧实测的脸，不受表情影响。
 *
 * 表情只有四个旋钮：头的朝向、两眼在球面上的张角、眼睛比例、每只眼的自转倾斜
 * （镜像倾斜才能做出生气/难过这类「两眼相向」的神态，本子集暂未用到）。
 */

class BotExpression(
    val id: String,
    val gaze: HeadGaze,
    val split: Double,
    val eyes: List<EyeCfg>,
)

/** `tilt` 为度，正 = 胶囊顶端向右；两眼取镜像。 */
private fun eyePair(w: Double, h: Double, tilt: Double = 0.0): List<EyeCfg> =
    listOf(EyeCfg(w, h, tilt = tilt), EyeCfg(w, h, tilt = -tilt))

/** 专注：眼睛稍大且微抬，像在留意周围。 */
val EXPRESSION_ATTENTIF = BotExpression(
    id = "attentif",
    gaze = HeadGaze(yaw = 4.0, pitch = 5.0, roll = -4.0),
    split = 16.0,
    eyes = eyePair(0.21, 0.44),
)

/** 好奇：头一歪、两眼不对称地打量（两眼尺寸/倾斜各不相同是实测值，别"修正"成对称）。 */
val EXPRESSION_CURIEUX = BotExpression(
    id = "curieux",
    gaze = HeadGaze(yaw = 16.0, pitch = -9.0, roll = -15.0),
    split = 16.5,
    eyes = listOf(EyeCfg(0.24, 0.46, tilt = -8.0), EyeCfg(0.2, 0.38, tilt = -8.0)),
)

/** 底栏待机用的表情。想换表情改这一行。 */
val REST_EXPRESSION = EXPRESSION_ATTENTIF
