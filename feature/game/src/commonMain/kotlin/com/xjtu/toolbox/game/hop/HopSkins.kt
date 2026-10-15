package com.xjtu.toolbox.game.hop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * 跳一跳的角色。每个角色一套画法，也一套动作：
 * - 棋子：蓄力压扁，空中翻跟头；
 * - 屁岱：果冻身子，蓄力压成扁团，空中拉长不翻身，眼睛看着要去的方向，站着会眨眼；
 * - 小猫：蓄力伏低、尾巴翘起，空中身子前探像扑过去，站着尾巴慢慢摆；
 * - 宇航员：轻飘飘地原地浮动，起跳往前倾、背包喷一口气，落地很轻。
 * 规则不管画成什么，只给位置；动作全在这里按状态算。
 */

enum class HopSkin(val id: String, val title: String) {
    PAWN("pawn", "棋子"),
    PIDAI("pidai", "屁岱"),
    CAT("cat", "小猫"),
    ASTRONAUT("astronaut", "宇航员"),
}

/** 画一帧角色需要的全部状态。[side] 是面朝屏幕右（1）还是左（-1）。 */
class PieceState(
    val feet: Offset,
    val scale: Float,
    /** 蓄力程度 0..1；起跳瞬间给负值表示被弹得拉长。 */
    val squash: Float,
    val flying: Boolean,
    /** 空中进度 0..1。 */
    val flight: Float,
    val side: Int,
    /** 倒下的角度（度），0 表示站着。 */
    val tilt: Float,
    val time: Float,
    /** 落地后过了多久（秒），用来做落地回弹。 */
    val sinceLand: Float,
    /** 整体缩放：传送时缩没再冒出来。 */
    val size: Float = 1f,
)

fun DrawScope.drawSkin(skin: HopSkin, s: PieceState) {
    if (s.size <= 0.01f) return
    scale(s.size, s.feet) {
        when (skin) {
            HopSkin.PAWN -> drawPawn(s)
            HopSkin.PIDAI -> drawPidai(s)
            HopSkin.CAT -> drawCat(s)
            HopSkin.ASTRONAUT -> drawAstronaut(s)
        }
    }
}

/** 落地回弹：刚落地压一下、弹回来略过头，大约 0.4 秒停住。 */
private fun landBounce(t: Float, depth: Float): Float = if (t > 0.6f) 0f else depth * exp(-9f * t) * cos(t * 26f)

// ── 棋子 ──

private val PAWN_DARK = Color(0xFF2B2750)
private val PAWN_MID = Color(0xFF4A4488)
private val PAWN_LIGHT = Color(0xFF7C74C9)

private fun DrawScope.drawPawn(s: PieceState) {
    val squash = s.squash + landBounce(s.sinceLand, 0.25f)
    val hk = 1f - 0.32f * squash
    val wk = 1f + 0.14f * squash
    val sc = s.scale
    val feet = s.feet
    val baseRx = PLAYER_RADIUS * sc * wk * 1.2247f
    val baseRy = PLAYER_RADIUS * sc * wk * 0.7071f
    val baseTop = feet.y - 0.05f * sc * hk
    val neckY = feet.y - 0.4f * sc * hk
    val neckR = 0.055f * sc * wk
    val headR = 0.105f * sc
    val headC = Offset(feet.x, neckY - headR * 0.85f)
    val angle = when {
        s.tilt != 0f -> s.tilt
        s.flying -> 360f * smoothstep(s.flight) * s.side
        else -> 0f
    }
    val pivot = if (s.tilt != 0f) feet else Offset(feet.x, (feet.y + headC.y) / 2)
    rotate(angle, pivot) {
        val body = Brush.horizontalGradient(listOf(PAWN_LIGHT, PAWN_MID, PAWN_DARK), feet.x - baseRx, feet.x + baseRx)
        drawPath(Path().apply {
            moveTo(feet.x - baseRx, baseTop)
            lineTo(feet.x - baseRx, feet.y)
            arcTo(Rect(feet.x - baseRx, feet.y - baseRy, feet.x + baseRx, feet.y + baseRy), 180f, -180f, false)
            lineTo(feet.x + baseRx, baseTop)
            close()
        }, body)
        drawOval(PAWN_MID, Offset(feet.x - baseRx, baseTop - baseRy), Size(baseRx * 2, baseRy * 2))
        val bw = baseRx * 0.78f
        drawPath(Path().apply {
            moveTo(feet.x - bw, baseTop)
            quadraticTo(feet.x - neckR * 1.2f, (baseTop + neckY) / 2 + sc * 0.03f, feet.x - neckR, neckY)
            lineTo(feet.x + neckR, neckY)
            quadraticTo(feet.x + neckR * 1.2f, (baseTop + neckY) / 2 + sc * 0.03f, feet.x + bw, baseTop)
            arcTo(Rect(feet.x - bw, baseTop - baseRy * 0.78f, feet.x + bw, baseTop + baseRy * 0.78f), 0f, 180f, false)
            close()
        }, Brush.horizontalGradient(listOf(PAWN_LIGHT, PAWN_MID, PAWN_DARK), feet.x - bw, feet.x + bw))
        drawOval(PAWN_MID, Offset(feet.x - neckR * 1.7f, neckY - neckR * 0.45f), Size(neckR * 3.4f, neckR * 0.9f))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFB3ADF0), PAWN_MID, PAWN_DARK), headC + Offset(-headR * 0.35f, -headR * 0.4f), headR * 1.4f),
            headR, headC,
        )
        drawCircle(Color.White, headR * 0.22f, headC + Offset(-headR * 0.38f, -headR * 0.42f), alpha = 0.7f)
    }
}

// ── 屁岱 ──

private fun DrawScope.drawPidai(s: PieceState) {
    val sc = s.scale
    val feet = s.feet
    // 身子的宽高系数：站着呼吸，蓄力压扁，空中先拉长后变圆，落地果冻一样晃
    val breathe = if (!s.flying && s.squash <= 0f) 0.025f * sin(s.time * 2.6f) else 0f
    val jelly = landBounce(s.sinceLand, 0.3f)
    val stretch = if (s.flying) 0.28f * (1f - s.flight) * (1f - s.flight) - 0.08f * sin(s.flight * PI.toFloat()) else 0f
    val squash = s.squash.coerceAtLeast(0f)
    val h = 0.34f * sc * (1f - 0.42f * squash + stretch + breathe - jelly)
    val w = 0.34f * sc * (1f + 0.28f * squash - stretch * 0.5f - breathe + jelly)
    val c = Offset(feet.x, feet.y - h / 2)
    rotate(s.tilt, feet) {
        drawOval(
            Brush.radialGradient(listOf(Color.White, Color(0xFFE8EAF2), Color(0xFFC9CDDA)), c + Offset(-w * 0.2f, -h * 0.25f), w * 0.8f),
            Offset(c.x - w / 2, c.y - h / 2), Size(w, h),
        )
        // 眼睛：看着要去的方向，站着时偶尔眨一下，蓄力时眯起来
        val look = s.side * w * (if (s.flying) 0.1f else 0.05f)
        val blink = !s.flying && squash == 0f && (s.time % 3.4f) < 0.12f
        val eyeH = when {
            blink -> h * 0.03f
            squash > 0.3f -> h * 0.08f
            else -> h * 0.2f
        }
        val eyeW = w * 0.09f
        for (dx in listOf(-0.15f, 0.15f)) {
            val e = Offset(c.x + w * dx + look, c.y - h * 0.08f)
            drawRoundRect(Color(0xFF232437), Offset(e.x - eyeW / 2, e.y - eyeH / 2), Size(eyeW, eyeH), CornerRadius(eyeW / 2))
        }
        // 两团腮红
        for (dx in listOf(-0.28f, 0.28f)) {
            drawOval(Color(0xFFFF9EB5), Offset(c.x + w * dx + look - w * 0.06f, c.y + h * 0.1f), Size(w * 0.12f, h * 0.06f), alpha = 0.6f)
        }
    }
}

// ── 小猫 ──

private val CAT_FUR = Color(0xFFF4A259)
private val CAT_DARK = Color(0xFFD9822B)
private val CAT_BELLY = Color(0xFFFFF1DC)

private fun DrawScope.drawCat(s: PieceState) {
    val sc = s.scale
    val feet = s.feet
    val squash = s.squash.coerceAtLeast(0f) + landBounce(s.sinceLand, 0.2f)
    val side = s.side.toFloat()
    // 空中身子朝前探，像扑过去；落下半程收回来
    val lean = if (s.flying) side * 28f * sin(s.flight * PI.toFloat()) else 0f
    val bodyH = 0.24f * sc * (1f - 0.35f * squash)
    val bodyW = 0.3f * sc * (1f + 0.15f * squash)
    val bodyC = Offset(feet.x, feet.y - bodyH / 2)
    rotate(s.tilt + lean, feet) {
        // 尾巴：站着慢慢摆，蓄力时翘高，空中拖在身后
        val tailBase = Offset(bodyC.x - side * bodyW * 0.42f, bodyC.y + bodyH * 0.1f)
        val sway = when {
            s.flying -> -0.2f
            squash > 0.05f -> 0.9f
            else -> 0.35f + 0.25f * sin(s.time * 2.2f)
        }
        val tailTip = tailBase + Offset(-side * sc * 0.13f, -sc * 0.2f * sway)
        drawPath(Path().apply {
            moveTo(tailBase.x, tailBase.y)
            quadraticTo(tailBase.x - side * sc * 0.14f, tailBase.y - sc * 0.02f, tailTip.x, tailTip.y)
        }, CAT_DARK, style = Stroke(sc * 0.045f, cap = StrokeCap.Round))
        // 身子和肚皮
        drawOval(Brush.horizontalGradient(listOf(CAT_FUR, CAT_DARK), bodyC.x - bodyW / 2, bodyC.x + bodyW / 2), Offset(bodyC.x - bodyW / 2, bodyC.y - bodyH / 2), Size(bodyW, bodyH))
        drawOval(CAT_BELLY, Offset(bodyC.x - bodyW * 0.2f + side * bodyW * 0.12f, bodyC.y - bodyH * 0.1f), Size(bodyW * 0.4f, bodyH * 0.5f))
        // 头：蓄力时压低贴近身子
        val headR = 0.11f * sc
        val headC = Offset(bodyC.x + side * bodyW * 0.22f, bodyC.y - bodyH * 0.45f - headR * (0.8f - 0.4f * squash))
        // 耳朵：蓄力时往后贴
        val earBack = squash * 0.5f
        for (dx in listOf(-0.62f, 0.62f)) {
            val base = headC + Offset(headR * dx, -headR * 0.55f)
            val tip = base + Offset(headR * dx * 0.2f - side * headR * earBack, -headR * (0.7f - earBack * 0.4f))
            drawPath(Path().apply {
                moveTo(base.x - headR * 0.3f, base.y + headR * 0.1f); lineTo(tip.x, tip.y); lineTo(base.x + headR * 0.3f, base.y + headR * 0.1f); close()
            }, CAT_FUR)
            drawPath(Path().apply {
                moveTo(base.x - headR * 0.14f, base.y + headR * 0.05f); lineTo(tip.x, tip.y + headR * 0.2f); lineTo(base.x + headR * 0.14f, base.y + headR * 0.05f); close()
            }, Color(0xFFFFB3C1))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF8B878), CAT_FUR, CAT_DARK), headC + Offset(-headR * 0.3f, -headR * 0.3f), headR * 1.5f), headR, headC)
        // 额头三道纹
        for (k in -1..1) drawLine(CAT_DARK, headC + Offset(k * headR * 0.25f, -headR * 0.95f), headC + Offset(k * headR * 0.2f, -headR * 0.6f), sc * 0.012f, StrokeCap.Round)
        // 眼睛：蓄力时盯紧（变细），空中睁圆
        val eyeH = if (squash > 0.3f) headR * 0.12f else headR * 0.3f
        for (dx in listOf(-0.35f, 0.35f)) {
            val e = headC + Offset(headR * dx + side * headR * 0.12f, -headR * 0.05f)
            drawOval(Color(0xFF2B2B2B), Offset(e.x - headR * 0.09f, e.y - eyeH / 2), Size(headR * 0.18f, eyeH))
        }
        drawCircle(Color(0xFFE56B6F), headR * 0.07f, headC + Offset(side * headR * 0.12f, headR * 0.22f))
    }
}

// ── 宇航员 ──

private fun DrawScope.drawAstronaut(s: PieceState) {
    val sc = s.scale
    val side = s.side.toFloat()
    // 站着时轻轻上下浮
    val float = if (!s.flying && s.squash <= 0f && s.tilt == 0f) sc * 0.012f * sin(s.time * 1.8f) else 0f
    val feet = s.feet + Offset(0f, -abs(float))
    val squash = s.squash.coerceAtLeast(0f) * 0.6f + landBounce(s.sinceLand, 0.12f)
    // 空中慢慢往前倾，落地前摆正
    val lean = if (s.flying) side * 22f * sin(s.flight * PI.toFloat()) else 0f
    val bodyH = 0.26f * sc * (1f - 0.3f * squash)
    val bodyW = 0.24f * sc * (1f + 0.12f * squash)
    rotate(s.tilt + lean, feet) {
        // 背包
        drawRoundRect(Color(0xFFB9BFCC), Offset(feet.x - side * bodyW * 0.62f - bodyW * 0.2f, feet.y - bodyH * 0.95f), Size(bodyW * 0.4f, bodyH * 0.7f), CornerRadius(sc * 0.03f))
        // 两条腿
        for (dx in listOf(-0.22f, 0.22f)) {
            drawRoundRect(Color(0xFFE3E6EE), Offset(feet.x + bodyW * dx - bodyW * 0.14f, feet.y - bodyH * 0.35f), Size(bodyW * 0.28f, bodyH * 0.35f), CornerRadius(sc * 0.03f))
        }
        // 身子
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color.White, Color(0xFFDADDE6)), feet.x - bodyW / 2, feet.x + bodyW / 2),
            Offset(feet.x - bodyW / 2, feet.y - bodyH), Size(bodyW, bodyH * 0.72f), CornerRadius(sc * 0.07f),
        )
        // 胸前一块小面板
        drawRoundRect(Color(0xFF6C8CFF), Offset(feet.x - bodyW * 0.15f + side * bodyW * 0.08f, feet.y - bodyH * 0.72f), Size(bodyW * 0.3f, bodyH * 0.16f), CornerRadius(sc * 0.015f))
        // 头盔 + 面罩
        val headR = 0.13f * sc
        val headC = Offset(feet.x, feet.y - bodyH - headR * 0.75f)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFD5D9E3)), headC + Offset(-headR * 0.3f, -headR * 0.3f), headR * 1.4f), headR, headC)
        val visor = Rect(headC.x - headR * 0.72f + side * headR * 0.1f, headC.y - headR * 0.45f, headC.x + headR * 0.72f + side * headR * 0.1f, headC.y + headR * 0.4f)
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF3A4A8A), Color(0xFF151B33)), visor.topLeft, visor.bottomRight),
            visor.topLeft, visor.size, CornerRadius(headR * 0.4f),
        )
        // 面罩反光：随浮动轻轻挪
        drawRoundRect(
            Color.White, Offset(visor.left + visor.width * 0.15f, visor.top + visor.height * (0.15f + float / sc * 3f)),
            Size(visor.width * 0.28f, visor.height * 0.16f), CornerRadius(headR * 0.1f), alpha = 0.55f,
        )
    }
}
