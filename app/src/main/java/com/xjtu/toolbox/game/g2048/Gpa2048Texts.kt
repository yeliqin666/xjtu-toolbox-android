package com.xjtu.toolbox.game.g2048

/**
 * GPA 2048 的文案，全部放常量表，改文案不用去 UI 代码里翻。
 *
 * 提到等级的一律从 [GpaScale] 推，不写死，免得等级表改了文案还停在旧值。
 */
object Gpa2048Texts {

    private val lowest = GpaScale.LEVELS.first()
    private val second = GpaScale.LEVELS[GpaScale.SPAWN_INDEX_1]
    private val highest = GpaScale.LEVELS[GpaScale.WIN_INDEX]

    /** 小游戏列表里的一句话简介。 */
    val SUMMARY: String = "满盘 ${lowest.letter} 合到 ${highest.letter}"

    /** 棋盘上方的玩法提示。 */
    val HOWTO: String =
        "滑动合并，两个 ${lowest.letter} 合成 ${second.letter}，一路冲到 ${highest.letter}"

    /** 合出满级时的祝贺。合到顶不强制结束，还能接着堆。 */
    val WIN_TITLE: String = "拿到 ${highest.letter} 了！"
    val WIN_BODY: String = "满绩点达成，还能接着合更多 ${highest.letter}"
    const val WIN_CONTINUE = "继续冲分"

    /** 走投无路时的结算。绩点由调用方算好填进来。 */
    fun gameOverTitle(boardGpa: String): String = "绩点定格在 $boardGpa"
    const val RESTART = "再来一局"
    fun undoLeft(count: Int): String = "撤销一步（剩 $count）"

    const val SCORE = "分数"
    const val BEST = "最高分"
    const val BOARD_GPA = "盘面绩点"
    const val LADDER = "绩点阶梯"
}
