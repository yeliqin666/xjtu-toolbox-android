package com.xjtu.toolbox.library

import com.xjtu.toolbox.platform.keyValueStore

/** 座位收藏的唯一一份实现（三端共用）。 */
object LibraryFavorites {

    private const val PREF_NAME = "library_favorites"
    private const val KEY_FAVORITES = "favorite_seats"

    private val store by lazy { keyValueStore(PREF_NAME) }

    /** 已收藏的座位号。屏进场读一次。[toggle] 返回的是同一份的更新值。 */
    fun all(): Set<String> = store.getStringSet(KEY_FAVORITES) ?: emptySet()

    /** 翻转一个座位的收藏状态，返回写回后的集合。 */
    fun toggle(seatId: String): Set<String> {
        val current = all()
        val next = if (seatId in current) current - seatId else current + seatId
        store.putStringSet(KEY_FAVORITES, next)
        return next
    }
}
