package com.xjtu.toolbox.venue

import com.xjtu.toolbox.platform.keyValueStore

/**
 * 场馆收藏的**唯一一份实现**（三端共用）—— 与 `:core` 的 `LibraryFavorites` 是同一条做法。
 *
 * ## 为什么它现在能搬进来了
 *
 * 收藏是**本机偏好**：同一台机器上「我标了哪几个场馆」不需要问学校，也不该跟着
 * 「这一端能不能登场馆站」变化。搬之前它只能留给各端自己实现（`:app` 是一个收 `Context`
 * 的 `VenueFavorites`，Web 用 `localStorage`），因为 `:core` 的 `KeyValueStore` 当时只有
 * `getString/getInt/getBoolean` 三档，而这里落盘用的是 **`SharedPreferences.getStringSet`**：
 * 用 `getString` 去读一个集合键会直接 `ClassCastException`。`KeyValueStore` 补上集合那一档
 * （`getStringSet` / `putStringSet`，与 `SharedPreferences` 语义逐条对齐）之后，这一份就能
 * 收回共享层 —— 与座位收藏那一次同一条路（见 `LibraryFavorites` 的 KDoc）。
 *
 * ## 落盘位置（逐字沿用搬迁前，老收藏不丢）
 *
 * | | |
 * |---|---|
 * | 存储名 | `venue_favorites`（Android = 同名 `SharedPreferences` 文件） |
 * | 键 | `favorite_venue_ids` |
 * | 值 | 一组场馆 id（Android = `StringSet`，Web/桌面 = 同一个 store 的字符串集合） |
 *
 * 搬迁前 `:app` 那份 `VenueFavorites` 就是这两个常量、同一个值类型、同一个 `.apply()`；
 * Android 的 `KeyValueStore` 实现直接落在同一个文件上，所以**老收藏一个不丢**。
 *
 * `by lazy` 而不是直接初始化：Android 侧 `keyValueStore` 要 `Application.onCreate` 里那次
 * `initAndroidPlatform` 先把 `Context` 交进来，而 `:core` 里的对象可能在任何时刻被触碰。
 */
object VenueFavorites {

    private const val PREF_NAME = "venue_favorites"
    private const val KEY_FAVORITES = "favorite_venue_ids"

    private val store by lazy { keyValueStore(PREF_NAME) }

    /**
     * 已收藏的场馆 id。屏进场读一次（[VenueSource.favorites]）。
     *
     * 口径逐字沿用搬迁前那份：集合里非数字的项一律丢掉（[Int] 之外的值不认），
     * 键不存在与空集合都读成空集合。
     */
    fun all(): Set<Int> = store.getStringSet(KEY_FAVORITES)
        ?.mapNotNull { it.toIntOrNull() }
        ?.toSet()
        ?: emptySet()

    /**
     * 翻转一个场馆的收藏状态，返回**切换后**的状态（与搬迁前 `VenueFavorites.toggleFavorite`
     * 同一个返回值语义）。
     *
     * 读出来的一定是副本（见 `KeyValueStore.getStringSet` 的口径），所以这里 `-`/`+` 生成新集合
     * 再写回 —— 与搬迁前那两行同一个次序。
     */
    fun toggle(venueId: Int): Boolean {
        val current = all()
        val next = if (venueId in current) current - venueId else current + venueId
        store.putStringSet(KEY_FAVORITES, next.map { it.toString() }.toSet())
        return venueId in next
    }
}
