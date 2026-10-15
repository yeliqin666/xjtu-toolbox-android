package com.xjtu.toolbox.game.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * 方块的画法：极简扁平。纯色圆角块，没有高光和描边，跟随系统深浅色；
 * 质感全靠动效——落定时轻轻一弹、消行时整行收缩消失。
 */

/** 一套配色。深浅色各一份，界面和画布都从这里取色。 */
@Immutable
internal class BlocksPalette(
    val background: Color,
    val board: Color,
    val cell: Color,
    val panel: Color,
    val ink: Color,
    val dim: Color,
    val garbage: Color,
    val dark: Boolean,
)

private val LIGHT = BlocksPalette(
    background = Color(0xFFF3F4F7), board = Color.White, cell = Color(0xFFF1F2F6), panel = Color.White,
    ink = Color(0xFF1C1E24), dim = Color(0xFF8A8F9C), garbage = Color(0xFFCBD0DA), dark = false,
)
private val DARK = BlocksPalette(
    background = Color(0xFF0E0F13), board = Color(0xFF191B21), cell = Color(0xFF22252D), panel = Color(0xFF191B21),
    ink = Color(0xFFF2F3F5), dim = Color(0xFF7C818D), garbage = Color(0xFF3A3E49), dark = true,
)

@Composable
internal fun blocksPalette(): BlocksPalette = if (LocalIsDarkTheme.current) DARK else LIGHT

private val PIECE_COLORS = longArrayOf(
    0xFF4CC9F0, 0xFFFFC43D, 0xFFB388EB, 0xFF06D6A0, 0xFFEF476F, 0xFF4361EE, 0xFFF78C6B,
)

internal fun blockColor(value: Int, palette: BlocksPalette): Color = when (value) {
    0 -> Color.Transparent
    GARBAGE -> palette.garbage
    else -> Color(PIECE_COLORS[value - 1])
}

/** 各玩法的主色；马拉松每升一级换一个颜色。 */
internal fun accentOf(mode: BlocksMode, level: Int): Color = when (mode) {
    BlocksMode.MARATHON -> Color(PIECE_COLORS[listOf(5, 0, 3, 2, 6, 4, 1)[(level - 1) % 7]])
    BlocksMode.ULTRA -> Color(0xFFF78C6B)
    BlocksMode.RISE -> Color(0xFFEF476F)
}

/** 一格：纯色圆角块，[scale] 以格子中心缩放（落定回弹、消行收缩用）。 */
internal fun DrawScope.drawFlatBlock(left: Float, top: Float, cs: Float, color: Color, alpha: Float = 1f, scale: Float = 1f) {
    val gap = cs * 0.07f
    val side = (cs - gap * 2) * scale
    val off = (cs - side) / 2
    drawRoundRect(color, Offset(left + off, top + off), Size(side, side), CornerRadius(side * 0.26f), alpha = alpha)
}

/** 画布中央画一个出生朝向的小方块：暂存 / 预览 / 选玩法插画用。 */
internal fun DrawScope.drawMini(type: Int, cs: Float, palette: BlocksPalette, alpha: Float = 1f, center: Offset = this.center) {
    val shape = SHAPES[type][0]
    var minX = 9; var maxX = 0; var minY = 9; var maxY = 0
    for (i in 0 until 8 step 2) {
        minX = minOf(minX, shape[i]); maxX = maxOf(maxX, shape[i])
        minY = minOf(minY, shape[i + 1]); maxY = maxOf(maxY, shape[i + 1])
    }
    val ox = center.x - (maxX - minX + 1) * cs / 2 - minX * cs
    val oy = center.y - (maxY - minY + 1) * cs / 2 - minY * cs
    for (i in 0 until 8 step 2) drawFlatBlock(ox + shape[i] * cs, oy + shape[i + 1] * cs, cs, blockColor(type + 1, palette), alpha)
}

/** 弹簧式回弹：t 从 0 到 1，先压下去再弹回来略微过头，最后停在 1。 */
private fun springBack(t: Float, depth: Float): Float = 1f - depth * exp(-6f * t) * cos(t * PI.toFloat() * 2.2f)

// ── 动效状态 ──

/** 一局里转瞬即逝的效果；逐帧 [step]，画布照着画。不是 Compose state，靠帧号驱动重画。 */
internal class BlocksFx {
    var lockCells = IntArray(0)
    var lockT = 1f
    var clear: ClearEvent? = null
    var clearT = 1f
    var time = 0f

    fun step(dt: Float) {
        time += dt
        lockT = minOf(1f, lockT + dt / 0.32f)
        clearT = minOf(1f, clearT + dt / CLEAR_SECONDS)
    }

    /** 消行动画还没放完。这期间界面停掉重力，相当于正规方块游戏里的消行停顿。 */
    val clearing get() = clear != null && clearT < 1f
}

internal const val CLEAR_SECONDS = 0.4f
/** 消行动画前六成是「缩没」，后四成是「上面的行落下来」。 */
private const val CLEAR_SPLIT = 0.6f

/** 整块棋盘：底板、空格、已落的块、影子、当前块、消行动画。 */
internal fun DrawScope.drawBoard(game: BlocksGame, fx: BlocksFx, cs: Float, palette: BlocksPalette) {
    val rows = BOARD_HEIGHT - HIDDEN_ROWS
    for (y in 0 until rows) for (x in 0 until BOARD_WIDTH) drawFlatBlock(x * cs, y * cs, cs, palette.cell)

    // 刚落定的四格轻轻一弹
    val bouncing = fx.lockT < 1f
    fun lockScale(x: Int, y: Int): Float {
        if (!bouncing) return 1f
        val c = fx.lockCells
        for (i in 0 until c.size step 2) if (c[i] == x && c[i + 1] == y) return springBack(fx.lockT, 0.14f)
        return 1f
    }
    val ev = fx.clear
    when {
        // 消行第一段：照消之前的盘面画，被消的行从中间往两边依次缩没
        ev != null && fx.clearT < CLEAR_SPLIT -> {
            val t = fx.clearT / CLEAR_SPLIT
            for (y in HIDDEN_ROWS until BOARD_HEIGHT) for (x in 0 until BOARD_WIDTH) {
                val v = ev.before[y * BOARD_WIDTH + x]
                if (v == 0) continue
                val s = if (y in ev.rows) {
                    val k = ((t - abs(x - 4.5f) / 4.5f * 0.4f) / 0.6f).coerceIn(0f, 1f)
                    (1f - k * k) * (1f + 0.12f * sin(k * PI.toFloat()))
                } else lockScale(x, y)
                if (s > 0f) drawFlatBlock(x * cs, (y - HIDDEN_ROWS) * cs, cs, blockColor(v, palette), scale = s)
            }
        }
        // 第二段：已经删好行的盘面，上面的行从原位置带一点回弹落下来
        ev != null && fx.clearT < 1f -> {
            val e = easeOutBack((fx.clearT - CLEAR_SPLIT) / (1f - CLEAR_SPLIT))
            val drop = IntArray(BOARD_HEIGHT)
            for (o in 0 until BOARD_HEIGHT) if (o !in ev.rows) {
                val below = ev.rows.count { it > o }
                drop[o + below] = below
            }
            for (y in HIDDEN_ROWS until BOARD_HEIGHT) for (x in 0 until BOARD_WIDTH) {
                val v = game.cell(x, y)
                if (v != 0) drawFlatBlock(x * cs, (y - HIDDEN_ROWS - drop[y] * (1f - e)) * cs, cs, blockColor(v, palette))
            }
        }
        else -> for (y in HIDDEN_ROWS until BOARD_HEIGHT) for (x in 0 until BOARD_WIDTH) {
            val v = game.cell(x, y)
            if (v != 0) drawFlatBlock(x * cs, (y - HIDDEN_ROWS) * cs, cs, blockColor(v, palette), scale = lockScale(x, y))
        }
    }

    // 堆到顶上四行以内：顶行空格泛一点红，提醒但不刺眼
    val top = (HIDDEN_ROWS until BOARD_HEIGHT).firstOrNull { y -> (0 until BOARD_WIDTH).any { game.cell(it, y) != 0 } } ?: BOARD_HEIGHT
    if (top - HIDDEN_ROWS < 4) {
        val pulse = 0.5f + 0.5f * sin(fx.time * 5f)
        for (x in 0 until BOARD_WIDTH) drawFlatBlock(x * cs, 0f, cs, Color(0xFFEF476F), alpha = 0.18f * pulse)
    }

    val p = game.piece
    if (p != null) {
        val color = blockColor(p.type + 1, palette)
        // 消行途中盘面还在变，影子落点对不上，先不画
        if (!fx.clearing) {
            val ghost = p.copy(y = game.ghostY()).cells()
            for (i in 0 until 8 step 2) {
                val gy = ghost[i + 1] - HIDDEN_ROWS
                if (gy >= 0) drawFlatBlock(ghost[i] * cs, gy * cs, cs, color, alpha = if (palette.dark) 0.22f else 0.25f)
            }
        }
        val cells = p.cells()
        for (i in 0 until 8 step 2) {
            val y = cells[i + 1] - HIDDEN_ROWS
            if (y >= 0) drawFlatBlock(cells[i] * cs, y * cs, cs, color)
        }
    }
}

/** 带一点过冲的缓出：落到位时轻轻弹一下。 */
private fun easeOutBack(t: Float): Float {
    val c = 1.4f
    val u = t.coerceIn(0f, 1f) - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}
