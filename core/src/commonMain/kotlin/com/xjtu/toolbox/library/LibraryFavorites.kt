package com.xjtu.toolbox.library

import com.xjtu.toolbox.platform.keyValueStore

/**
 * 座位收藏的**唯一一份实现**（三端共用）。
 *
 * ## 为什么它在共享层，而座位数据不在
 *
 * 收藏是**本机偏好**，与上游一点关系都没有：同一台机器上「我标了哪几个座位」不需要问学校，
 * 也不该跟着「这一端能不能登上游」变化。所以它不属于取数端口 [LibrarySource]，而是共享代码
 * 直接读写 `:core` 的 `KeyValueStore`：屏（[LibraryScreen] 的收藏过滤/高亮）与
 * [LibraryViewModel] 拿的都是这里同一份。
 *
 * 这也正是 `KeyValueStore` 补上 `getStringSet` / `putStringSet` 的原因 —— 补之前，
 * 「收藏存在哪儿」只能由各端实现方自己包一层（Android 是 `SharedPreferences.getStringSet`，
 * Web 是 `localStorage` 里的逗号串，两份同语义代码），见 [LibrarySource] 的 KDoc。
 *
 * ## 落盘位置（逐字沿用搬迁前，老收藏不丢）
 *
 * | | |
 * |---|---|
 * | 存储名 | `library_favorites`（Android = 同名 `SharedPreferences` 文件） |
 * | 键 | `favorite_seats` |
 * | 值 | 一组座位号（Android = `StringSet`，Web = `localStorage` 里的逗号串） |
 *
 * `by lazy` 而不是直接初始化：Android 侧 `keyValueStore` 要 `Application.onCreate` 里那次
 * `initAndroidPlatform` 先把 `Context` 交进来，而 `:core` 里的对象可能在任何时刻被触碰。
 */
object LibraryFavorites {

    private const val PREF_NAME = "library_favorites"
    private const val KEY_FAVORITES = "favorite_seats"

    private val store by lazy { keyValueStore(PREF_NAME) }

    /** 已收藏的座位号。屏进场读一次。[toggle] 返回的是同一份的更新值。 */
    fun all(): Set<String> = store.getStringSet(KEY_FAVORITES) ?: emptySet()

    /**
     * 翻转一个座位的收藏状态，返回**写回后**的集合。
     *
     * 读出来的集合一律是副本（见 `KeyValueStore.getStringSet` 的口径），所以这里 `-`/`+`
     * 生成新集合再写回 —— 与搬迁前 `AppLibrarySource.toggleFavorite` 那两行同一个次序。
     */
    fun toggle(seatId: String): Set<String> {
        val current = all()
        val next = if (seatId in current) current - seatId else current + seatId
        store.putStringSet(KEY_FAVORITES, next)
        return next
    }
}
