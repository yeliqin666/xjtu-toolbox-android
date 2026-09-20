package com.xjtu.toolbox.game.go

import kotlin.random.Random

/**
 * 围棋规则引擎，纯 Kotlin，不依赖 android.*，好让规则能跑 JVM 单测。
 *
 * 只做同屏双人对弈需要的规则：落子、提子、禁自杀、superko、虚手、悔棋。
 * 数子逻辑单独放 [GoScoring]，这里只管"这一手合不合法、下完盘面长什么样"。
 */

/** 棋子颜色；EMPTY 单独列出而不是用可空类型，是因为棋盘数组要频繁按值比较，可空类型多一层装箱判断。 */
enum class Stone { EMPTY, BLACK, WHITE }

fun Stone.opponent(): Stone = when (this) {
    Stone.BLACK -> Stone.WHITE
    Stone.WHITE -> Stone.BLACK
    Stone.EMPTY -> Stone.EMPTY
}

data class GoPoint(val x: Int, val y: Int)

/** 一手棋的结果。合法的落子会带上被提掉的子数，方便 UI 播提子动画/播音效。 */
sealed class PlayResult {
    data class Success(val captured: Int) : PlayResult()
    object Occupied : PlayResult()
    /** 落子后己方所在整块棋没有气——且没有通过提子获得气，是被禁止的自杀手。 */
    object Suicide : PlayResult()
    /** 落子后的全局盘面与历史上某一步之后的盘面完全相同（positional superko）。 */
    object Ko : PlayResult()
}

class GoBoard(val size: Int, seed: Long = 0xC0FFEE_5A17L) {

    private val cells = Array(size * size) { Stone.EMPTY }

    /** 最后一手的落点，UI 用来画标记；虚手或悔棋会清空/回退它。 */
    var lastMove: GoPoint? = null
        private set

    /** 连续虚手计数，达到 2 即进入数子阶段。任何一次正常落子都会清零。 */
    var consecutivePasses: Int = 0
        private set

    // ── Zobrist 哈希：每个 (坐标, 颜色) 组合一个随机数，局面哈希 = 所有落子格子的异或和。
    // 用固定种子生成，保证同一份代码在任何机器上跑出的哈希序列一致，可复现、可单测。
    private val zobristBlack = LongArray(size * size)
    private val zobristWhite = LongArray(size * size)
    private var currentHash: Long = 0L

    // superko 判定：记录"每一手棋合法落下之后"出现过的所有全局哈希。
    // 用 positional superko（只看盘面，不看是谁走的/打劫权），这是中国规则通行的口径。
    private val positionHistory = HashSet<Long>()

    private data class Snapshot(
        val cells: Array<Stone>,
        val lastMove: GoPoint?,
        val consecutivePasses: Int,
        val hash: Long,
        val historySize: Int,
    )

    private val undoStack = ArrayDeque<Snapshot>()

    init {
        val rng = Random(seed)
        for (i in cells.indices) {
            zobristBlack[i] = rng.nextLong()
            zobristWhite[i] = rng.nextLong()
        }
        positionHistory.add(currentHash) // 空盘也是一个"曾经出现过的局面"
    }

    private fun index(x: Int, y: Int) = y * size + x

    fun inBounds(x: Int, y: Int) = x in 0 until size && y in 0 until size

    fun stoneAt(x: Int, y: Int): Stone = cells[index(x, y)]

    fun snapshotCells(): List<Stone> = cells.toList()

    private fun neighbors(p: GoPoint): List<GoPoint> {
        val result = ArrayList<GoPoint>(4)
        if (p.x > 0) result.add(GoPoint(p.x - 1, p.y))
        if (p.x < size - 1) result.add(GoPoint(p.x + 1, p.y))
        if (p.y > 0) result.add(GoPoint(p.x, p.y - 1))
        if (p.y < size - 1) result.add(GoPoint(p.x, p.y + 1))
        return result
    }

    /** 找到 start 所在的整块同色棋（连通分量），以及它的气（相邻空点集合）。 */
    private fun groupAndLiberties(start: GoPoint): Pair<Set<GoPoint>, Set<GoPoint>> {
        val color = stoneAt(start.x, start.y)
        val group = HashSet<GoPoint>()
        val liberties = HashSet<GoPoint>()
        val stack = ArrayDeque<GoPoint>()
        stack.addLast(start)
        group.add(start)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (n in neighbors(cur)) {
                when (stoneAt(n.x, n.y)) {
                    Stone.EMPTY -> liberties.add(n)
                    color -> if (group.add(n)) stack.addLast(n)
                    else -> Unit // 对方棋子，不属于这一块，也不是气
                }
            }
        }
        return group to liberties
    }

    private fun setStone(p: GoPoint, color: Stone) {
        val i = index(p.x, p.y)
        val old = cells[i]
        // 落子/提子都要同步维护哈希：先把格子原来的贡献异或掉，再异或上新的贡献。
        // Zobrist 哈希的性质决定了顺序无所谓，但"改一格就要异或两次（去旧、加新）"不能漏。
        if (old == Stone.BLACK) currentHash = currentHash xor zobristBlack[i]
        if (old == Stone.WHITE) currentHash = currentHash xor zobristWhite[i]
        cells[i] = color
        if (color == Stone.BLACK) currentHash = currentHash xor zobristBlack[i]
        if (color == Stone.WHITE) currentHash = currentHash xor zobristWhite[i]
    }

    /**
     * 落子。规则顺序是硬约束，不能颠倒：
     * 1) 先落子；
     * 2) 再检查对方相邻的块，把没气的对方块整块提走——**必须先提子**，
     *    因为提子会腾出空点，可能正好就是我方这块棋唯一缺的那口气；
     * 3) 提完之后才检查我方这块棋是否有气——如果这时候还没气，才是真正的禁着（自杀），
     *    整手作废；如果提子之后有气了，说明这其实是一手合法的打劫式提子，不算自杀。
     *    顺序反了（先判自杀再提子）会把"提子后自己有气"的合法棋误判成非法。
     */
    fun play(x: Int, y: Int, color: Stone): PlayResult {
        require(color != Stone.EMPTY) { "落子颜色不能是 EMPTY" }
        if (!inBounds(x, y)) return PlayResult.Occupied
        if (stoneAt(x, y) != Stone.EMPTY) return PlayResult.Occupied

        val before = snapshotFor(currentHash)
        val p = GoPoint(x, y)
        setStone(p, color)

        // 先提对方无气的块。
        var captured = 0
        val opp = color.opponent()
        for (n in neighbors(p)) {
            if (stoneAt(n.x, n.y) == opp) {
                val (group, liberties) = groupAndLiberties(n)
                if (liberties.isEmpty()) {
                    for (g in group) setStone(g, Stone.EMPTY)
                    captured += group.size
                }
            }
        }

        // 再检查己方这块棋——提子已经做完，此刻查气才能正确识别"提子后自救"。
        val (_, selfLiberties) = groupAndLiberties(p)
        if (selfLiberties.isEmpty()) {
            restore(before)
            return PlayResult.Suicide
        }

        // superko：整盘哈希如果和历史上某一步之后完全一样，禁止——这也覆盖了最基本的打劫循环。
        if (currentHash in positionHistory) {
            restore(before)
            return PlayResult.Ko
        }

        undoStack.addLast(before)
        positionHistory.add(currentHash)
        lastMove = p
        consecutivePasses = 0
        return PlayResult.Success(captured)
    }

    /** 虚手。连续两次虚手由调用方（UI/对局状态机）据此转入数子阶段。 */
    fun pass() {
        undoStack.addLast(snapshotFor(currentHash))
        consecutivePasses += 1
        lastMove = null
    }

    /** 是否可以悔棋。 */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /** 悔棋一手（落子或虚手都算一手）。 */
    fun undo(): Boolean {
        val snap = undoStack.removeLastOrNull() ?: return false
        restore(snap)
        return true
    }

    private fun snapshotFor(hash: Long) = Snapshot(
        cells = cells.copyOf(),
        lastMove = lastMove,
        consecutivePasses = consecutivePasses,
        hash = hash,
        historySize = positionHistory.size,
    )

    private fun restore(snap: Snapshot) {
        for (i in cells.indices) cells[i] = snap.cells[i]
        lastMove = snap.lastMove
        consecutivePasses = snap.consecutivePasses
        currentHash = snap.hash
        // positionHistory 只增不减地记了每一步之后的哈希；悔棋要把之后新增的历史一并撤销，
        // 否则回退后再次走到同一盘面会被误判为"之前出现过"而报 superko。
        // undoStack 里剩下的每个快照都是"某一步落子之前"的哈希，它们加上当前哈希，
        // 顺序拼起来正好就是回退后应该保留的历史集合。
        if (positionHistory.size > snap.historySize) {
            val kept = LinkedHashSet<Long>()
            kept.add(0L) // 空盘哈希恒为 0，任何时候都算"出现过"
            for (s in undoStack) kept.add(s.hash)
            kept.add(currentHash)
            positionHistory.clear()
            positionHistory.addAll(kept)
        }
    }
}
