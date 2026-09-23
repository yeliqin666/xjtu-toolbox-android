package com.xjtu.toolbox.game.gomoku

/** 胜负文案，全部放常量表，改文案不用去 UI 代码里翻。 */
object GomokuTexts {

    const val XJTU_WIN = "这局西交的"

    /** 上交赢（= 玩家输）时随机挑一条：认输的、自嘲的、以及让上交 AI 自己得意的。 */
    val SJTU_WIN_TAUNTS = listOf(
        "这局算上交的，下一局西交找回场子。",
        "上交这盘赢了，但西交食堂更好吃，扯平。",
        "五颗子都没连上，认了。",
        "这 AI 不讲情面，再来一局。",
        "光顾着进攻，家里先着火了。",
        "想得挺好，就是漏了一手。",
        "复盘一下吧，输得不冤。",
        "上交 AI：我读的书比你多，就这五颗子的事。",
        "上交 AI：不用谢，指导棋。",
        "上交 AI：这局我让了三手，你没接住。",
        "上交 AI：承让——虽然你也没让什么。",
    )

    const val DRAW_TEXT = "棋盘满了，谁也没连上。重开一盘。"

    fun sjtuWinTaunt(index: Int): String = SJTU_WIN_TAUNTS[index % SJTU_WIN_TAUNTS.size]
}
