package com.xjtu.toolbox.game.go

/**
 * 数子阶段的计算，纯 Kotlin，和 [GoBoard] 分开放是因为这段逻辑只在终局跑一次，
 * 不需要跟落子规则耦合在同一个类里，单测也更好独立写。
 *
 * 用**中国规则（数子法）**：己方的子 + 己方围住的空点 = 己方"子数"。
 * 死子先当作被提子处理（从盘面上拿掉，算对方的地），再数活子和空点。
 * 黑棋贴 3.75 子，也就是常说的"贴 7.5 目"——中国规则里贴子数是目数的一半，
 * 因为一个交叉点算一子，而日韩规则的"目"是只数空点、贴的是目数，两者换算关系是 2 子 = 1 目 * 2。
 * 这里直接用子数做加减，7.5 目 / 2 = 3.75 子，避免在 UI 里再做一次单位换算。
 */

private const val KOMI_IN_STONES = 3.75

data class ScoreResult(
    val blackArea: Int,
    val whiteArea: Int,
    val blackFinal: Double,
    val whiteFinal: Double,
    val winner: Stone,
    val margin: Double,
)

object GoScoring {

    /**
     * @param board 终局盘面
     * @param deadStones 数子阶段里被点选为"死"的棋子坐标；这些子会被当作已提，
     *        既不计入己方子数，所在的空间也会算进吃掉它们那一方的势力范围。
     */
    fun score(board: GoBoard, deadStones: Set<GoPoint>): ScoreResult {
        val size = board.size
        // effective[y*size+x]：把死子摘掉之后的"实际盘面"，数子只认这份盘面。
        val effective = Array(size * size) { i ->
            val x = i % size
            val y = i / size
            val p = GoPoint(x, y)
            if (p in deadStones) Stone.EMPTY else board.stoneAt(x, y)
        }

        fun at(x: Int, y: Int) = effective[y * size + x]

        var blackStones = 0
        var whiteStones = 0
        for (i in effective.indices) {
            when (effective[i]) {
                Stone.BLACK -> blackStones++
                Stone.WHITE -> whiteStones++
                Stone.EMPTY -> Unit
            }
        }

        // 洪水填充每一块连通的空区域，看它的边界只挨着黑、只挨着白、还是两者都有（两者都有 = 双方都不占的"公气/死活未定"）。
        val visited = BooleanArray(size * size)
        var blackTerritory = 0
        var whiteTerritory = 0

        fun neighborsOf(x: Int, y: Int): List<Pair<Int, Int>> {
            val r = ArrayList<Pair<Int, Int>>(4)
            if (x > 0) r.add(x - 1 to y)
            if (x < size - 1) r.add(x + 1 to y)
            if (y > 0) r.add(x to y - 1)
            if (y < size - 1) r.add(x to y + 1)
            return r
        }

        for (start in effective.indices) {
            if (visited[start] || effective[start] != Stone.EMPTY) continue
            val sx = start % size
            val sy = start / size
            val region = ArrayList<Int>()
            var touchesBlack = false
            var touchesWhite = false
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(sx to sy)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val (cx, cy) = stack.removeLast()
                region.add(cy * size + cx)
                for ((nx, ny) in neighborsOf(cx, cy)) {
                    when (at(nx, ny)) {
                        Stone.EMPTY -> {
                            val ni = ny * size + nx
                            if (!visited[ni]) {
                                visited[ni] = true
                                stack.addLast(nx to ny)
                            }
                        }
                        Stone.BLACK -> touchesBlack = true
                        Stone.WHITE -> touchesWhite = true
                    }
                }
            }
            if (touchesBlack && !touchesWhite) blackTerritory += region.size
            if (touchesWhite && !touchesBlack) whiteTerritory += region.size
            // 两边都挨着（或者哪边都不挨，孤悬空棋盘）的区域不属于任何一方，中国规则下就是"公"点，不计分。
        }

        val blackArea = blackStones + blackTerritory
        val whiteArea = whiteStones + whiteTerritory
        val blackFinal = blackArea - KOMI_IN_STONES
        val whiteFinal = whiteArea.toDouble()
        val winner = if (blackFinal > whiteFinal) Stone.BLACK else Stone.WHITE
        val margin = kotlin.math.abs(blackFinal - whiteFinal)
        return ScoreResult(
            blackArea = blackArea,
            whiteArea = whiteArea,
            blackFinal = blackFinal,
            whiteFinal = whiteFinal,
            winner = winner,
            margin = margin,
        )
    }
}
