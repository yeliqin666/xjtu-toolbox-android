package com.xjtu.toolbox.game.net

/**
 * 联机支持的棋种。只是一个标识——协议里 hello 消息带上它，双方用来确认
 * 「这局是同一种棋」，具体规则怎么判由各自的 [OnlineRuleAdapter] 实现。
 */
enum class GameKind(val wireId: String) {
    GOMOKU("gomoku"),
    GO("go"),
    XIANGQI("xiangqi");

    companion object {
        fun fromWireId(id: String?): GameKind? = entries.firstOrNull { it.wireId == id }
    }
}
