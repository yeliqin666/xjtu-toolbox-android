package com.xjtu.toolbox.venue

import com.xjtu.toolbox.platform.keyValueStore

/** 场馆收藏的唯一一份实现（三端共用）。 */
object VenueFavorites {

    private const val PREF_NAME = "venue_favorites"
    private const val KEY_FAVORITES = "favorite_venue_ids"

    private val store by lazy { keyValueStore(PREF_NAME) }

    /** 已收藏的场馆 id。 */
    fun all(): Set<Int> = store.getStringSet(KEY_FAVORITES)
        ?.mapNotNull { it.toIntOrNull() }
        ?.toSet()
        ?: emptySet()

    /** 翻转一个场馆的收藏状态，返回切换后的状态。 */
    fun toggle(venueId: Int): Boolean {
        val current = all()
        val next = if (venueId in current) current - venueId else current + venueId
        store.putStringSet(KEY_FAVORITES, next.map { it.toString() }.toSet())
        return venueId in next
    }
}
