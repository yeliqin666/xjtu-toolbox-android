package com.xjtu.toolbox.util

import android.content.Context

/**
 * 记录用户对各服务/快捷入口的点击习惯，用于：
 * - 决定哪些卡片显示 hint（top-N + 一些随机性，做"探索"）
 * - 决定快捷入口动态 top-4
 *
 * 数据存储在 SharedPreferences ("home_usage")，独立于 cacheDir，不会被"清除缓存"影响。
 */
object ServiceUsageTracker {
    private const val PREFS = "home_usage"

    fun record(context: Context, key: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = prefs.getInt(key, 0)
        prefs.edit().putInt(key, cur + 1).apply()
    }

    /** 获取每个 key 的使用次数 */
    fun counts(context: Context, keys: List<String>): Map<String, Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return keys.associateWith { prefs.getInt(it, 0) }
    }

    /**
     * 选出"亮点"key 集合（确定性，不随机）：
     * - 取使用次数前 [topN]（仅 count > 0），按频率降序
     * - 若总点击量太低（<3），则使用 [defaultHighlights] 作为兜底
     */
    fun highlightSet(
        context: Context,
        keys: List<String>,
        topN: Int = 5,
        defaultHighlights: Set<String> = emptySet()
    ): Set<String> {
        val cnt = counts(context, keys)
        val totalClicks = cnt.values.sum()
        if (totalClicks < 3) return defaultHighlights.intersect(keys.toSet())
        return keys
            .filter { (cnt[it] ?: 0) > 0 }
            .sortedByDescending { cnt[it] ?: 0 }
            .take(topN)
            .toSet()
    }

    /** 取使用频率前 [n] 的 key，不足用 [fallback] 顺序补齐 */
    fun topKeys(context: Context, keys: List<String>, n: Int, fallback: List<String>): List<String> {
        val cnt = counts(context, keys)
        val ranked = keys.sortedByDescending { cnt[it] ?: 0 }
        // 仅用过的 (count>0) 优先按频率，剩余从 fallback 按原顺序补齐
        val used = ranked.filter { (cnt[it] ?: 0) > 0 }
        val remaining = fallback.filter { it !in used }
        return (used + remaining).take(n)
    }
}
