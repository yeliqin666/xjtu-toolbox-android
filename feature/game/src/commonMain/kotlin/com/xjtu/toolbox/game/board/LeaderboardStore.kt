package com.xjtu.toolbox.game.board

import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.platform.AnonymousId

/**
 * 榜单的本地状态：这个设备在这款游戏上用什么名字上榜、以后还要不要再问。
 *
 * 按游戏分开存：玩家可能想在 2048 上挂真名、在跳一跳上匿名，这是两次独立的选择。
 *
 * 身份用反馈那套匿名 ID（`ANDROID_ID` 哈希，不含学号），一台设备一份——服务端按
 * 「游戏 + 这个 ID」去重，所以同一个人在同一款游戏里只会有一行，改了名字也是改那一行。
 */
object LeaderboardStore {

    /** 匿名上榜时上传的名字。玩家自定义的名字不允许等于它，见 [Leaderboard.sanitizeName]。 */
    const val ANONYMOUS = "匿名"

    fun uid(): String = AnonymousId.get()

    /** 本机在这款游戏上选过的身份；从没选过返回 null（那就该弹一次询问）。 */
    fun identity(game: String): Identity? {
        if (!GameStore.contains(nameKey(game))) return null
        return Identity(
            // 存的是空串 = 当时选了匿名；空串和「没存过」必须分得开，否则每次开局都会重问
            name = GameStore.getString(nameKey(game))?.takeIf { it.isNotBlank() },
            silent = GameStore.getBoolean(silentKey(game), false),
        )
    }

    fun remember(game: String, name: String?, silent: Boolean) {
        GameStore.putString(nameKey(game), name.orEmpty())
        GameStore.putBoolean(silentKey(game), silent)
    }

    /**
     * 忘掉这款游戏的身份，下次上榜重新问一遍。
     *
     * 用在名字被判不合适的场合：本机记的名字可能是在旧词表下通过的，
     * 服务端现在不收，那就不该继续拿它去撞墙。
     */
    fun forget(game: String) {
        GameStore.remove(nameKey(game))
        GameStore.remove(silentKey(game))
    }

    /** [name] 为 null 表示匿名上榜；[silent] 为 true 表示以后上榜不再询问。 */
    data class Identity(val name: String?, val silent: Boolean)

    private fun nameKey(game: String) = "board_name_$game"

    private fun silentKey(game: String) = "board_silent_$game"
}