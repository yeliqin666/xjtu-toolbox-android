package com.xjtu.toolbox.game.blocks

import kotlin.math.pow
import kotlin.random.Random

/** 三种玩法。[id] 同时用作最高分的记录 key 后缀，改了旧记录就对不上了。 */
enum class BlocksMode(val id: String, val title: String, val summary: String) {
    MARATHON("marathon", "马拉松", "每消 10 行升一级，越落越快"),
    ULTRA("ultra", "冲分", "两分钟内拿尽量多的分"),
    RISE("rise", "无尽抬升", "底部不停冒出垃圾行，速度不变"),
}

/**
 * 一次消行给界面的反馈：消了哪几行、消之前的整盘样子（界面先照它画「缩没」再画「落下」）、
 * 飘什么字、是不是大招（四消 / T-Spin）。[serial] 每次递增，界面靠它判断是不是新事件。
 */
class ClearEvent(val rows: List<Int>, val before: IntArray, val label: String, val big: Boolean, val serial: Int)

/**
 * 正在下落的方块。[x]/[y] 是它包围盒左上角在棋盘上的位置，y 向下。
 */
data class ActivePiece(val type: Int, val rot: Int, val x: Int, val y: Int) {
    /** 四个格子的绝对坐标，按 x0,y0,x1,y1… 排。 */
    fun cells(): IntArray {
        val shape = SHAPES[type][rot]
        return IntArray(8) { i -> shape[i] + if (i % 2 == 0) x else y }
    }
}

const val BOARD_WIDTH = 10
const val BOARD_HEIGHT = 22
/** 顶上两行不显示，方块在这里出生。 */
const val HIDDEN_ROWS = 2
const val GARBAGE = 8
const val PIECE_COUNT = 7

const val LOCK_DELAY_MS = 500L
internal const val MAX_LOCK_RESETS = 15
const val ULTRA_MS = 120_000L
const val RISE_MS = 7_000L
internal const val SOFT_DROP_FACTOR = 20.0
/** 软降的基准：重力比这还慢时按这个算，保证低等级下拉也够快。 */
private const val SOFT_DROP_MS = 600.0
private const val MAX_LEVEL = 20

// 方块顺序 I O T S Z J L；格子值 = 类型 + 1，0 是空，GARBAGE 是垃圾行
private val SPAWN = arrayOf(
    intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),
    intArrayOf(1, 0, 2, 0, 1, 1, 2, 1),
    intArrayOf(1, 0, 0, 1, 1, 1, 2, 1),
    intArrayOf(1, 0, 2, 0, 0, 1, 1, 1),
    intArrayOf(0, 0, 1, 0, 1, 1, 2, 1),
    intArrayOf(0, 0, 0, 1, 1, 1, 2, 1),
    intArrayOf(2, 0, 0, 1, 1, 1, 2, 1),
)

/** 四个朝向的形状。I 在 4×4 盒里转，O 不转，其余在 3×3 盒里绕中心转——和 SRS 的朝向定义一致。 */
val SHAPES: Array<Array<IntArray>> = Array(PIECE_COUNT) { type ->
    val n = if (type == 0) 4 else 3
    val states = arrayOfNulls<IntArray>(4)
    states[0] = SPAWN[type]
    for (r in 1..3) {
        val prev = states[r - 1]!!
        states[r] = if (type == 1) prev else IntArray(8) { i -> if (i % 2 == 0) n - 1 - prev[i + 1] else prev[i - 1] }
    }
    Array(4) { states[it]!! }
}

// SRS 踢墙表，照抄规范（y 向上），用的时候取反 y。键是「起始朝向 * 4 + 目标朝向」
private fun kicks(vararg v: Int) = v
private val KICKS_JLSTZ = mapOf(
    0 * 4 + 1 to kicks(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
    1 * 4 + 0 to kicks(0, 0, 1, 0, 1, -1, 0, 2, 1, 2),
    1 * 4 + 2 to kicks(0, 0, 1, 0, 1, -1, 0, 2, 1, 2),
    2 * 4 + 1 to kicks(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
    2 * 4 + 3 to kicks(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
    3 * 4 + 2 to kicks(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
    3 * 4 + 0 to kicks(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
    0 * 4 + 3 to kicks(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
)
private val KICKS_I = mapOf(
    0 * 4 + 1 to kicks(0, 0, -2, 0, 1, 0, -2, -1, 1, 2),
    1 * 4 + 0 to kicks(0, 0, 2, 0, -1, 0, 2, 1, -1, -2),
    1 * 4 + 2 to kicks(0, 0, -1, 0, 2, 0, -1, 2, 2, -1),
    2 * 4 + 1 to kicks(0, 0, 1, 0, -2, 0, 1, -2, -2, 1),
    2 * 4 + 3 to kicks(0, 0, 2, 0, -1, 0, 2, 1, -1, -2),
    3 * 4 + 2 to kicks(0, 0, -2, 0, 1, 0, -2, -1, 1, 2),
    3 * 4 + 0 to kicks(0, 0, 1, 0, -2, 0, 1, -2, -2, 1),
    0 * 4 + 3 to kicks(0, 0, -1, 0, 2, 0, -1, 2, 2, -1),
)

/**
 * 一局方块的全部规则：界面只管调用操作函数、每帧调 [tick]，再照着字段画。
 *
 * 规则按现行标准：SRS 旋转与踢墙、7 个一包的随机、500ms 落地锁定
 * （移动/旋转可续 15 次）、T-Spin（三角判定）、背靠背 ×1.5 与连击加分。
 */
class BlocksGame(val mode: BlocksMode, private val random: Random = Random.Default) {

    val cells = IntArray(BOARD_WIDTH * BOARD_HEIGHT)
    var piece: ActivePiece? = null
    var score = 0; private set
    var lines = 0; private set
    var level = 1; private set
    var over = false; private set
    /** 冲分模式时间到而结束（不是堆满）。 */
    var timeUp = false; private set
    var elapsedMs = 0L; private set
    var clearEvent: ClearEvent? = null; private set
    /** 每锁定一块加一，界面拿来震一下。 */
    var lockCount = 0; private set
    /** 最近锁定的那块落在哪四格（x0,y0,x1,y1…），界面给它闪一下。 */
    var lastLocked = IntArray(0); private set
    /** 每次直落真的往下落了至少一格就加一，界面让棋盘沉一下。 */
    var hardDrops = 0; private set
    /** 按住下拉时为 true：重力加快 [SOFT_DROP_FACTOR] 倍，每落一格加 1 分。 */
    var softDropping = false
    /** 无尽抬升：距下一次抬升已过的时间。 */
    var riseMs = 0L; private set

    private val queue = ArrayDeque<Int>()
    private var combo = -1
    private var backToBack = false
    private var gravityAcc = 0.0
    private var lockMs = 0L
    private var lockResets = 0
    private var lowestY = 0
    private var lastWasRotation = false
    private var holeCol = random.nextInt(BOARD_WIDTH)
    private var eventSerial = 0

    init {
        refill()
        spawn(queue.removeFirst())
    }

    val timeLeftMs: Long get() = (ULTRA_MS - elapsedMs).coerceAtLeast(0)

    fun cell(x: Int, y: Int): Int = cells[y * BOARD_WIDTH + x]

    fun preview(count: Int): List<Int> = queue.take(count)

    /** 当前方块直落下去的 y。 */
    fun ghostY(): Int {
        val p = piece ?: return 0
        var y = p.y
        while (fits(p.copy(y = y + 1))) y++
        return y
    }

    fun moveLeft() = shift(-1)
    fun moveRight() = shift(1)

    fun rotate(clockwise: Boolean): Boolean {
        val p = piece ?: return false
        if (over || p.type == 1) return false
        val to = (p.rot + if (clockwise) 1 else 3) % 4
        val table = (if (p.type == 0) KICKS_I else KICKS_JLSTZ).getValue(p.rot * 4 + to)
        for (i in table.indices step 2) {
            val next = p.copy(rot = to, x = p.x + table[i], y = p.y - table[i + 1])
            if (fits(next)) {
                place(next, rotated = true)
                return true
            }
        }
        return false
    }

    fun softDrop(): Boolean {
        val p = piece ?: return false
        if (over || !fits(p.copy(y = p.y + 1))) return false
        place(p.copy(y = p.y + 1), rotated = false)
        score += 1
        gravityAcc = 0.0
        return true
    }

    fun hardDrop() {
        val p = piece ?: return
        if (over) return
        val y = ghostY()
        if (y > p.y) {
            hardDrops++
            score += 2 * (y - p.y)
            piece = p.copy(y = y)
            lastWasRotation = false
        }
        lock()
    }

    /** 推进 [dtMs] 毫秒：重力、锁定计时、冲分倒计时、垃圾行抬升。 */
    fun tick(dtMs: Long) {
        if (over) return
        elapsedMs += dtMs
        if (mode == BlocksMode.ULTRA && elapsedMs >= ULTRA_MS) {
            timeUp = true
            over = true
            return
        }
        if (mode == BlocksMode.RISE) {
            riseMs += dtMs
            while (riseMs >= RISE_MS && !over) {
                riseMs -= RISE_MS
                riseGarbage()
            }
            if (over) return
        }
        val p = piece ?: return
        if (fits(p.copy(y = p.y + 1))) {
            lockMs = 0
            gravityAcc += dtMs / if (softDropping) minOf(msPerRow(), SOFT_DROP_MS) / SOFT_DROP_FACTOR else msPerRow()
            var cur = p
            while (gravityAcc >= 1.0 && fits(cur.copy(y = cur.y + 1))) {
                cur = cur.copy(y = cur.y + 1)
                gravityAcc -= 1.0
                if (softDropping) score += 1
            }
            if (cur != p) place(cur, rotated = false)
            if (!fits(cur.copy(y = cur.y + 1))) gravityAcc = 0.0
        } else {
            lockMs += dtMs
            if (lockMs >= LOCK_DELAY_MS) lock()
        }
    }

    // ── 内部 ──

    // 标准表的 1 级（每格 1 秒）在手机上太慢，整体往快挪两档
    private fun msPerRow(): Double {
        val lv = when (mode) {
            BlocksMode.MARATHON -> minOf(MAX_LEVEL, level + 2)
            BlocksMode.ULTRA, BlocksMode.RISE -> 4
        }
        return (0.8 - (lv - 1) * 0.007).pow(lv - 1) * 1000.0
    }

    private fun shift(dx: Int): Boolean {
        val p = piece ?: return false
        if (over) return false
        val next = p.copy(x = p.x + dx)
        if (!fits(next)) return false
        place(next, rotated = false)
        return true
    }

    /** 放到新位置；落地状态下的移动/旋转续锁定时间，但最多续 [MAX_LOCK_RESETS] 次。 */
    private fun place(next: ActivePiece, rotated: Boolean) {
        piece = next
        lastWasRotation = rotated
        if (next.y > lowestY) {
            lowestY = next.y
            lockResets = 0
        }
        if (!fits(next.copy(y = next.y + 1)) && lockResets < MAX_LOCK_RESETS) {
            lockMs = 0
            lockResets++
        }
    }

    internal fun fits(p: ActivePiece): Boolean {
        val c = p.cells()
        for (i in 0 until 8 step 2) {
            val x = c[i]
            val y = c[i + 1]
            if (x < 0 || x >= BOARD_WIDTH || y >= BOARD_HEIGHT) return false
            if (y >= 0 && cells[y * BOARD_WIDTH + x] != 0) return false
        }
        return true
    }

    private fun occupied(x: Int, y: Int): Boolean =
        x < 0 || x >= BOARD_WIDTH || y >= BOARD_HEIGHT || (y >= 0 && cells[y * BOARD_WIDTH + x] != 0)

    private fun lock() {
        val p = piece ?: return
        val c = p.cells()
        // 三角判定：T 最后一下是旋转，且包围盒四个角占了三个
        val tSpin = p.type == 2 && lastWasRotation &&
            listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2).count { (dx, dy) -> occupied(p.x + dx, p.y + dy) } >= 3
        var allHidden = true
        for (i in 0 until 8 step 2) {
            val y = c[i + 1]
            if (y < 0) {
                over = true
                piece = null
                return
            }
            if (y >= HIDDEN_ROWS) allHidden = false
            cells[y * BOARD_WIDTH + c[i]] = p.type + 1
        }
        piece = null
        lastLocked = c
        lockCount++
        if (allHidden) {
            over = true
            return
        }

        val full = (0 until BOARD_HEIGHT).filter { y -> (0 until BOARD_WIDTH).all { x -> cell(x, y) != 0 } }
        val before = if (full.isEmpty()) cells else cells.copyOf()
        for (y in full) {
            // 从上往下逐行删，删完上面的整体下移一格
            for (yy in y downTo 1) {
                for (x in 0 until BOARD_WIDTH) cells[yy * BOARD_WIDTH + x] = cells[(yy - 1) * BOARD_WIDTH + x]
            }
            for (x in 0 until BOARD_WIDTH) cells[x] = 0
        }
        scoreClear(full, before, tSpin)
        spawn(nextType())
    }

    private fun scoreClear(rows: List<Int>, before: IntArray, tSpin: Boolean) {
        val n = rows.size
        val multiplier = if (mode == BlocksMode.MARATHON) level else 1
        var base = if (tSpin) intArrayOf(400, 800, 1200, 1600)[n] else intArrayOf(0, 100, 300, 500, 800)[n]
        val labels = mutableListOf<String>()
        if (n > 0) {
            val difficult = n == 4 || tSpin
            if (difficult && backToBack) {
                base = base * 3 / 2
                labels += "背靠背"
            }
            backToBack = difficult
            combo++
            if (combo > 0) labels += "连击 ×$combo"
            score += (base + 50 * combo) * multiplier
            lines += n
            if (mode == BlocksMode.MARATHON) level = minOf(MAX_LEVEL, 1 + lines / 10)
        } else {
            combo = -1
            score += base * multiplier
        }
        val name = if (tSpin) "T-Spin" + (if (n > 0) " " + CLEAR_NAMES[n] else "") else if (n > 0) CLEAR_NAMES[n] else null
        if (name != null) labels.add(0, name)
        if (labels.isNotEmpty() || n > 0) {
            clearEvent = ClearEvent(rows, before, labels.joinToString(" · "), n == 4 || tSpin, ++eventSerial)
        }
    }

    private fun spawn(type: Int) {
        val p = ActivePiece(type, 0, 3, 0)
        lockMs = 0
        lockResets = 0
        gravityAcc = 0.0
        lastWasRotation = false
        if (!fits(p)) {
            over = true
            piece = null
            return
        }
        // 标准做法：出生后能落就先落一格，露出一半
        val shown = if (fits(p.copy(y = 1))) p.copy(y = 1) else p
        piece = shown
        lowestY = shown.y
    }

    private fun nextType(): Int {
        val t = queue.removeFirst()
        refill()
        return t
    }

    private fun refill() {
        while (queue.size < PIECE_COUNT) queue.addAll((0 until PIECE_COUNT).shuffled(random))
    }

    /** 整盘上移一行，底部补一行只留一个缺口的垃圾。缺口大多沿用上一行，挖起来才有节奏。 */
    fun riseGarbage() {
        if ((0 until BOARD_WIDTH).any { cell(it, 0) != 0 }) {
            over = true
            return
        }
        cells.copyInto(cells, 0, BOARD_WIDTH, cells.size)
        if (random.nextFloat() < 0.3f) holeCol = random.nextInt(BOARD_WIDTH)
        val bottom = (BOARD_HEIGHT - 1) * BOARD_WIDTH
        for (x in 0 until BOARD_WIDTH) cells[bottom + x] = if (x == holeCol) 0 else GARBAGE
        val p = piece ?: return
        if (!fits(p)) {
            val up = p.copy(y = p.y - 1)
            if (fits(up)) piece = up else over = true
        }
        lowestY = piece?.y ?: 0
    }

    companion object {
        private val CLEAR_NAMES = arrayOf("", "单消", "双消", "三消", "四消")
    }
}
