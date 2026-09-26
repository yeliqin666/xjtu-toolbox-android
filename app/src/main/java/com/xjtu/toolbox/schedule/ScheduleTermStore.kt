package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.data.DataCache
/**
 * 学期可读名：与日程页同一来源。
 * 优先教务学期列表/当前学期接口里的 `MC`，没有才用代码末位译名。
 */
object ScheduleTermStore {
    const val CACHE_KEY = "schedule_term_names"

    fun usableName(code: String, raw: String?): String? {
        if (code.isBlank()) return null
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name == code) return null
        return name
    }

    fun officialName(code: String, live: Map<String, String>, disk: Map<String, String>): String? {
        if (code.isBlank()) return null
        return usableName(code, live[code]) ?: usableName(code, disk[code])
    }

    fun display(code: String, live: Map<String, String>, disk: Map<String, String>): String {
        if (code.isBlank()) return ""
        return officialName(code, live, disk) ?: XjtuTime.displayTerm(code)
    }

    fun read(dataCache: DataCache): Map<String, String> =
        dataCache.read<Map<String, String>>(CACHE_KEY, Long.MAX_VALUE).orEmpty()
            .mapNotNull { (code, raw) -> usableName(code, raw)?.let { code to it } }.toMap()

    fun merge(dataCache: DataCache, names: Map<String, String>) {
        val incoming = names.mapNotNull { (code, raw) -> usableName(code, raw)?.let { code to it } }
        if (incoming.isEmpty()) return
        dataCache.write(CACHE_KEY, read(dataCache) + incoming)
    }

    fun display(code: String, dataCache: DataCache, api: ScheduleApi?): String =
        display(code, api?.termNames().orEmpty(), read(dataCache))
}
