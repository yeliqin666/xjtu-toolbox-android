package com.xjtu.toolbox.util

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.synchronizedBlock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * 按天累加的本机计数（小游戏时长、和小岱的交互次数），给学期总结用，不上传。
 * 存成一份 `「yyyy-MM-dd|项」→ 数` 的 JSON；[KEEP_DAYS] 之前的自动丢掉。
 */
class DailyCounters(private val storeName: String) {
    private val store get() = keyValueStore(storeName)

    fun add(item: String, amount: Long = 1, date: LocalDate = todayOnCampus()) {
        if (amount <= 0) return
        synchronizedBlock(lock) {
            val oldest = date.minus(KEEP_DAYS, DateTimeUnit.DAY).toString()
            val map = read().filterKeys { it.substringBefore('|') >= oldest }.toMutableMap()
            val key = "$date|$item"
            map[key] = (map[key] ?: 0L) + amount
            store.putString(KEY, AppJson.encodeToString(map))
        }
    }

    /** [from]..[to] 之间的记录：(日期, 项, 数)。 */
    fun between(from: LocalDate, to: LocalDate): List<Triple<LocalDate, String, Long>> = read().mapNotNull { (k, n) ->
        val date = runCatching { LocalDate.parse(k.substringBefore('|')) }.getOrNull() ?: return@mapNotNull null
        Triple(date, k.substringAfter('|'), n).takeIf { date in from..to }
    }

    private fun read(): Map<String, Long> =
        runCatching { AppJson.decodeFromString<Map<String, Long>>(store.getString(KEY) ?: "{}") }.getOrDefault(emptyMap())

    private companion object {
        const val KEY = "counts"
        const val KEEP_DAYS = 400
        val lock = Any()
    }
}
