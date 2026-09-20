package com.xjtu.toolbox.game.gomoku

/**
 * 胜负文案，全部放常量表，改文案不用去 UI 代码里翻。
 *
 * 西交赢只有一句——「正统」不需要花样。上交赢是一组自嘲文案随机挑一条，
 * 图一乐，不刻薄。
 */
object GomokuTexts {
    const val XJTU_WIN = "正统在西"

    val SJTU_WIN_TAUNTS = listOf(
        "上交这盘赢了，但西交食堂更好吃，扯平。",
        "让一让，交大数学系代表上交发言：这局赢了。",
        "闵行的棋盘比西安的宽，纯属占了地皮的便宜。",
        "上交 AI：我读的书比你多，就这五颗子的事。",
        "赢了棋，没赢排名，上交你自己清楚。",
        "这局算上交的，下一局西交找回场子。",
    )

    const val DRAW_TEXT = "棋盘下满了，谁也没连成五子——算平局，重开一盘见分晓。"

    fun sjtuWinTaunt(index: Int): String = SJTU_WIN_TAUNTS[index % SJTU_WIN_TAUNTS.size]
}
