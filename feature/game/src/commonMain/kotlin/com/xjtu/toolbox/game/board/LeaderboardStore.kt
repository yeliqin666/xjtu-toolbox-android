package com.xjtu.toolbox.game.board

import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.platform.AnonymousId

/**
 * 榜单的本地状态：这个设备在这款游戏上用什么名字上榜、以后还要不要再问。
 *
 * 按游戏分开存：玩家可能想在 2048 上挂真名、在跳一跳上匿名，这是两次独立的选择。
 *
 * 身份用反馈那套匿名 ID（`ANDROID_ID` 哈希，不含学号），一台设备一份——服务端按
 * 「游戏 + 这个 ID」去重，所以同一台设备在同一款游戏里只会有一行，改了名字也是改那一行。
 */
object LeaderboardStore {

    /** 匿名上榜时上传的名字。玩家自定义的名字不允许等于它，见 [Leaderboard.sanitizeName]。 */
    const val ANONYMOUS = "匿名"

    /**
     * 榜单身份 = 一台设备一份的匿名指纹。
     *
     * **为什么不按账户做。** 「同一个账户只有一行」需要用学号派生一个稳定 ID，但学号是公开可枚举的
     * （校区分段 + 年份 + 院系 + 序号），而 App 是公开的、盐就在源码里：拿到榜单库的人把候选挨个
     * 算一遍就能反查出每行是谁，换什么哈希都一样——实测完整学号在一张消费级显卡上是秒级。
     * 加迭代只是把成本抬到"天级"，挡得住顺手而为，挡不住铁了心的人。姓名同理，而且姓名还会变。
     * 要让"按账户"同时反推不出来，输入必须掺进一个**秘密**，而秘密只能靠数据迁移或第三方登录
     * 跨设备——这两样对一个小游戏排行榜都不划算。
     *
     * **所以定成设备级**：换设备就是新的一行，同一个人在两台设备上会有两条记录。
     * 这是刻意的取舍——上榜这件事上，宁可少一点唯一性，也不引入任何可反查的标识。
     *
     * 榜上显示什么仍由玩家自己决定：昵称留空即匿名，那才是「隐藏我是谁」的那一层。
     */
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