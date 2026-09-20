package com.xjtu.toolbox.game.go

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/** 对局所处的阶段。 */
enum class GoPhase {
    PLAYING,
    /** 双方连续虚手之后：点子标死活，还没定输赢。 */
    SCORING,
    FINISHED,
}

/** 落子被拒绝的原因，UI 用来给一句提示，不弹烦人的对话框。 */
enum class GoRejection { OCCUPIED, SUICIDE, KO }

/**
 * 一局围棋的可变状态，包在 Compose 状态里让 UI 能重组。
 *
 * 规则本身全部委托给纯 Kotlin 的 [GoBoard]/[GoScoring]，这个类只管"UI 需要的那层"：
 * 轮到谁、现在是哪个阶段、数子阶段点了哪些死子、以及把每一手的历史串起来好悔棋。
 *
 * 同屏双人没有"AI 对手"的概念，所以不需要单独的玩家类型——黑白双方都是人，
 * 轮流点同一块屏幕，这也是不做 AI 的直接原因之一：这里从设计上就没有为电脑输入留位置。
 */
class GoGameState(size: Int) {

    var board: GoBoard = GoBoard(size)
        private set

    var boardSize: Int by mutableStateOf(size)
        private set

    // GoBoard 内部是普通可变数组，不是 Compose 状态；它变了之后单靠"引用没变"是不会触发
    // 重组的。所以每次落子/提子/悔棋之后都把这个计数器 +1，Composable 读它就能感知到盘面变化，
    // 不用把整块棋盘数组塞进 Compose 状态、也不用给 GoBoard 加 equals。
    var version: Int by mutableStateOf(0)
        private set

    var turn: Stone by mutableStateOf(Stone.BLACK)
        private set

    var phase: GoPhase by mutableStateOf(GoPhase.PLAYING)
        private set

    var rejection: GoRejection? by mutableStateOf(null)
        private set

    /** 数子阶段里被点为"死"的棋子坐标；点同一块棋（连通同色棋块）整体切换死活。 */
    val deadStones: SnapshotStateList<GoPoint> = SnapshotStateList()

    var result: ScoreResult? by mutableStateOf(null)
        private set

    /** 认输/终局时的赢家，用于展示；SCORING 阶段确认数子后也会写这里。 */
    var winner: Stone? by mutableStateOf(null)
        private set

    /** 重开一局，可以顺便换棋盘大小。 */
    fun newGame(newSize: Int = boardSize) {
        boardSize = newSize
        board = GoBoard(newSize)
        turn = Stone.BLACK
        phase = GoPhase.PLAYING
        rejection = null
        deadStones.clear()
        result = null
        winner = null
        version++
    }

    fun tapIntersection(x: Int, y: Int) {
        when (phase) {
            GoPhase.PLAYING -> playAt(x, y)
            GoPhase.SCORING -> toggleDead(x, y)
            GoPhase.FINISHED -> Unit
        }
    }

    private fun playAt(x: Int, y: Int) {
        rejection = null
        when (val r = board.play(x, y, turn)) {
            is PlayResult.Success -> {
                turn = turn.opponent()
                version++
            }
            PlayResult.Occupied -> rejection = GoRejection.OCCUPIED
            PlayResult.Suicide -> rejection = GoRejection.SUICIDE
            PlayResult.Ko -> rejection = GoRejection.KO
        }
    }

    fun pass() {
        rejection = null
        board.pass()
        turn = turn.opponent()
        version++
        if (board.consecutivePasses >= 2) {
            phase = GoPhase.SCORING
        }
    }

    fun canUndo(): Boolean = board.canUndo() && phase == GoPhase.PLAYING

    fun undo() {
        if (!board.canUndo()) return
        rejection = null
        if (board.undo()) {
            // 每一手（落子或虚手）都恰好让 turn 翻一次面，所以悔棋只需要把 turn 翻回去，
            // 不用另外记一份"谁走了这一步"的历史。
            turn = turn.opponent()
            version++
        }
    }

    /** 数子阶段点一块棋，把它整体标记为死/活；点两下等于没点。 */
    private fun toggleDead(x: Int, y: Int) {
        val stone = board.stoneAt(x, y)
        if (stone == Stone.EMPTY) return
        val group = connectedGroup(x, y, stone)
        val alreadyDead = deadStones.containsAll(group)
        if (alreadyDead) {
            deadStones.removeAll(group)
        } else {
            deadStones.addAll(group - deadStones.toSet())
        }
    }

    private fun connectedGroup(x: Int, y: Int, color: Stone): Set<GoPoint> {
        val visited = HashSet<GoPoint>()
        val stack = ArrayDeque<GoPoint>()
        stack.addLast(GoPoint(x, y))
        visited.add(GoPoint(x, y))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val neighbors = listOf(
                cur.x - 1 to cur.y, cur.x + 1 to cur.y,
                cur.x to cur.y - 1, cur.x to cur.y + 1,
            )
            for ((nx, ny) in neighbors) {
                if (!board.inBounds(nx, ny)) continue
                if (board.stoneAt(nx, ny) != color) continue
                val np = GoPoint(nx, ny)
                if (visited.add(np)) stack.addLast(np)
            }
        }
        return visited
    }

    /** 数子阶段确认死子后终局，返回计算结果。 */
    fun confirmScore(): ScoreResult {
        val r = GoScoring.score(board, deadStones.toSet())
        result = r
        winner = r.winner
        phase = GoPhase.FINISHED
        return r
    }

    /** 认输，直接终局，不经过数子。 */
    fun resign(by: Stone) {
        winner = by.opponent()
        phase = GoPhase.FINISHED
    }
}
