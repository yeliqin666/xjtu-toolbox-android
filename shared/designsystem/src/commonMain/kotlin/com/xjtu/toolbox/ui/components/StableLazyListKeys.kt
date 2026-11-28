package com.xjtu.toolbox.ui.components

/**
 * 用数据身份而非列表下标定位行。上游返回重复记录时，保留每一行并按出现次数区分。
 * 返回 String，兼容 Android Lazy 列表的状态保存。
 */
fun <T> List<T>.stableLazyListKeys(keyOf: (T) -> String): List<String> {
    val counts = mutableMapOf<String, Int>()
    return map { item ->
        val identity = keyOf(item)
        val occurrence = counts[identity] ?: 0
        counts[identity] = occurrence + 1
        "$identity#$occurrence"
    }
}
