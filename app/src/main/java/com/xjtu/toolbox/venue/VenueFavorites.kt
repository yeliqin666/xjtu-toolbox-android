package com.xjtu.toolbox.venue

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 场馆收藏管理器
 * 
 * 使用 SharedPreferences 持久化收藏数据，通过 StateFlow 提供响应式状态更新
 */
class VenueFavorites(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _favoriteIds = MutableStateFlow<Set<Int>>(loadFavorites())
    val favoriteIds: StateFlow<Set<Int>> = _favoriteIds.asStateFlow()
    
    /**
     * 判断场馆是否已收藏
     */
    fun isFavorite(venueId: Int): Boolean = _favoriteIds.value.contains(venueId)
    
    /**
     * 切换收藏状态
     * @return 切换后的收藏状态
     */
    fun toggleFavorite(venueId: Int): Boolean {
        val current = _favoriteIds.value.toMutableSet()
        val newState = if (current.contains(venueId)) {
            current.remove(venueId)
            false
        } else {
            current.add(venueId)
            true
        }
        _favoriteIds.value = current
        saveFavorites(current)
        return newState
    }
    
    /**
     * 添加收藏
     */
    fun addFavorite(venueId: Int) {
        if (!_favoriteIds.value.contains(venueId)) {
            val current = _favoriteIds.value.toMutableSet()
            current.add(venueId)
            _favoriteIds.value = current
            saveFavorites(current)
        }
    }
    
    /**
     * 移除收藏
     */
    fun removeFavorite(venueId: Int) {
        if (_favoriteIds.value.contains(venueId)) {
            val current = _favoriteIds.value.toMutableSet()
            current.remove(venueId)
            _favoriteIds.value = current
            saveFavorites(current)
        }
    }
    
    private fun loadFavorites(): Set<Int> {
        val saved = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        return saved.mapNotNull { it.toIntOrNull() }.toSet()
    }
    
    private fun saveFavorites(ids: Set<Int>) {
        prefs.edit()
            .putStringSet(KEY_FAVORITES, ids.map { it.toString() }.toSet())
            .apply()
    }
    
    companion object {
        private const val PREFS_NAME = "venue_favorites"
        private const val KEY_FAVORITES = "favorite_venue_ids"
    }
}
